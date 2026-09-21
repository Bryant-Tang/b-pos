// 測試方法名一律用 ASCII，理由見 RecordingPrinterTransportTest.kt 開頭。
//
// 這支測試連的是 127.0.0.1 上臨時開的 port，不是任何真實印表機的位址
// （CLAUDE.md 第一節：真實印表機 IP 不進版控）。
package io.github.bryanttang.bpos.printer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NetworkTransportTest {

    @Test
    fun `bytes written arrive at the printer untouched`() = runBlocking {
        FakePrinter().use { printer ->
            val transport = NetworkTransport("127.0.0.1", printer.port)

            assertTrue(transport.connect().isSuccess)
            assertTrue(transport.write(PAYLOAD).isSuccess)
            // 收尾要關連線，對面才會讀到 EOF、才知道這張單送完了。
            transport.disconnect()

            assertArrayEquals(PAYLOAD, printer.awaitReceived())
        }
    }

    @Test
    fun `connecting to a printer that is not there fails instead of hanging`() = runBlocking {
        // 開了又立刻關的 port：沒有人在聽，連線會馬上被拒絕。
        val deadPort = ServerSocket(0).use { it.localPort }
        val transport = NetworkTransport("127.0.0.1", deadPort, connectTimeoutMs = 2_000)

        val result = transport.connect()

        assertTrue("應該要失敗，實際是 $result", result.isFailure)
    }

    @Test
    fun `writing without connecting fails and says so`() = runBlocking {
        val transport = NetworkTransport("127.0.0.1", 9100)

        val result = transport.write(PAYLOAD)

        assertTrue(result.isFailure)
        // 錯誤訊息要看得懂是哪一台，現場才有辦法查。
        assertTrue(
            "訊息裡應該有位址，實際是「${result.exceptionOrNull()?.message}」",
            result.exceptionOrNull()?.message.orEmpty().contains("127.0.0.1:9100"),
        )
    }

    @Test
    fun `writing after disconnect fails rather than silently doing nothing`() = runBlocking {
        FakePrinter().use { printer ->
            val transport = NetworkTransport("127.0.0.1", printer.port)
            transport.connect()
            transport.disconnect()

            // 靜靜地成功是最糟的結果：佇列會把這筆標成已完成，單子卻沒印出來。
            assertTrue(transport.write(PAYLOAD).isFailure)
        }
    }

    @Test
    fun `reconnecting drops the previous connection`() = runBlocking {
        // 印表機被拔電、換網段之後，握著舊的死連線只會讓下一單靜靜失敗。
        FakePrinter().use { printer ->
            val transport = NetworkTransport("127.0.0.1", printer.port)

            assertTrue(transport.connect().isSuccess)
            assertTrue(transport.connect().isSuccess)
            assertTrue(transport.write(PAYLOAD).isSuccess)
            transport.disconnect()

            // 第一條連線收到的是空的（連上就被關掉），第二條才拿到資料。
            assertArrayEquals(ByteArray(0), printer.awaitReceived())
            assertArrayEquals(PAYLOAD, printer.awaitReceived())
        }
    }

    @Test
    fun `disconnecting when never connected is harmless`() = runBlocking {
        // 佇列失敗收尾時會無差別呼叫一次，這裡不能炸。
        NetworkTransport("127.0.0.1", 9100).disconnect()
    }

    /** 本機開一個假印表機，把每條連線收到的位元組留下來。 */
    private class FakePrinter : AutoCloseable {
        private val server = ServerSocket(0)
        private val connections = mutableListOf<CompletableFuture<ByteArray>>()
        private val accepting = Thread {
            runCatching {
                while (true) {
                    val client = server.accept()
                    val received = CompletableFuture<ByteArray>()
                    synchronized(connections) { connections += received }
                    Thread {
                        client.use { received.complete(it.getInputStream().readBytes()) }
                    }.also { it.isDaemon = true }.start()
                }
            }
        }.also { it.isDaemon = true; it.start() }

        val port: Int get() = server.localPort

        private var taken = 0

        /** 依連線順序取下一條連線收到的全部內容。 */
        fun awaitReceived(): ByteArray {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val pending = synchronized(connections) { connections.getOrNull(taken) }
                if (pending != null) {
                    taken++
                    return pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                Thread.sleep(10)
            }
            throw AssertionError("等不到第 ${taken + 1} 條連線")
        }

        override fun close() {
            server.close()
            accepting.interrupt()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        /** 一小段 ESC/POS：重設加一個位元組的資料。內容是什麼不重要，逐位元組相同才重要。 */
        val PAYLOAD = byteArrayOf(0x1B, 0x40, 0x0A)
    }
}
