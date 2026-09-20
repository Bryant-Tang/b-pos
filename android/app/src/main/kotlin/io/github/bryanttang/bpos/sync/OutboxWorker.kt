package io.github.bryanttang.bpos.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import io.github.bryanttang.bpos.data.local.BposDatabase
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 把佇列裡的意圖送出去。
 *
 * 交給 WorkManager 而不是自己開一條背景執行緒，是因為它會把工作存進自己的資料庫：
 * App 被系統砍掉、平板重開機之後，沒送完的單還會被接著送。自己顧的執行緒
 * 一關機就沒了，而這裡躺的是店家的單。
 *
 * ## 節奏由 RetryPolicy 決定，不是 WorkManager
 *
 * 這支 worker **正常情況下不回傳 [Result.retry]**，而是自己算出下一次該醒來的時刻，
 * 用 `setInitialDelay` 排下一輪。原因是 `Result.retry()` 會走 WorkManager 自己的
 * 退避曲線（預設 EXPONENTIAL、30 秒起跳、**上限 5 小時**），跟 [RetryPolicy]
 * （2 秒起跳、封頂 5 分鐘）差了兩個數量級。
 *
 * 如果交給 WorkManager 排，會發生這件事：一批裡只要有一筆暫時性失敗，
 * 整輪就照 WorkManager 的退避往後延，而且連續失敗會越延越久——即使佇列裡
 * 還有其他**已經到期、隨時可送**的單，也得一起等。店裡 Wi-Fi 抖一下，
 * 單可能幾個小時才送得出去。`next_attempt_at` 寫得再準也沒用，
 * 因為根本沒有人在那個時刻把 worker 叫醒。
 *
 * 所以這裡讓資料庫當排程來源：[OutboxRepository.flush] 回報佇列裡最早該再送的時刻，
 * 下一輪就排在那個時刻。已經到期的會立刻再跑一輪。
 */
class OutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val storeId = inputData.getString(KEY_STORE_ID)
            ?: return Result.failure()

        val repository = OutboxRepository(
            dao = BposDatabase.get(applicationContext).orderIntentOutboxDao(),
            // getInstance() 延後到真的要送的時候才呼叫，理由見
            // FirestoreOrderIntentSender 的建構子註解。
            sender = FirestoreOrderIntentSender({ FirebaseFirestore.getInstance() }, storeId),
        )

        val report = flushOrNull { repository.flush() }
            ?: return Result.retry()

        scheduleNext(applicationContext, storeId, report, Instant.now())
        return Result.success()
    }

    companion object {
        /**
         * 跑一輪 flush，壞掉就回 null（呼叫端轉成 [Result.retry]）。
         *
         * **取消訊號一定要放行。** `kotlinx.coroutines.CancellationException`
         * 的繼承鏈是 CancellationException → IllegalStateException →
         * RuntimeException → Exception，所以一個素樸的 `catch (e: Exception)`
         * 會把它一起吃掉。那會違反 coroutine 的協作式取消慣例，也跟
         * [OutboxRepository.flush] 裡刻意重新丟出取消的做法自相矛盾——
         * 那邊才剛說「取消不是這筆意圖的失敗」，這邊又把它當成「底層壞掉」。
         *
         * 實際情境：WorkManager 因為 constraints 不再滿足（例如網路斷了）
         * 或執行超時而呼叫 onStopped() 取消這個 job 時，flush() 裡任何一個
         * suspend 點都可能丟出取消。
         *
         * 抽成獨立函式是為了讓這件事測得到——doWork() 本身要有 Context、
         * 真的 Room 資料庫與 Firestore 才跑得起來，測不了這一行。
         */
        internal suspend fun flushOrNull(flush: suspend () -> FlushReport): FlushReport? =
            try {
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 走到這裡代表比「送出失敗」更底層的東西壞了（例如資料庫打不開）。
                // flush() 內部已經把送出端丟的例外處理掉了。這種情況沒有
                // next_attempt_at 可以拿來排程，只能交給 WorkManager 的退避
                // 當安全網——它慢，但總比整條佇列從此沒有人叫醒好。
                null
            }

        const val KEY_STORE_ID = "storeId"
        private const val WORK_NAME = "order-intent-outbox"

        /**
         * 依這一輪的結果排下一輪。佇列清空就不排。
         *
         * 抽成 internal 讓測試能直接驗「算出來的延遲對不對」，
         * 不用真的跑一個 worker。
         */
        internal fun nextDelay(report: FlushReport, now: Instant): Duration? {
            val next = report.nextAttemptAt ?: return null
            val delay = Duration.between(now, next)
            // 已經到期（或時鐘往回跳）就是馬上再跑一輪。
            return if (delay.isNegative) Duration.ZERO else delay
        }

        private fun scheduleNext(
            context: Context,
            storeId: String,
            report: FlushReport,
            now: Instant,
        ) {
            val delay = nextDelay(report, now) ?: return
            enqueue(context, storeId, delay, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        /**
         * 排一輪送出。
         *
         * 用 unique work：同一時間只會有一條佇列在跑，不會兩個 worker 同時
         * 撈到同一筆意圖去送。預設 [ExistingWorkPolicy.KEEP] 代表「已經有一輪
         * 排著了就不用再排」——店員連點五次送出，排的還是同一輪。
         */
        fun enqueue(
            context: Context,
            storeId: String,
            delay: Duration = Duration.ZERO,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setInputData(workDataOf(KEY_STORE_ID to storeId))
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                // 沒網路時連跑都不用跑。Firestore 的 set() 在離線時不會回應，
                // 跑起來只是白等到逾時。
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
