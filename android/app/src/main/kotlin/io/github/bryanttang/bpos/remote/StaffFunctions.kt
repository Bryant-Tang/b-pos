package io.github.bryanttang.bpos.remote

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * 平板呼叫店員端 Cloud Function 的唯一入口。
 *
 * 店員的所有寫入都走 Function，不直接寫 Firestore（CLAUDE.md 第二節、firestore.rules
 * 對平板只開讀取）。這一層做的事只有三件：帶上冪等鍵、等結果、把例外翻成
 * [ActionResult]，讓畫面不用認得 Firebase 的錯誤碼。
 *
 * **地區要寫對。** 函式部署在 `asia-east1`（functions/src/index.ts 的 REGION），
 * 用預設的 `us-central1` 取實例會得到 404——而那個 404 長得像「函式不存在」，
 * 很容易被誤判成還沒部署。
 */
class StaffFunctions(
    /** 延後取實例，理由同其他 Firebase 類別：Firebase 還沒設定好時 getInstance() 會丟例外。 */
    private val functionsProvider: () -> FirebaseFunctions = { FirebaseFunctions.getInstance(REGION) },
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * 確認一張顧客自助單（`pending_confirm` → `open`）。
     *
     * [requestId] 由呼叫端保管並在重試時沿用同一個：確認會讓廚房收到單，
     * 「其實成功了但回應掉了」的情況下店員一定會再按一次，而沒有同一個鍵的話
     * 廚房會收到兩張（見 confirmGuestOrderInput.ts 的註解）。
     */
    suspend fun confirmGuestOrder(orderId: String, requestId: String = newRequestId()): ActionResult =
        call("confirmGuestOrder", mapOf("orderId" to orderId, "requestId" to requestId))

    /**
     * 結帳（`open` → 搬進 `orders_archive`）。
     *
     * **沒有任何金額參數，只有 [received]。** 總額由伺服器從單上鎖住（CLAUDE.md 第二節第一條），
     * [received] 不是價格，是「客人拿了多少現金出來」——只有櫃檯知道，伺服器算不出來。
     * 找零是伺服器拿它減掉總額算的，平板不算（見 closeOrderInput.ts）。
     * 付現以外的方式不帶 [received]：伺服器的 schema 會直接拒絕。
     *
     * [requestId] 要在重試時沿用：結帳成功之後這張單就不在 `orders` 了，換一個鍵重送
     * 只會得到「這張單已經結帳了」，店員拿不回找零金額與查詢碼；同一個鍵重送則會拿回
     * 當初那份結果（closeOrder.ts 的 replayResult）。
     */
    suspend fun closeOrder(
        orderId: String,
        method: PaymentMethod,
        received: Int?,
        requestId: String = newRequestId(),
    ): ActionResult {
        val payment = buildMap<String, Any?> {
            put("method", method.wireName)
            if (method == PaymentMethod.CASH && received != null) put("received", received)
        }
        return call(
            "closeOrder",
            mapOf("orderId" to orderId, "payment" to payment, "requestId" to requestId),
        )
    }

    private suspend fun call(name: String, data: Map<String, Any?>): ActionResult = try {
        val result = functionsProvider().getHttpsCallable(name).call(data).await()
        @Suppress("UNCHECKED_CAST")
        ActionResult.Done((result.data as? Map<String, Any?>).orEmpty())
    } catch (e: FirebaseFunctionsException) {
        actionResultFor(e.code, e.message)
    } catch (e: CancellationException) {
        // 畫面被收掉才會走到這裡，不是伺服器的錯，也不該變成給店員看的訊息。
        throw e
    } catch (e: Exception) {
        // 送不出去（沒網路、TLS、DNS）。請求沒到伺服器，重送不會有第二次副作用。
        networkFailureResult()
    }

    private companion object {
        const val REGION = "asia-east1"
    }
}
