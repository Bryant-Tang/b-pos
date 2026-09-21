package io.github.bryanttang.bpos.tables

import java.time.Instant

/**
 * 一個 session 在推導桌位狀態時用得到的部分，對應 `tenants/{storeId}/sessions/{id}`。
 *
 * 與 [TableSession] 的差別是這裡還帶著 `tableId` 與 `orderId`：前者是要把 session
 * 歸到哪一張桌，後者是要去問「那張單確認過了沒有」。[TableSession] 是推導函式的輸入，
 * 已經是「某一張桌的某一個 session」了。
 */
data class LiveSession(
    val sessionId: String,
    val tableId: String,
    val isActive: Boolean,
    /** 結帳後可讀到什麼時候；active session 為 null。 */
    val readableUntil: Instant?,
    val orderId: String?,
)

/**
 * 每一張桌現在該顯示成什麼顏色。
 *
 * 推導本身在 [deriveTableStatus]，這裡只做三件事：把 session 歸到桌、
 * 查那張單是不是還沒確認、沒有任何 session 的桌子補成空桌。
 *
 * **補空桌這一步不能省。** 少了它，一張從來沒人坐過的桌子會從結果裡消失，
 * 畫面上要嘛不顯示那張桌，要嘛得自己想辦法補預設值——而「忘了補」的結果是
 * 平面圖上破一個洞，店員以為那張桌被刪了。
 *
 * @param pendingConfirmOrderIds 還沒被店員確認的顧客自助單。
 */
fun tableStatuses(
    tableIds: Collection<String>,
    sessions: List<LiveSession>,
    pendingConfirmOrderIds: Set<String>,
    now: Instant,
): Map<String, TableStatus> {
    val byTable = sessions.groupBy { it.tableId }
    return tableIds.associateWith { tableId ->
        val forTable = byTable[tableId].orEmpty().map { session ->
            TableSession(
                sessionId = session.sessionId,
                isActive = session.isActive,
                readableUntil = session.readableUntil,
                hasPendingConfirmOrder = session.orderId != null &&
                    session.orderId in pendingConfirmOrderIds,
            )
        }
        deriveTableStatus(forTable, now)
    }
}
