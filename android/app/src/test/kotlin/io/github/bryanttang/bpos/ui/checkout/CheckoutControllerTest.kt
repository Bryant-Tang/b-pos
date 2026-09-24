package io.github.bryanttang.bpos.ui.checkout

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import io.github.bryanttang.bpos.order.CloseReceipt
import io.github.bryanttang.bpos.remote.ActionResult
import io.github.bryanttang.bpos.remote.PaymentMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutControllerTest {

    /** 假的伺服器：記下每次收到什麼，[gate] 沒完成之前一直卡著。 */
    private class FakeClose(
        private val results: MutableList<ActionResult>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) {
        data class Call(val orderId: String, val method: PaymentMethod, val received: Int?, val requestId: String)

        val calls = mutableListOf<Call>()

        suspend fun send(orderId: String, method: PaymentMethod, received: Int?, requestId: String): ActionResult {
            calls += Call(orderId, method, received, requestId)
            gate?.await()
            return if (results.size > 1) results.removeAt(0) else results.first()
        }
    }

    private fun closed(total: Int = 420, change: Int = 80, outcome: String = "closed") = ActionResult.Done(
        mapOf("outcome" to outcome, "lookupCode" to "0000", "total" to total.toLong(), "change" to change.toLong()),
    )

    private fun controller(fake: FakeClose, ids: List<String> = listOf("req_1", "req_2", "req_3")): CheckoutController {
        val remaining = ids.toMutableList()
        return CheckoutController(sendClose = fake::send, newRequestId = { remaining.removeAt(0) })
    }

    @Test
    fun `a cash close keeps the server change and what was received`() = runTest {
        val fake = FakeClose(mutableListOf(closed(total = 420, change = 80)))
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CASH, received = 500)

        val result = controller.state.value.results["order_1"] as CheckoutResult.Closed
        assertEquals(CloseReceipt(total = 420, change = 80, lookupCode = "0000", replayed = false), result.receipt)
        assertEquals(500, result.received)
        assertEquals(500, fake.calls.single().received)
    }

    /** 伺服器的 schema 對刷卡與行動支付帶 received 會直接拒絕（closeOrderInput.ts 的 refine）。 */
    @Test
    fun `a non cash close never sends a received amount`() = runTest {
        val fake = FakeClose(mutableListOf(closed(change = 0)))
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CARD, received = 500)

        assertNull(fake.calls.single().received)
        assertNull((controller.state.value.results["order_1"] as CheckoutResult.Closed).received)
    }

    @Test
    fun `the order shows as closing while the server has not answered`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeClose(mutableListOf(closed()), gate)
        val controller = controller(fake)

        val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
            controller.close("order_1", PaymentMethod.CASH, 500)
        }
        assertEquals(setOf("order_1"), controller.state.value.closing)

        gate.complete(Unit)
        inFlight.await()
        assertTrue(controller.state.value.closing.isEmpty())
    }

    @Test
    fun `pressing again while in flight does not send a second call`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeClose(mutableListOf(closed()), gate)
        val controller = controller(fake)

        val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
            controller.close("order_1", PaymentMethod.CASH, 500)
        }
        controller.close("order_1", PaymentMethod.CASH, 500)
        assertEquals(1, fake.calls.size)

        gate.complete(Unit)
        inFlight.await()
    }

    /** 結好之後、店員按「完成」之前，再按一次不能又送一次。 */
    @Test
    fun `a closed order cannot be closed again until finished`() = runTest {
        val fake = FakeClose(mutableListOf(closed()))
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CASH, 500)
        controller.close("order_1", PaymentMethod.CASH, 500)

        assertEquals(1, fake.calls.size)
    }

    /**
     * 整個狀態機存在的理由：網路斷在結帳途中，重按要沿用同一個鍵，
     * 伺服器才會把當初的找零與查詢碼還回來，而不是回「這張單已經結帳了」。
     */
    @Test
    fun `retrying after a network failure reuses the same request id`() = runTest {
        val fake = FakeClose(mutableListOf(ActionResult.Retryable("連不到伺服器"), closed(outcome = "already_applied")))
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CASH, 500)
        assertEquals("連不到伺服器", controller.state.value.message)
        assertTrue(controller.state.value.results.isEmpty())

        controller.close("order_1", PaymentMethod.CASH, 500)

        assertEquals(listOf("req_1", "req_1"), fake.calls.map { it.requestId })
        val result = controller.state.value.results["order_1"] as CheckoutResult.Closed
        assertTrue(result.receipt.replayed)
    }

    /** 被拒絕（收不夠、狀態不對）代表伺服器沒有動那張單，改好再送是新的一次。 */
    @Test
    fun `a refused close shows the server message and starts fresh next time`() = runTest {
        val fake = FakeClose(
            mutableListOf(ActionResult.Refused("收到的金額不足，這張單是 420 元"), closed()),
        )
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CASH, 400)
        assertEquals("收到的金額不足，這張單是 420 元", controller.state.value.message)
        assertTrue(controller.state.value.results.isEmpty())

        controller.close("order_1", PaymentMethod.CASH, 500)

        assertEquals(listOf("req_1", "req_2"), fake.calls.map { it.requestId })
    }

    @Test
    fun `two different orders each get their own request id`() = runTest {
        val fake = FakeClose(mutableListOf(closed()))
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CASH, 500)
        controller.close("order_2", PaymentMethod.MOBILE, null)

        assertEquals(listOf("req_1", "req_2"), fake.calls.map { it.requestId })
    }

    /** 單已經搬走了，叫店員重按沒有用；顯示 0 元找零會真的少找錢。 */
    @Test
    fun `a success the tablet cannot read is reported as such`() = runTest {
        val fake = FakeClose(mutableListOf(ActionResult.Done(mapOf("outcome" to "closed"))))
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CASH, 500)

        assertEquals(CheckoutResult.ClosedUnreadable, controller.state.value.results["order_1"])
    }

    @Test
    fun `finishing drops the result`() = runTest {
        val fake = FakeClose(mutableListOf(closed()))
        val controller = controller(fake)

        controller.close("order_1", PaymentMethod.CASH, 500)
        controller.finish("order_1")

        assertTrue(controller.state.value.results.isEmpty())
    }

    @Test
    fun `an unexpected failure clears the in flight mark and propagates`() {
        val controller = CheckoutController(
            sendClose = { _, _, _, _ -> throw IllegalStateException("送不出去") },
            newRequestId = { "req_1" },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { controller.close("order_1", PaymentMethod.CASH, 500) }
        }

        assertTrue(controller.state.value.closing.isEmpty())
        assertTrue(controller.state.value.results.isEmpty())
    }
}
