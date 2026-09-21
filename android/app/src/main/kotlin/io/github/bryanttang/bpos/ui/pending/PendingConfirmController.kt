package io.github.bryanttang.bpos.ui.pending

import io.github.bryanttang.bpos.remote.ActionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 待確認畫面上每一列現在按不按得下去。
 *
 * 列表本身不在這裡：那批單是桌位總覽本來就在聽的那一條
 * （[io.github.bryanttang.bpos.ui.tables.TablesController]），這個狀態機只管
 * 「店員按了確認之後發生什麼事」。
 */
data class PendingConfirmUiState(
    /** 已經送出確認、還在等伺服器回應的單。那幾列的按鈕要停用。 */
    val confirming: Set<String> = emptySet(),
    /**
     * 伺服器已經放行的單。
     *
     * 按鈕要一直停用到那張單從列表上消失為止。清掉的話，從「伺服器回成功」到
     * 「監聽收到狀態變成 open」之間那一下子按鈕會亮回來，店員很可能就再按一次——
     * 有 requestId 擋著不會印兩張，但看起來像壞了。
     */
    val confirmed: Set<String> = emptySet(),
    /** 要顯示給店員看的一句話，看過就該收掉（[PendingConfirmController.dismissMessage]）。 */
    val message: String? = null,
)

/**
 * 待確認畫面的狀態機。
 *
 * 不碰 Compose 也不碰 Firebase，所以下面兩條規則都測得到：
 *
 * - **同一張單重試時沿用同一個 `requestId`。** 確認會讓廚房收到單，而「其實成功了
 *   但回應在路上掉了」時店員看到的是失敗、一定會再按一次。沒有同一個鍵的話，
 *   第二次會被伺服器當成另一次確認（見 functions/src/orders/confirmGuestOrderInput.ts）。
 *   鍵是按下去的那一刻產生、一直留到這張單有定論為止，不是每次呼叫產生一個。
 * - **送出中不能再送一次。** 理由同 OrderController：沒有立刻反應時店員就是會再按。
 */
class PendingConfirmController(
    /**
     * 送出確認。收一個函式而不是 [io.github.bryanttang.bpos.remote.StaffFunctions] 本身，
     * 是為了讓這個狀態機的測試不用連 Firebase——它要驗的是「三種結果各自怎麼辦」，
     * 錯誤碼怎麼分類那一層有 CallableErrors 在顧。
     */
    private val sendConfirm: suspend (orderId: String, requestId: String) -> ActionResult,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _state = MutableStateFlow(PendingConfirmUiState())
    val state: StateFlow<PendingConfirmUiState> = _state.asStateFlow()

    /**
     * 每張單的冪等鍵。
     *
     * 放在 state 外面是因為它不是畫面要顯示的東西；用 ConcurrentHashMap 是因為
     * 兩張不同的單可以同時在確認中（店員連按兩列），那是兩個 coroutine 同時動這個 map。
     */
    private val requestIds = ConcurrentHashMap<String, String>()

    /**
     * 確認一張顧客自助單。重複呼叫同一張單是安全的：正在確認中或已經確認過就直接返回。
     */
    suspend fun confirm(orderId: String) {
        val current = _state.value
        if (orderId in current.confirming || orderId in current.confirmed) return

        val requestId = requestIds.computeIfAbsent(orderId) { newRequestId() }
        _state.update { it.copy(confirming = it.confirming + orderId, message = null) }

        val result = try {
            sendConfirm(orderId, requestId)
        } catch (e: Throwable) {
            // 畫面被收掉（CancellationException）或是預期外的例外，這張單都要從
            // 「確認中」拿掉，否則那顆按鈕就永遠按不下去了。requestId 留著，
            // 下次按同一張單還是同一個鍵。
            _state.update { it.copy(confirming = it.confirming - orderId) }
            throw e
        }

        // 一次更新到底，中間不留「既不是確認中、也還沒確認」的空檔——
        // 那個空檔會讓按鈕閃一下亮回來。
        _state.update { state ->
            when (result) {
                is ActionResult.Done -> state.copy(
                    confirming = state.confirming - orderId,
                    confirmed = state.confirmed + orderId,
                )

                // 伺服器沒收到或忙不過來，再按一次是有意義的（見 ActionResult.Retryable）。
                is ActionResult.Retryable -> state.copy(
                    confirming = state.confirming - orderId,
                    message = result.message,
                )

                // 再按幾次都一樣：那張單被別台處理掉了、或狀態已經不能確認。
                // 訊息是伺服器寫給店員看的（confirmGuestOrder.ts 的 notConfirmableMessage），
                // 直接顯示比翻成「操作失敗」有用。
                is ActionResult.Refused -> state.copy(
                    confirming = state.confirming - orderId,
                    message = result.message,
                )
            }
        }

        // 有定論的單不用再留鍵。可重試的留著，那正是它存在的理由。
        if (result !is ActionResult.Retryable) requestIds.remove(orderId)
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
