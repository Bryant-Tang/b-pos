package io.github.bryanttang.bpos.sync

/*
 * 測試方法名一律用 ASCII，理由見 RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * worker 怎麼決定下一輪什麼時候跑。
 *
 * 這組測試釘住的是 review 在 PR #12 抓到的問題：原本只要一批裡有任何一筆失敗，
 * `doWork()` 就回傳 `Result.retry()`，把節奏交給 WorkManager 的預設退避
 * （30 秒起跳、上限 5 小時），而不是佇列自己的 RetryPolicy（2 秒起跳、封頂 5 分鐘）。
 * 後果是店裡 Wi-Fi 抖一下，已經到期可送的單也得一起等上幾小時。
 */
class OutboxWorkerTest {

    private val now = Instant.parse("2026-01-15T03:20:00Z")

    private fun report(
        sent: Int = 0,
        failed: Int = 0,
        rejected: Int = 0,
        nextAttemptAt: Instant? = null,
    ) = FlushReport(sent = sent, failed = failed, rejected = rejected, nextAttemptAt = nextAttemptAt)

    // 佇列清空就不排下一輪
    @Test
    fun `a drained queue schedules nothing`() {
        assertNull(OutboxWorker.nextDelay(report(sent = 3), now))
    }

    // 還沒到期就等到該到期的時刻
    @Test
    fun `a future attempt is scheduled for exactly that moment`() {
        val r = report(failed = 1, nextAttemptAt = now.plusSeconds(2))
        assertEquals(Duration.ofSeconds(2), OutboxWorker.nextDelay(r, now))
    }

    // 已經到期就馬上再跑
    @Test
    fun `an already-due attempt schedules immediately`() {
        val r = report(sent = 2, nextAttemptAt = now.minusSeconds(5))
        assertEquals(Duration.ZERO, OutboxWorker.nextDelay(r, now))
    }

    /**
     * 這一條是那個 bug 的正題：**這一批有失敗，但佇列裡還有已經到期的單**。
     *
     * 修好之前，`failed > 0` 會短路掉「馬上再排一輪」，整個交給 WorkManager
     * 的退避。修好之後，排程只看「最早該送的是什麼時候」——那是現在，
     * 所以延遲是零，不是 30 秒也不是 5 小時。
     */
    @Test
    fun `a failure does not delay a queue that still has due work`() {
        val r = report(sent = 1, failed = 1, nextAttemptAt = now)
        assertEquals(Duration.ZERO, OutboxWorker.nextDelay(r, now))
    }

    /**
     * 退避時間一律由 RetryPolicy 決定，所以最長只會等到它的上限（5 分鐘），
     * 不會出現 WorkManager 預設退避那種以小時計的間隔。
     */
    @Test
    fun `the longest wait is the retry policy cap, not hours`() {
        val r = report(failed = 1, nextAttemptAt = now.plus(RetryPolicy.MAX_DELAY))
        val delay = OutboxWorker.nextDelay(r, now)
        assertEquals(RetryPolicy.MAX_DELAY, delay)
        assertEquals(Duration.ofMinutes(5), delay)
    }

    /**
     * 平板的時鐘被往前調（店員改時間、NTP 校時）時，算出來的延遲會是負的。
     * 負的延遲丟進 setInitialDelay 是沒有意義的，要夾成零而不是讓它穿過去。
     */
    @Test
    fun `a clock jump never produces a negative delay`() {
        val r = report(failed = 1, nextAttemptAt = now.minus(Duration.ofHours(3)))
        assertEquals(Duration.ZERO, OutboxWorker.nextDelay(r, now))
    }
}
