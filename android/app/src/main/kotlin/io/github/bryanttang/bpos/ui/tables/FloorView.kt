package io.github.bryanttang.bpos.ui.tables

import io.github.bryanttang.bpos.tables.LiveSession
import io.github.bryanttang.bpos.tables.Table
import io.github.bryanttang.bpos.tables.TableStatus
import io.github.bryanttang.bpos.tables.tableStatuses
import java.time.Instant

/**
 * 把平面圖與現場狀態併成桌位總覽要畫的東西。
 *
 * 顏色狀態的推導在 [tableStatuses]，這裡只多做一件事：算出每張桌的單數。
 *
 * **這個單數是「這張桌現在有幾張單」，不是 SPEC 第六節〈同桌多單〉講的
 * 「本桌今日 N 張單（含已結帳的）」。** 今日那個數字要再讀 `orders_archive`，
 * 而結帳把單搬進 archive 這件事要等結帳那一段做出來才有東西可讀。
 * 現在這個數字在「前一組客人還在門口看帳單、新客人已經坐下」時會顯示 2，
 * 那正是店員最需要看到的情況，所以先這樣是有用的，只是還不完整。
 */
fun tablesOnPlan(
    tables: List<Table>,
    sessions: List<LiveSession>,
    pendingConfirmOrderIds: Set<String>,
    now: Instant,
): List<TableOnPlan> {
    val statuses = tableStatuses(
        tableIds = tables.map { it.tableId },
        sessions = sessions,
        pendingConfirmOrderIds = pendingConfirmOrderIds,
        now = now,
    )

    // 同一張單可能掛在多個 session 底下（併桌），所以數的是不重複的 orderId。
    val orderCounts = sessions
        .groupBy { it.tableId }
        .mapValues { (_, forTable) -> forTable.mapNotNull { it.orderId }.distinct().size }

    return tables.map { table ->
        TableOnPlan(
            table = table,
            // 推導函式對每一個傳進去的 tableId 都會給答案，所以這裡不會落到預設值；
            // 留著 EMPTY 是因為型別上 get 仍然可能是 null，而「桌子憑空消失」
            // 比「顯示成空桌」難查得多。
            status = statuses[table.tableId] ?: TableStatus.EMPTY,
            orderCount = orderCounts[table.tableId] ?: 0,
        )
    }
}
