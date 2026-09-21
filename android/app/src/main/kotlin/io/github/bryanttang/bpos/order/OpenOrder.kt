package io.github.bryanttang.bpos.order

import io.github.bryanttang.bpos.sync.OrderType
import java.time.Instant

/**
 * 一張從伺服器讀回來的訂單（SPEC 第四節的 `orders` 文件）。
 *
 * 跟 [Cart] 與 [io.github.bryanttang.bpos.sync.OrderIntent] 的方向相反：那兩個是平板
 * 要送出去的東西，這個是伺服器算完之後的結果。**所以這裡的金額是真的金額**，
 * 不是預估值——平板一個數字都沒有算，全部照抄文件（CLAUDE.md 第二節第一條）。
 *
 * 平板對 `orders` 只有讀取權限（firestore.rules），改單一律走伺服器。
 */
data class OpenOrder(
    val orderId: String,
    /** 內用／外帶／候位。這台平板不認得文件上的值時是 null，畫面顯示「型態未知」。 */
    val orderType: OrderType?,
    val status: OrderStatus,
    val source: OrderSource,
    /** 顯示用的桌名快照；併桌時會有多個，外帶是空的。 */
    val tableLabels: List<String>,
    /** 外帶與候位的取餐號。 */
    val pickupCode: String?,
    val lines: List<OpenOrderLine>,
    val subtotal: Int,
    val serviceCharge: Int,
    val discount: Int,
    val total: Int,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    /** 還在帳上的品項。作廢的行留在 [lines] 裡供查核，但不該混在點餐內容裡。 */
    val activeLines: List<OpenOrderLine> get() = lines.filterNot { it.isVoided }

    /** 作廢過的行。訂單明細會另外列一區，讓店員對得起帳。 */
    val voidedLines: List<OpenOrderLine> get() = lines.filter { it.isVoided }

    /** 這張單還能不能加點或退點（SPEC 第六節〈狀態轉換規則〉）。 */
    val isEditable: Boolean
        get() = status == OrderStatus.OPEN || status == OrderStatus.PENDING_CONFIRM
}

data class OpenOrderLine(
    val lineId: String,
    val name: String,
    val qty: Int,
    /** 規格選項的顯示名稱，例如「大辣」。價格已經含在 [subtotal] 裡。 */
    val options: List<String>,
    val subtotal: Int,
    /**
     * 送進廚房的時間。
     *
     * 這是訂單明細上最要緊的一個欄位：已經送出去的品項，廚房正在做，
     * 退掉它要印作廢單、而且不能從帳上消失（SPEC 第四節）。畫面上要看得出差別，
     * 否則店員會以為退掉一份還沒出的跟退掉一份已經在鍋裡的是同一回事。
     */
    val printedAt: Instant?,
    val voidedAt: Instant?,
    val voidReason: String?,
) {
    val isVoided: Boolean get() = voidedAt != null
    val isPrinted: Boolean get() = printedAt != null
}

/** 訂單狀態（SPEC 第四節）。 */
enum class OrderStatus(val wireName: String) {
    /** 顧客自助單，還沒被店員確認。 */
    PENDING_CONFIRM("pending_confirm"),

    /** 用餐中。 */
    OPEN("open"),

    /** 已結帳。 */
    CLOSED("closed"),

    /** 整張單作廢。 */
    VOIDED("voided"),

    /**
     * 這台平板不認得的狀態。
     *
     * 平板是靠 App Distribution 更新的，店裡那台可能落後伺服器好幾版
     * （SPEC 第十一節）。伺服器之後多一個狀態時，舊平板會讀到不認得的字串。
     *
     * 不猜成 [OPEN]：猜錯的方向會是把一張已經結完帳的單顯示成還在用餐中，
     * 然後被收第二次錢。畫面照樣把品項與金額列出來讓店員看，但不給任何動作。
     */
    UNKNOWN("");

    companion object {
        fun fromWireName(value: String?): OrderStatus =
            entries.firstOrNull { it.wireName == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** 這張單是誰開的。顧客自助單要在明細上看得出來，因為它需要被確認。 */
enum class OrderSource(val wireName: String) {
    STAFF("staff"),
    GUEST("guest"),
    UNKNOWN("");

    companion object {
        fun fromWireName(value: String?): OrderSource =
            entries.firstOrNull { it.wireName == value && it != UNKNOWN } ?: UNKNOWN
    }
}
