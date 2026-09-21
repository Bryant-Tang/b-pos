package io.github.bryanttang.bpos.tables

import java.time.Instant

/**
 * 桌位在總覽上顯示的狀態（SPEC 第六節〈桌位狀態機〉）。
 *
 * 這是**顯示用**的狀態，不是資料庫裡的欄位。它由那張桌目前有哪些 session
 * 與訂單推導出來，[deriveTableStatus] 是唯一的推導入口。
 */
enum class TableStatus {
    /** 空桌。無 active session，也沒有還在可讀期的已結帳 session。 */
    EMPTY,

    /** 用餐中。有 active session。 */
    OCCUPIED,

    /** 待確認。有 active session，而且其中有顧客自助單還沒確認。 */
    PENDING_CONFIRM,

    /** 已結帳。session 已結束但還在可讀期（預設 3 小時），客人可能還在門口看帳單。 */
    PAID,
}

/** 推導桌位狀態時需要知道的一個 session。 */
data class TableSession(
    val sessionId: String,
    val isActive: Boolean,
    /** 結帳後可讀到什麼時候；active session 為 null。 */
    val readableUntil: Instant?,
    /** 這個 session 底下有沒有還沒確認的顧客自助單。 */
    val hasPendingConfirmOrder: Boolean,
)

/**
 * 從一張桌目前的 session 推導出它該顯示成什麼狀態。
 *
 * **同一張桌可以同時有一個已結帳但仍可讀的 session（前一組客人正在門口看帳單）
 * 和一個 active session（新客人已經坐下）。** SPEC 明講不要在資料層或 UI 層
 * 假設「一桌一 session」，所以這裡收的是一個清單，而且 active 優先於已結帳：
 * 已結帳的 session 只影響帳單的可讀性，不影響這張桌現在是不是有人在用。
 *
 * 可讀期用 `>` 而不是 `>=`：readableUntil 是「可讀到這個時刻為止」，
 * 剛好到點就已經過期。邊界差一毫秒不影響店員，但兩邊（這裡與伺服器的
 * releaseTables 排程）用同一個判斷才不會出現一邊顯示已結帳、一邊已經釋放。
 */
fun deriveTableStatus(sessions: List<TableSession>, now: Instant): TableStatus {
    val active = sessions.filter { it.isActive }

    if (active.isNotEmpty()) {
        return if (active.any { it.hasPendingConfirmOrder }) {
            TableStatus.PENDING_CONFIRM
        } else {
            TableStatus.OCCUPIED
        }
    }

    val stillReadable = sessions.any { session ->
        val until = session.readableUntil
        until != null && until > now
    }

    return if (stillReadable) TableStatus.PAID else TableStatus.EMPTY
}
