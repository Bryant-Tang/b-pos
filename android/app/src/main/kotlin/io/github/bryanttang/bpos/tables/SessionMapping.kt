package io.github.bryanttang.bpos.tables

import com.google.firebase.Timestamp
import java.time.Instant

/**
 * `tenants/{storeId}/sessions/{id}` 的文件轉成 [LiveSession]。
 *
 * 與桌位、訂單那兩層同一個原則：讀不出來的那一筆跳過，其餘照常。
 * 這裡跳過一筆的後果比較輕——那張桌會少算一個 session，最多是顏色不對，
 * 不是整張平面圖打不開。
 */
fun toLiveSession(sessionId: String, data: Map<String, Any?>): LiveSession? {
    if (sessionId.isBlank()) return null
    val tableId = (data["tableId"] as? String)?.takeIf { it.isNotBlank() } ?: return null

    return LiveSession(
        sessionId = sessionId,
        tableId = tableId,
        // 只有明確寫著 active 才算在用。狀態欄位壞掉時當成不在用：
        // 猜成在用的話，那張桌會一直是「用餐中」，誰都坐不進去，
        // 而且沒有任何操作解得開——只能等人去後台改資料。
        isActive = data["status"] == "active",
        readableUntil = (data["readableUntil"] as? Timestamp)?.toDate()?.toInstant(),
        orderId = (data["orderId"] as? String)?.takeIf { it.isNotBlank() },
    )
}
