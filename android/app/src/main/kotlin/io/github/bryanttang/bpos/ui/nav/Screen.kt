package io.github.bryanttang.bpos.ui.nav

import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.tables.Table

/**
 * 店員登入之後看得到的畫面（SPEC 第六節〈畫面〉）。
 *
 * 只列已經做出來的五個。設定還缺印表機那一段，
 * **刻意不先放空殼進來**（CLAUDE.md 第四節：不要生成標著 TODO 的樁）。
 * 做出來的時候在這裡加一個 entry，導覽本身不用改。
 */
sealed interface Screen {

    /** 桌位總覽，登入後的起點。 */
    data object Tables : Screen

    /**
     * 點餐。
     *
     * [tableId] 為 null 表示外帶（SPEC 第六節：外帶不掛桌號）。
     * [tableLabel] 帶著走而不是進畫面再查一次，是因為桌名可能在店員點餐的途中
     * 被後台改掉——那張單從頭到尾該顯示同一個名字。
     */
    data class Order(
        val tableId: String?,
        val tableLabel: String,
        val orderType: OrderType,
    ) : Screen

    /**
     * 待確認的顧客自助單列表。
     *
     * 不掛任何參數：那批單是全店共用的一份（見
     * [io.github.bryanttang.bpos.ui.tables.TablesController]），
     * 不像點餐與明細是綁在某一張桌上的。
     */
    data object PendingConfirm : Screen

    /** 某一張桌的訂單明細。 */
    data class OrderDetail(
        val tableId: String,
        val tableLabel: String,
    ) : Screen

    /**
     * 結帳某一張單。
     *
     * 帶著 [tableId] 是因為單的內容要即時監聽（見 CheckoutRoute），而監聽是以桌為範圍的。
     * 一張桌可能同時有好幾張單（SPEC 第六節〈同桌多單〉），所以還要 [orderId] 指名是哪一張。
     */
    data class Checkout(
        val tableId: String,
        val tableLabel: String,
        val orderId: String,
    ) : Screen
}

/** 從一張桌開一張內用單。 */
fun orderScreenFor(table: Table): Screen.Order = Screen.Order(
    tableId = table.tableId,
    tableLabel = table.label,
    orderType = OrderType.DINE_IN,
)

/** 這張桌的明細。 */
fun orderDetailFor(table: Table): Screen.OrderDetail = Screen.OrderDetail(
    tableId = table.tableId,
    tableLabel = table.label,
)

/**
 * 從明細頁加點。
 *
 * 加點就是掛同一個 tableId 開一張新單（SPEC 第六節〈同桌多單〉），
 * 所以它跟從總覽開單走的是同一個畫面，只是起點不同。
 */
fun addMoreFor(detail: Screen.OrderDetail): Screen.Order = Screen.Order(
    tableId = detail.tableId,
    tableLabel = detail.tableLabel,
    orderType = OrderType.DINE_IN,
)

/** 從明細頁結某一張單。 */
fun checkoutFor(detail: Screen.OrderDetail, orderId: String): Screen.Checkout = Screen.Checkout(
    tableId = detail.tableId,
    tableLabel = detail.tableLabel,
    orderId = orderId,
)

/**
 * 畫面的返回堆疊。
 *
 * 自己寫而不是用 navigation-compose，理由是這台平板是 kiosk：它不處理深層連結、
 * 不需要把路由編碼成字串再解回來，畫面數量也就 SPEC 那七個。自己管一個清單換來的是
 * **整個導覽可以用單元測試驗**（下面那幾條規則都不用開模擬器就測得到），
 * 而路由字串那套要跑 instrumentation test 才驗得了。
 *
 * 堆疊永遠不會是空的：底下那層固定是 [Screen.Tables]，而且建構子是私有的，
 * 唯一的入口是 [INITIAL]。
 */
@JvmInline
value class NavStack private constructor(val screens: List<Screen>) {

    val current: Screen get() = screens.last()

    val canGoBack: Boolean get() = screens.size > 1

    /**
     * 往上疊一層。
     *
     * **疊上來的跟現在這層一模一樣時什麼都不做。** 這不是最佳化，是防手滑：
     * 觸控螢幕上連點兩下同一張桌會送兩次，疊了兩層之後店員按一次返回還留在原地，
     * 看起來就像按鈕壞了。
     */
    fun push(screen: Screen): NavStack =
        if (screen == current) this else NavStack(screens + screen)

    /** 退一層。已經在最底下時什麼都不做。 */
    fun pop(): NavStack = if (canGoBack) NavStack(screens.dropLast(1)) else this

    /**
     * 直接回到桌位總覽。
     *
     * 送出一張單之後用這個而不是 [pop]：店員的下一個動作幾乎都是去招呼別桌，
     * 而且留在點餐畫面上的是一台已經送出去的空車，再按一次送出只會看到「購物車是空的」。
     */
    fun home(): NavStack = INITIAL

    companion object {
        val INITIAL = NavStack(listOf(Screen.Tables))
    }
}
