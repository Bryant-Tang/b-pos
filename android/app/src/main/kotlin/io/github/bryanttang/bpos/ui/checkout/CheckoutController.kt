package io.github.bryanttang.bpos.ui.checkout

import io.github.bryanttang.bpos.order.CloseReceipt
import io.github.bryanttang.bpos.remote.ActionResult
import io.github.bryanttang.bpos.remote.PaymentMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 一張單結完之後畫面要顯示的東西。 */
sealed interface CheckoutResult {

    /**
     * 結好了。[received] 是這次送出去的收款金額，付現以外是 null；
     * [CloseReceipt.replayed] 為 true 時不顯示它，理由見那個欄位的註解。
     */
    data class Closed(val receipt: CloseReceipt, val received: Int?) : CheckoutResult

    /**
     * 伺服器說結好了，但平板讀不懂它回的數字。
     *
     * 不能叫店員重按：單已經搬走了。也不能顯示 0 元找零：那會真的少找錢
     * （見 [CloseReceipt.from]）。能做的只有明講，讓店員去看紙本或後台。
     */
    data object ClosedUnreadable : CheckoutResult
}

/**
 * 結帳這件事現在的狀態。
 *
 * 用 orderId 當鍵而不是只存「目前這一張」，是因為這個狀態機活得比畫面久（見
 * [CheckoutController]）：店員按了結帳、等不到回應、退回去看別桌，這張單的結果
 * 要等到他回來看的時候還在。
 */
data class CheckoutUiState(
    /** 已經送出、還在等伺服器的單。 */
    val closing: Set<String> = emptySet(),
    /** 已經結好、店員還沒按「完成」的單。 */
    val results: Map<String, CheckoutResult> = emptyMap(),
    /** 要顯示給店員看的一句話，看過就該收掉（[CheckoutController.dismissMessage]）。 */
    val message: String? = null,
)

/**
 * 結帳的狀態機。
 *
 * **由 AppServices 建，登入期間都是同一個，不在畫面裡 `remember`。** BposApp 換畫面時
 * 整棵子樹連同 `remember` 的東西一起被丟掉（#43 踩過）。放在畫面裡的話，店員
 * 「按了結帳、網路卡住、退回去、再進來按一次」拿到的是新的 requestId——而那一次如果
 * 其實已經結成了，伺服器只會回「這張單已經結帳了」，店員拿不回找零金額。
 * 同一個鍵重送，伺服器會把當初那份結果原樣回傳（closeOrder.ts 的 replayResult）。
 *
 * 不碰 Compose 也不碰 Firebase，下面幾條規則都測得到：
 *
 * - 同一張單重試沿用同一個 requestId，直到這張單有定論為止。
 * - 送出中不能再送一次。
 * - 找零只用伺服器回的數字；讀不懂就明講，不退回 0。
 */
class CheckoutController(
    /**
     * 送出結帳。收一個函式而不是 [io.github.bryanttang.bpos.remote.StaffFunctions] 本身，
     * 理由同 PendingConfirmController：測試不用連 Firebase。
     */
    private val sendClose: suspend (
        orderId: String,
        method: PaymentMethod,
        received: Int?,
        requestId: String,
    ) -> ActionResult,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _state = MutableStateFlow(CheckoutUiState())
    val state: StateFlow<CheckoutUiState> = _state.asStateFlow()

    /** 每張單的冪等鍵。理由同 PendingConfirmController 的同名欄位。 */
    private val requestIds = ConcurrentHashMap<String, String>()

    /**
     * 結帳。[received] 只有付現才帶，而且呼叫前畫面應該已經用 [parseCashInput] 擋過
     * 收不夠的情況；伺服器會再擋一次。
     *
     * 重複呼叫同一張單是安全的：正在結或已經結好就直接返回。
     */
    suspend fun close(orderId: String, method: PaymentMethod, received: Int?) {
        val current = _state.value
        if (orderId in current.closing || orderId in current.results) return

        val requestId = requestIds.computeIfAbsent(orderId) { newRequestId() }
        _state.update { it.copy(closing = it.closing + orderId, message = null) }

        val sentReceived = received.takeIf { method == PaymentMethod.CASH }
        val result = try {
            sendClose(orderId, method, sentReceived, requestId)
        } catch (e: Throwable) {
            // 畫面被收掉或預期外的例外：這張單要從「結帳中」拿掉，不然按鈕永遠按不下去。
            // requestId 留著，下次按同一張單還是同一個鍵。
            _state.update { it.copy(closing = it.closing - orderId) }
            throw e
        }

        _state.update { state ->
            val cleared = state.copy(closing = state.closing - orderId)
            when (result) {
                is ActionResult.Done -> {
                    val receipt = CloseReceipt.from(result.payload)
                    val outcome = if (receipt == null) {
                        CheckoutResult.ClosedUnreadable
                    } else {
                        CheckoutResult.Closed(receipt, sentReceived)
                    }
                    cleared.copy(results = cleared.results + (orderId to outcome))
                }

                // 沒連上或伺服器忙。這張單可能已經結了也可能還沒，所以鍵留著：
                // 已經結了的話下一次會拿回當時的結果，沒結的話下一次才真的結。
                is ActionResult.Retryable -> cleared.copy(message = result.message)

                // 狀態不對（還沒確認、被併走、已經結過）或收的錢不夠。
                // 伺服器的訊息是寫給店員看的，直接顯示。
                is ActionResult.Refused -> cleared.copy(message = result.message)
            }
        }

        if (result !is ActionResult.Retryable) requestIds.remove(orderId)
    }

    /**
     * 店員看完結果、按了「完成」。
     *
     * 在這之前結果一直留著，理由見 [CheckoutUiState]。按了之後就丟掉：
     * 那張單已經不在 `orders` 了，之後不會再有人從這台平板找它。
     */
    fun finish(orderId: String) = _state.update { it.copy(results = it.results - orderId) }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
