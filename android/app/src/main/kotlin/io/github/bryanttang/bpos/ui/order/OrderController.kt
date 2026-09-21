package io.github.bryanttang.bpos.ui.order

import io.github.bryanttang.bpos.menu.Menu
import io.github.bryanttang.bpos.menu.MenuItem
import io.github.bryanttang.bpos.menu.MenuLoad
import io.github.bryanttang.bpos.order.Cart
import io.github.bryanttang.bpos.order.CartChange
import io.github.bryanttang.bpos.order.OptionSelection
import io.github.bryanttang.bpos.order.OrderDraft
import io.github.bryanttang.bpos.order.OrderSubmitter
import io.github.bryanttang.bpos.order.SubmitResult
import io.github.bryanttang.bpos.sync.OrderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 規格選項的 bottom sheet 現在開在哪個品項上、已經選了什麼。 */
data class PendingOptions(
    val item: MenuItem,
    val selection: OptionSelection = emptyMap(),
)

/**
 * 點餐畫面現在的樣子。
 *
 * [menu] 為 null 而且 [loading] 為 false，表示這台平板連一份菜單都沒有
 * （見 [MenuLoad.Unavailable]）——畫面要明白講「請連上網路後重新開店」，
 * 不是顯示一份空菜單。
 */
data class OrderUiState(
    val menu: Menu? = null,
    val menuFromCache: Boolean = false,
    val loading: Boolean = true,
    val selectedCategoryId: String? = null,
    val cart: Cart = Cart(),
    val pendingOptions: PendingOptions? = null,
    val submitting: Boolean = false,
    val submitted: Boolean = false,
    /** 要顯示給店員看的一句話，看過就該收掉（[dismissMessage]）。 */
    val message: String? = null,
)

/**
 * 點餐畫面的狀態機。
 *
 * 不碰 Compose 也不碰 Firebase，所以下面幾條規則都測得到：
 *
 * - **一張單的 [OrderDraft] 只產生一次。** 店員從按下第一個品項到送出，
 *   用的是同一組 intentId/orderId，連按兩次送出在佇列裡只會是一筆
 *   （SPEC 第六節、[OrderSubmitter]）。id 如果改成按下送出時才產生，
 *   連按兩次就是實實在在的兩張單，而且是斷網時最容易發生。
 * - **送出中不能再送一次。** 理由同 LoginController：沒有立刻反應時店員就是會再按。
 * - **送出成功之後這張單就關起來了**（[OrderUiState.submitted]），
 *   不會因為再按一次而又送一張。
 */
class OrderController(
    /**
     * 抓菜單。收一個函式而不是 [io.github.bryanttang.bpos.menu.MenuRepository] 本身，
     * 是為了讓這個狀態機的測試不用開 Room——它要驗的是「拿到／沒拿到菜單各自怎麼辦」，
     * 快取與版本比對那一層有 MenuRepositoryTest 在顧。
     */
    private val fetchMenu: suspend (storeId: String) -> MenuLoad,
    private val submitter: OrderSubmitter,
    private val tableId: String?,
    private val orderType: OrderType,
    private val draft: OrderDraft = OrderDraft.new(),
) {

    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state.asStateFlow()

    /** 開店時抓一次菜單。進畫面就呼叫，重複呼叫是安全的。 */
    suspend fun loadMenu(storeId: String) {
        when (val load = fetchMenu(storeId)) {
            is MenuLoad.Ready -> _state.update {
                it.copy(
                    menu = load.menu,
                    menuFromCache = load.fromCache,
                    loading = false,
                    selectedCategoryId = it.selectedCategoryId
                        ?: load.menu.categories.minByOrNull { c -> c.sort }?.categoryId,
                )
            }

            MenuLoad.Unavailable -> _state.update {
                it.copy(menu = null, loading = false)
            }
        }
    }

    fun onCategorySelect(categoryId: String) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    /**
     * 按下一個品項。
     *
     * 有規格選項的先把 sheet 拉起來，沒有的直接進購物車——多一次「確定」對
     * 沒有選項的品項只是白按一下，而點餐是整天重複幾百次的動作。
     */
    fun onItemClick(item: MenuItem) {
        val menu = _state.value.menu ?: return
        if (menu.optionGroupsOf(item).isEmpty()) {
            addToCart(item, emptyMap())
        } else {
            _state.update { it.copy(pendingOptions = PendingOptions(item)) }
        }
    }

    fun onSelectionChange(selection: OptionSelection) {
        _state.update { current ->
            val pending = current.pendingOptions ?: return@update current
            current.copy(pendingOptions = pending.copy(selection = selection))
        }
    }

    /** sheet 上按下確定。選項合不合法由 sheet 自己擋（`checkSelection`），這裡只負責加。 */
    fun onOptionsConfirm() {
        val pending = _state.value.pendingOptions ?: return
        _state.update { it.copy(pendingOptions = null) }
        addToCart(pending.item, pending.selection)
    }

    fun onOptionsDismiss() {
        _state.update { it.copy(pendingOptions = null) }
    }

    fun onQtyChange(index: Int, qty: Int) {
        apply(_state.value.cart.setQty(index, qty))
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * 送出這張單。
     *
     * 回傳 true 表示已經進佇列，呼叫端可以離開這個畫面了。
     * 「先前已經送過同一張」也回 true：對店員而言那跟成功一樣，不是錯誤。
     */
    suspend fun submit(createdBy: String): Boolean {
        val current = _state.value
        if (current.submitting || current.submitted) return false

        _state.update { it.copy(submitting = true, message = null) }

        val result = submitter.submit(
            draft = draft,
            cart = current.cart,
            orderType = orderType,
            tableId = tableId,
            createdBy = createdBy,
        )

        return when (result) {
            SubmitResult.Queued, SubmitResult.AlreadyQueued -> {
                _state.update { it.copy(submitting = false, submitted = true) }
                true
            }

            is SubmitResult.Rejected -> {
                _state.update { it.copy(submitting = false, message = result.reason) }
                false
            }
        }
    }

    private fun addToCart(item: MenuItem, selection: OptionSelection) {
        apply(_state.value.cart.add(item, selection))
    }

    private fun apply(change: CartChange) {
        _state.update {
            when (change) {
                is CartChange.Updated -> it.copy(cart = change.cart, message = null)
                is CartChange.Rejected -> it.copy(message = change.reason)
            }
        }
    }
}
