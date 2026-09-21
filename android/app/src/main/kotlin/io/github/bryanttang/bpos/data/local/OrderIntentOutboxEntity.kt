package io.github.bryanttang.bpos.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 佇列裡的一筆下單意圖。
 *
 * 主鍵是 `intentId`，這同時就是冪等鍵：Firestore 那邊的文件 id 也用它，
 * 所以同一筆意圖不論重試幾次，最多只會產生一份文件
 * （見 `functions/src/orders/applyOrderIntent.ts`，觸發器是 at-least-once）。
 *
 * 品項用 JSON 存成一個欄位，沒有拆成第二張表。理由是這筆東西對本地端而言
 * 是**不可變的整體**——送出後就不再修改（Rules 那邊 `allow update: if false`），
 * 本地也從不單獨查詢某一筆品項。拆表只會換來一組 join 和一份要維護的升級腳本。
 */
@Entity(
    tableName = "order_intent_outbox",
    indices = [Index(value = ["state", "next_attempt_at"])],
)
data class OrderIntentOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "intent_id") val intentId: String,
    @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "order_type") val orderType: String,
    @ColumnInfo(name = "table_id") val tableId: String?,
    /** [io.github.bryanttang.bpos.sync.IntentLine] 的 JSON 陣列。 */
    @ColumnInfo(name = "lines_json") val linesJson: String,
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "client_created_at") val clientCreatedAtMillis: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "attempts") val attempts: Int,
    /** 早於這個時刻才輪得到它重試。第一次送出時等於建立時間，代表「馬上送」。 */
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAtMillis: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

/**
 * 佇列裡一筆意圖的狀態。
 *
 * 只有三種，而且 [REJECTED] 是終點：伺服器說這筆不合法（例如桌號不存在、
 * 品項已下架），再送一百次也是一樣的答案。重試只對「這次沒送到」有意義。
 */
enum class OutboxState(val storedName: String) {
    /** 還沒送出，或送失敗了等著重試。 */
    PENDING("pending"),

    /** 伺服器已經收下。 */
    SYNCED("synced"),

    /** 伺服器拒絕，不會再試，要讓店員看到。 */
    REJECTED("rejected"),
    ;

    companion object {
        fun fromStoredName(value: String): OutboxState =
            entries.firstOrNull { it.storedName == value }
                ?: throw IllegalArgumentException("未知的佇列狀態：$value")
    }
}
