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
import io.github.bryanttang.bpos.data.local.BposDatabase

/**
 * 把佇列裡的意圖送出去。
 *
 * 交給 WorkManager 而不是自己開一條背景執行緒，是因為它會把工作存進自己的資料庫：
 * App 被系統砍掉、平板重開機之後，沒送完的單還會被接著送。自己顧的執行緒
 * 一關機就沒了，而這裡躺的是店家的單。
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
            sender = FirestoreOrderIntentSender(FirebaseFirestore.getInstance(), storeId),
        )

        val report = repository.flush()

        return when {
            // 這一批裡有送失敗的，交給 WorkManager 依它自己的退避重排。
            // 每一筆的 next_attempt_at 仍由 RetryPolicy 決定，所以早排一輪
            // 也不會讓還沒到時間的意圖提前送出，只是白跑一趟。
            report.failed > 0 -> Result.retry()

            // 還有到期的沒處理完（這一批只拿了 batchSize 筆），馬上再接一輪。
            report.hasMoreDue -> {
                enqueue(applicationContext, storeId, ExistingWorkPolicy.APPEND_OR_REPLACE)
                Result.success()
            }

            else -> Result.success()
        }
    }

    companion object {
        const val KEY_STORE_ID = "storeId"
        private const val WORK_NAME = "order-intent-outbox"

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
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setInputData(workDataOf(KEY_STORE_ID to storeId))
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
