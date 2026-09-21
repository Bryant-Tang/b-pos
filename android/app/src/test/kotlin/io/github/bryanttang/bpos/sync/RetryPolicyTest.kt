package io.github.bryanttang.bpos.sync

/*
 * 測試方法名一律用 ASCII，中文寫在上方的註解裡。
 *
 * Kotlin 的反引號方法名會原樣變成檔案名稱（`ClassName$方法名$1.class`），
 * 而 JVM 寫檔用的是作業系統 locale 的編碼。locale 是 POSIX / C 的機器上
 * （容器映像常見）編碼是 ASCII，中文名稱會讓 Kotlin 編譯器直接丟
 * InvalidPathException 內部錯誤——不是測試失敗，是整個測試編譯不起來。
 * 這種錯誤只在某些機器上出現，很難查，所以不要把名字改回中文。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class RetryPolicyTest {

    @Test
    // 第一次失敗等兩秒
    fun `first failure waits two seconds`() {
        assertEquals(Duration.ofSeconds(2), RetryPolicy.delayAfter(1))
    }

    @Test
    // 每失敗一次等待時間加倍
    fun `delay doubles on each failure`() {
        assertEquals(Duration.ofSeconds(2), RetryPolicy.delayAfter(1))
        assertEquals(Duration.ofSeconds(4), RetryPolicy.delayAfter(2))
        assertEquals(Duration.ofSeconds(8), RetryPolicy.delayAfter(3))
        assertEquals(Duration.ofSeconds(16), RetryPolicy.delayAfter(4))
        assertEquals(Duration.ofSeconds(32), RetryPolicy.delayAfter(5))
    }

    @Test
    // 等待時間封頂在五分鐘
    fun `delay is capped at five minutes`() {
        assertEquals(RetryPolicy.MAX_DELAY, RetryPolicy.delayAfter(9))
        assertEquals(RetryPolicy.MAX_DELAY, RetryPolicy.delayAfter(50))
    }

    /**
     * 退避是 `2 << (attempts - 1)`，指數沒夾住的話大約在第 63 次左右溢位變成負數，
     * 而負的等待時間等於「立刻重試」——變成無限迴圈狂打伺服器。
     * 平板整天不關機，斷網一整個下午是真的會累積到這種次數。
     */
    @Test
    // 重試很多次也不會溢位成負數
    fun `many retries never overflow into a negative delay`() {
        for (attempts in intArrayOf(31, 32, 62, 63, 64, 1000, Int.MAX_VALUE)) {
            val delay = RetryPolicy.delayAfter(attempts)
            assertTrue("attempts=$attempts 得到負的等待時間 $delay", !delay.isNegative)
            assertEquals("attempts=$attempts", RetryPolicy.MAX_DELAY, delay)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    // attempts 從一起算
    fun `attempts are counted from one`() {
        RetryPolicy.delayAfter(0)
    }
}
