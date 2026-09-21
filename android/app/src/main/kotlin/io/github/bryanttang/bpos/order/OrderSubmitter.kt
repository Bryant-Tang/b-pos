package io.github.bryanttang.bpos.order

import io.github.bryanttang.bpos.sync.OrderIntent
import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.sync.OutboxRepository
import java.time.Clock
import java.util.UUID

/**
 * 一張還沒送出的單的身分證。
 *
 * [intentId] 與 [orderId] 在**店員開始點這張單的時候就產生**，而不是按下送出時才產生
 * （SPEC 第六節：orderId 由客戶端產生 UUID，不要等伺服器回傳）。
 *
 * 這件事是雙送保護的關鍵：店員手滑連按兩次送出，兩次帶的是同一個 [intentId]，
 * 佇列看到重複的主鍵就直接忽略第二次，伺服器上也只會有一份。如果改成每次按下去
 * 才產生新的 id，連按兩次就是實實在在的兩張單，而且是斷網時最容易發生
 * ——畫面沒有立刻反應，店員自然會再按一次。
 */
data class OrderDraft(
    val intentId: String,
    val orderId: String,
) {
    companion object {
        /** 開一張新單。送出成功之後才換下一張，不要在同一張單的中途換。 */
        fun new(ids: () -> String = { UUID.randomUUID().toString() }): OrderDraft =
            OrderDraft(intentId = ids(), orderId = ids())
    }
}

/** 送出的結果。 */
sealed interface SubmitResult {
    /** 已經進佇列，店員可以繼續做下一件事了。真正送到伺服器是後面的事。 */
    data object Queued : SubmitResult

    /**
     * 這張單先前已經進過佇列了，這次什麼都沒有改變。
     *
     * 連按兩次送出會走到這裡，**對店員而言跟成功一樣**，不要顯示成錯誤。
     */
    data object AlreadyQueued : SubmitResult

    /** 送不出去，帶著要顯示給店員看的原因。 */
    data class Rejected(val reason: String) : SubmitResult
}

/**
 * 把購物車變成一筆下單意圖丟進離線佇列。
 *
 * 這是 [OutboxRepository] 在 App 裡的第一個真實呼叫端：店員按下送出 → 寫進本地佇列
 * → 畫面立刻可以繼續，送到伺服器由 WorkManager 在背景處理（SPEC 第六節〈離線策略〉）。
 * 這條路上**沒有任何一步需要網路**，斷網時店員照樣點得了餐。
 */
class OrderSubmitter(
    private val outbox: OutboxRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    suspend fun submit(
        draft: OrderDraft,
        cart: Cart,
        orderType: OrderType,
        tableId: String?,
        createdBy: String,
    ): SubmitResult {
        if (cart.isEmpty) return SubmitResult.Rejected("購物車是空的")

        // OrderIntent 的 init 會擋「內用沒帶桌號」「外帶帶了桌號」這類組合。
        // 那些是程式上不該送出的狀態，但這裡接住轉成訊息而不是讓它炸掉，
        // 因為畫面上的桌號是可以被別人改掉的（例如另一台平板剛把這桌結了）。
        val intent = try {
            OrderIntent(
                intentId = draft.intentId,
                orderId = draft.orderId,
                orderType = orderType,
                tableId = tableId,
                lines = cart.toIntentLines(),
                createdBy = createdBy,
                clientCreatedAt = clock.instant(),
            )
        } catch (e: IllegalArgumentException) {
            return SubmitResult.Rejected(e.message ?: "這張單的內容不完整")
        }

        return if (outbox.enqueue(intent)) SubmitResult.Queued else SubmitResult.AlreadyQueued
    }
}
