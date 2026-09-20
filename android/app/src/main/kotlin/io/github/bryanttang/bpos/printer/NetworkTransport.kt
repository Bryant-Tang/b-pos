package io.github.bryanttang.bpos.printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 走區網 TCP 的印表機連線，也就是 ESC/POS 機器慣用的 9100 port。
 *
 * SPEC 第七節指定先做這一種：印表機與平板本來就要在同一段 Wi-Fi（印表機要設靜態 IP
 * 或 DHCP 保留），而 socket 這條路沒有配對、沒有權限彈窗、沒有 USB 線會被踢掉的問題。
 *
 * 「列印不依賴網路」講的是**外網**：斷了對外連線，平板與印表機還在同一台 AP 底下，
 * 這條路照樣通，所以離線也出得了單（SPEC 第六節）。
 */
class NetworkTransport(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
) : PrinterTransport {

    @Volatile
    private var socket: Socket? = null

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeQuietly()
            // 一定要帶逾時。不帶的話拔掉電源的印表機會讓這裡卡在系統預設值，
            // 在 Linux 上是兩分鐘以上——店員早就以為當機了。
            Socket().also { fresh ->
                fresh.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket = fresh
            }
            Unit
        }
    }

    override suspend fun write(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val open = socket ?: error("還沒連上印表機（$host:$port）")
            val stream = open.getOutputStream()
            stream.write(bytes)
            stream.flush()
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { closeQuietly() }
    }

    /**
     * 關掉現在這條連線，關的時候出什麼錯都吞掉。
     *
     * 關閉失敗沒有第二條路可走，而把它變成例外只會讓「我要放棄這條連線」這件事
     * 反而失敗。重連一定會先走這裡，所以吞掉的代價只是留下一個已經不能用的 socket。
     */
    private fun closeQuietly() {
        val open = socket ?: return
        socket = null
        runCatching { open.close() }
    }

    companion object {
        /** ESC/POS 機器的慣例 port（RAW / JetDirect）。 */
        const val DEFAULT_PORT = 9100

        /**
         * 連線逾時，毫秒。
         *
         * 五秒是「同一個網段的機器該有的反應」與「店員還願意等」之間的折衷：
         * 同網段的 TCP 交握通常幾毫秒內就完成，等到五秒基本上就是機器不在了，
         * 這時候早點失敗、早點顯示警示，比讓店員盯著轉圈好。
         */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
    }
}
