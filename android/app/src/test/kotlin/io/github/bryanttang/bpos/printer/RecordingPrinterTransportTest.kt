// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// Kotlin 的反引號方法名會原樣變成 .class 的檔名，而 JVM 寫檔用的是作業系統
// locale 的編碼。locale 是 POSIX 的機器上（容器映像很常見），中文方法名會讓
// Kotlin 編譯器丟 InvalidPathException 內部錯誤——不是測試失敗，是整個測試
// 模組編不起來，而且錯誤訊息完全看不出跟中文有關。
package io.github.bryanttang.bpos.printer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPrinterTransportTest {

    @Test
    fun `it keeps what was written`() = runBlocking {
        val transport = RecordingPrinterTransport()
        transport.connect()

        transport.write(byteArrayOf(1, 2, 3))

        assertEquals(1, transport.recorded.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), transport.recorded.single())
    }

    @Test
    fun `it keeps only the most recent jobs`() = runBlocking {
        // 設定頁只顯示最近幾張，全部留著等於讓平板整天長記憶體。
        val transport = RecordingPrinterTransport(keep = 3)
        transport.connect()

        repeat(5) { transport.write(byteArrayOf(it.toByte())) }

        assertEquals(3, transport.recorded.size)
        assertArrayEquals(byteArrayOf(2), transport.recorded.first())
        assertArrayEquals(byteArrayOf(4), transport.recorded.last())
    }

    @Test
    fun `it copies the payload instead of holding the caller's array`() = runBlocking {
        // 呼叫端重複使用同一個 buffer 的話，預覽畫面會顯示被改過的內容，
        // 而不是當初送出去的那張。
        val transport = RecordingPrinterTransport()
        transport.connect()
        val buffer = byteArrayOf(1, 2, 3)

        transport.write(buffer)
        buffer[0] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3), transport.recorded.single())
    }

    @Test
    fun `it refuses to write before connecting, exactly like the real one`() = runBlocking {
        // 假的比真的寬鬆的話，拿它測出來的「會動」到現場就不算數了。
        val transport = RecordingPrinterTransport()

        assertTrue(transport.write(byteArrayOf(1)).isFailure)
        assertTrue(transport.recorded.isEmpty())
    }

    @Test
    fun `disconnecting closes it for writing again`() = runBlocking {
        val transport = RecordingPrinterTransport()
        transport.connect()
        transport.write(byteArrayOf(1))
        transport.disconnect()

        assertTrue(transport.write(byteArrayOf(2)).isFailure)
        assertEquals(1, transport.recorded.size)
    }

    @Test
    fun `it must keep at least one job`() {
        assertTrue(
            runCatching { RecordingPrinterTransport(keep = 0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
