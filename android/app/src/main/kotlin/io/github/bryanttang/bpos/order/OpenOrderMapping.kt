package io.github.bryanttang.bpos.order

import com.google.firebase.Timestamp
import io.github.bryanttang.bpos.sync.OrderType
import java.time.Instant

/**
 * 把 `tenants/{storeId}/orders/{orderId}` 的文件轉成 [OpenOrder]。
 *
 * 這一層的工作只有一件事：**不要因為一個欄位壞掉，就讓整張桌的訂單讀不出來。**
 *
 * 文件是伺服器寫的，型別理論上都對。但「理論上都對」的東西在營業中出錯時，
 * 代價是店員按下那張桌卻看到一片空白，而客人正站在櫃台前等結帳。所以每個欄位
 * 都各自判斷、各自退回安全值，壞掉的是哪一格就只有那一格不對。
 *
 * 唯一不退回預設值的是狀態與型態：那兩個猜錯會讓店員對一張已經結完帳的單再收一次錢
 * （見 [OrderStatus.UNKNOWN]）。
 */
fun toOpenOrder(orderId: String, data: Map<String, Any?>): OpenOrder = OpenOrder(
    orderId = orderId,
    orderType = orderTypeOf(data["orderType"] as? String),
    status = OrderStatus.fromWireName(data["status"] as? String),
    source = OrderSource.fromWireName(data["source"] as? String),
    tableLabels = stringsOf(data["tableLabels"]),
    pickupCode = (data["pickupCode"] as? String)?.takeIf { it.isNotBlank() },
    lines = linesOf(data["lines"]),
    subtotal = intOf(data["subtotal"]),
    serviceCharge = intOf(data["serviceCharge"]),
    discount = intOf(data["discount"]),
    total = intOf(data["total"]),
    createdAt = instantOf(data["createdAt"]),
    updatedAt = instantOf(data["updatedAt"]),
)

/**
 * [OrderType.fromWireName] 在送出那條路上認不得就丟例外是對的——那是程式的 bug。
 * 讀回來這條路不一樣：不認得多半只代表這台平板比伺服器舊，丟例外會讓整張單消失。
 */
private fun orderTypeOf(raw: String?): OrderType? =
    OrderType.entries.firstOrNull { it.wireName == raw }

private fun linesOf(raw: Any?): List<OpenOrderLine> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { element ->
        val line = element as? Map<*, *> ?: return@mapNotNull null
        OpenOrderLine(
            // lineId 空字串代表這一行沒辦法指名操作（退點、拆單都是靠它）。
            // 仍然列出來而不是丟掉：帳上少一個品項是更糟的錯。
            lineId = line["lineId"] as? String ?: "",
            name = line["name"] as? String ?: "",
            qty = intOf(line["qty"]),
            options = optionNamesOf(line["options"]),
            subtotal = intOf(line["subtotal"]),
            printedAt = instantOf(line["printedAt"]),
            voidedAt = instantOf(line["voidedAt"]),
            voidReason = (line["voidReason"] as? String)?.takeIf { it.isNotBlank() },
        )
    }
}

/** 規格只取顯示名稱：加價已經算進那一行的小計，明細上再列一次只會讓人對不起來。 */
private fun optionNamesOf(raw: Any?): List<String> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
        .filter { it.isNotBlank() }
}

private fun stringsOf(raw: Any?): List<String> =
    (raw as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()

/**
 * Firestore 的數字一律回 [Long]（整數）或 [Double]（小數），不會回 Int。
 * 金額在伺服器那邊全程是整數元，所以這裡直接取整；壞掉的值當 0，
 * 不要讓一個欄位把整張單的總額變成 NaN 或讓畫面炸掉。
 */
private fun intOf(raw: Any?): Int = (raw as? Number)?.toInt() ?: 0

private fun instantOf(raw: Any?): Instant? = (raw as? Timestamp)?.toDate()?.toInstant()
