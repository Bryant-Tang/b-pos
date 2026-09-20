package io.github.bryanttang.bpos.sync

import io.github.bryanttang.bpos.data.local.OrderIntentOutboxDao
import io.github.bryanttang.bpos.data.local.OutboxState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * 下單意圖的離線佇列。
 *
 * SPEC 第六節〈離線策略〉的核心：店員按下送出 → 寫進這裡就算完成、UI 立刻反應，
 * 送到伺服器是後面非同步的事。斷網時店員照樣點得了餐、出得了單。
 */
class OutboxRepository(
    private val dao: OrderIntentOutboxDao,
    private val sender: OrderIntentSender,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 把一筆意圖放進佇列。
     *
     * 回傳 `true` 表示這次真的新增了一筆；`false` 表示這個 `intentId` 已經在佇列裡，
     * 什麼都沒有改變。店員連按兩下送出、或是上層重試同一個動作時會走到 `false`，
     * 這是正常情況，不是錯誤。
     */
    suspend fun enqueue(intent: OrderIntent): Boolean =
        dao.insertIfAbsent(intent.toOutboxEntity()) != -1L

    /**
     * 送出目前該送的意圖，回傳這一輪的結果。
     *
     * 一次處理 [batchSize] 筆就回來，不把佇列一口氣清完：WorkManager 的單次執行
     * 有時間上限，積了幾百筆時硬跑會被系統砍在半路，而被砍掉的那一輪
     * 連 attempts 都來不及寫回去。
     */
    suspend fun flush(batchSize: Int = DEFAULT_BATCH_SIZE): FlushReport {
        val now = clock.millis()
        val due = dao.dueForSending(now, batchSize)

        var sent = 0
        var failed = 0
        var rejected = 0

        for (entry in due) {
            val intent = try {
                entry.toOrderIntent()
            } catch (e: IllegalArgumentException) {
                // 資料列壞掉（欄位對不上、JSON 壞了）。重試不會讓它變好，
                // 而且這筆永遠送不出去的東西會一直卡在隊伍最前面。
                dao.markRejected(entry.intentId, "本地資料毀損：${e.message}")
                rejected++
                continue
            }

            when (val result = sender.send(intent)) {
                // 已經在伺服器上跟這次剛送成功，對佇列而言是同一件事。
                SendResult.Accepted, SendResult.AlreadyPresent -> {
                    dao.markSynced(entry.intentId)
                    sent++
                }

                is SendResult.Rejected -> {
                    dao.markRejected(entry.intentId, result.reason)
                    rejected++
                }

                is SendResult.Failed -> {
                    val attempts = entry.attempts + 1
                    val nextAttemptAt = now + RetryPolicy.delayAfter(attempts).toMillis()
                    dao.markFailed(entry.intentId, nextAttemptAt, result.error)
                    failed++
                }
            }
        }

        return FlushReport(
            sent = sent,
            failed = failed,
            rejected = rejected,
            hasMoreDue = dao.countDue(clock.millis()) > 0,
        )
    }

    /** 還沒送到伺服器的筆數。UI 常駐顯示的「待同步 N 筆」用這個。 */
    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /**
     * 被伺服器拒絕、需要店員處理的意圖。
     *
     * 資料列毀損時 [OrderIntentOutboxEntity.toOrderIntent] 會丟例外，這裡直接濾掉：
     * 這條流是要畫到畫面上的，讓它整條炸掉的話，店員看到的是一個空白畫面，
     * 而不是「有幾筆單被拒絕了」。壞掉那幾筆的 last_error 仍留在資料庫裡可查。
     */
    fun observeRejected(): Flow<List<RejectedIntent>> =
        dao.observeRejected().map { entries ->
            entries.mapNotNull { entry ->
                val intent = runCatching { entry.toOrderIntent() }.getOrNull()
                intent?.let { RejectedIntent(it, entry.lastError) }
            }
        }

    suspend fun stateOf(intentId: String): OutboxState? =
        dao.findById(intentId)?.let { OutboxState.fromStoredName(it.state) }

    companion object {
        const val DEFAULT_BATCH_SIZE = 20
    }
}

/** 一筆被伺服器拒絕的意圖，連同拒絕的理由。 */
data class RejectedIntent(
    val intent: OrderIntent,
    val reason: String?,
)

/**
 * 一輪 flush 的結果。
 *
 * [hasMoreDue] 是給排程用的：還有到期的意圖沒處理完就馬上再排一輪，
 * 不要等到下一次退避時間才動。
 */
data class FlushReport(
    val sent: Int,
    val failed: Int,
    val rejected: Int,
    val hasMoreDue: Boolean,
)
