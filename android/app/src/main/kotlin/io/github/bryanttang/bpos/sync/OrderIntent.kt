package io.github.bryanttang.bpos.sync

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 店員送出的一筆「下單意圖」。
 *
 * 這個型別刻意**沒有任何金額欄位**，而且之後也不該加。
 * 平板只說「哪些品項、各幾份」，價格一律由伺服器從 published/menu 查出來算
 * （CLAUDE.md 第二節第一條、SPEC 第零節）。伺服器那邊的 zod schema 是 `.strict()`，
 * 多送一個 price 欄位不會被忽略，而是整筆被拒絕——所以這裡多加欄位不只是沒用，
 * 是會把單弄丟。
 *
 * 欄位與 `functions/src/orders/intentSchema.ts` 一一對應，改動要兩邊一起改。
 */
data class OrderIntent(
    val intentId: String,
    val orderId: String,
    val orderType: OrderType,
    val tableId: String?,
    val lines: List<IntentLine>,
    val createdBy: String,
    val clientCreatedAt: Instant,
) {
    init {
        require(lines.isNotEmpty()) { "意圖至少要有一筆品項" }
        require(lines.size <= MAX_LINES) { "意圖最多 $MAX_LINES 筆品項" }
        // 內用一定要有桌號，外帶與候位則不可帶桌號（SPEC 第十五節〈三種訂單型態〉）。
        // 伺服器會擋，但在這裡就擋掉的話，錯誤會出現在店員按下送出的當下，
        // 而不是幾秒後從雲端回來一則看不懂的拒絕訊息。
        when (orderType) {
            OrderType.DINE_IN -> require(tableId != null) { "內用單必須帶桌號" }
            OrderType.TAKEOUT, OrderType.WAITLIST ->
                require(tableId == null) { "外帶與候位單不可帶桌號，綁桌要走型態轉換" }
        }
    }

    companion object {
        const val MAX_LINES = 100
    }
}

enum class OrderType(val wireName: String) {
    DINE_IN("dine_in"),
    TAKEOUT("takeout"),
    WAITLIST("waitlist"),
    ;

    companion object {
        fun fromWireName(value: String): OrderType =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("未知的訂單型態：$value")
    }
}

@Serializable
data class IntentLine(
    val itemId: String,
    val qty: Int,
    val options: List<IntentLineOption> = emptyList(),
) {
    init {
        require(qty in 1..MAX_QTY) { "數量必須介於 1 與 $MAX_QTY 之間，收到 $qty" }
        require(options.size <= MAX_OPTIONS) { "單筆品項最多 $MAX_OPTIONS 個選項" }
    }

    companion object {
        const val MAX_QTY = 999
        const val MAX_OPTIONS = 20
    }
}

@Serializable
data class IntentLineOption(
    val groupId: String,
    val optionId: String,
)
