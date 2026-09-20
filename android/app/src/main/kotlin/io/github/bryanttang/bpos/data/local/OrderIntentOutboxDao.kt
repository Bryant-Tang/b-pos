package io.github.bryanttang.bpos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderIntentOutboxDao {

    /**
     * 放一筆意圖進佇列。
     *
     * 衝突時 **IGNORE 而不是 REPLACE**：`intentId` 已經在裡面，代表這筆早就送出去
     * 或正在送，重複的送出請求（店員連點兩下、WorkManager 重跑）不該把 attempts
     * 與 state 洗掉重來。回傳 -1 就表示這次沒有真的寫進去。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: OrderIntentOutboxEntity): Long

    @Query("SELECT * FROM order_intent_outbox WHERE intent_id = :intentId")
    suspend fun findById(intentId: String): OrderIntentOutboxEntity?

    /**
     * 撈出這個時間點該送的意圖，舊的先送。
     *
     * 依 `client_created_at` 排序而不是插入順序，是為了讓同一張單的加點
     * 照店員實際操作的先後抵達伺服器。
     */
    @Query(
        """
        SELECT * FROM order_intent_outbox
        WHERE state = 'pending' AND next_attempt_at <= :now
        ORDER BY client_created_at ASC
        LIMIT :limit
        """,
    )
    suspend fun dueForSending(now: Long, limit: Int): List<OrderIntentOutboxEntity>

    @Query("UPDATE order_intent_outbox SET state = 'synced', last_error = NULL WHERE intent_id = :intentId")
    suspend fun markSynced(intentId: String)

    @Query(
        """
        UPDATE order_intent_outbox
        SET state = 'rejected', last_error = :reason
        WHERE intent_id = :intentId
        """,
    )
    suspend fun markRejected(intentId: String, reason: String)

    @Query(
        """
        UPDATE order_intent_outbox
        SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt, last_error = :error
        WHERE intent_id = :intentId
        """,
    )
    suspend fun markFailed(intentId: String, nextAttemptAt: Long, error: String?)

    /** 還沒送到伺服器的筆數，UI 的「待同步 N 筆」用這個（SPEC 第六節）。 */
    @Query("SELECT COUNT(*) FROM order_intent_outbox WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>

    /** 被伺服器拒絕、需要店員處理的意圖。 */
    @Query("SELECT * FROM order_intent_outbox WHERE state = 'rejected' ORDER BY client_created_at ASC")
    fun observeRejected(): Flow<List<OrderIntentOutboxEntity>>

    /**
     * 佇列裡最早該再送的時刻，沒有待送的就是 null。
     *
     * 這是排程的依據：下一次 worker 要在這個時刻跑，而不是交給 WorkManager
     * 自己的退避決定。回傳「時刻」而不是「有沒有到期」，是因為排程需要知道
     * 「還要等多久」，只知道「現在沒有到期的」沒辦法算出下一次該何時醒來。
     */
    @Query("SELECT MIN(next_attempt_at) FROM order_intent_outbox WHERE state = 'pending'")
    suspend fun nextPendingAttemptAt(): Long?
}
