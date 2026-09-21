package io.github.bryanttang.bpos.ui.pending

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import io.github.bryanttang.bpos.remote.ActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingConfirmControllerTest {

    /**
     * 假的伺服器。記下每次收到的 requestId，[gate] 沒完成之前一直卡著，
     * 用來重現「店員按了確認、伺服器還沒回」那個瞬間。
     */
    private class FakeConfirm(
        private val results: MutableList<ActionResult>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) {
        val requestIds = mutableListOf<String>()
        val orderIds = mutableListOf<String>()

        suspend fun send(orderId: String, requestId: String): ActionResult {
            orderIds += orderId
            requestIds += requestId
            gate?.await()
            return if (results.size > 1) results.removeAt(0) else results.first()
        }
    }

    private fun controller(
        fake: FakeConfirm,
        ids: List<String> = listOf("req_1", "req_2", "req_3"),
    ): PendingConfirmController {
        val remaining = ids.toMutableList()
        return PendingConfirmController(
            sendConfirm = fake::send,
            newRequestId = { remaining.removeAt(0) },
        )
    }

    @Test
    fun `confirm marks the order confirmed when the server accepts it`() = runTest {
        val fake = FakeConfirm(mutableListOf(ActionResult.Done(emptyMap())))
        val controller = controller(fake)

        controller.confirm("order_1")

        assertEquals(setOf("order_1"), controller.state.value.confirmed)
        assertTrue(controller.state.value.confirming.isEmpty())
        assertNull(controller.state.value.message)
        assertEquals(listOf("order_1"), fake.orderIds)
    }

    @Test
    fun `the order shows as confirming while the server has not answered`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeConfirm(mutableListOf(ActionResult.Done(emptyMap())), gate)
        val controller = controller(fake)

        val inFlight = async(start = CoroutineStart.UNDISPATCHED) { controller.confirm("order_1") }
        assertEquals(setOf("order_1"), controller.state.value.confirming)
        assertTrue(controller.state.value.confirmed.isEmpty())

        gate.complete(Unit)
        inFlight.await()
        assertTrue(controller.state.value.confirming.isEmpty())
        assertEquals(setOf("order_1"), controller.state.value.confirmed)
    }

    /** 沒有立刻反應時店員就是會再按一次，而確認是會讓廚房收到單的動作。 */
    @Test
    fun `pressing again while in flight does not send a second call`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeConfirm(mutableListOf(ActionResult.Done(emptyMap())), gate)
        val controller = controller(fake)

        val inFlight = async(start = CoroutineStart.UNDISPATCHED) { controller.confirm("order_1") }
        controller.confirm("order_1")
        assertEquals(1, fake.orderIds.size)

        gate.complete(Unit)
        inFlight.await()
    }

    /** 放行之後那張單下一秒就會從列表消失，按鈕不能在那個空檔亮回來。 */
    @Test
    fun `an already confirmed order cannot be confirmed again`() = runTest {
        val fake = FakeConfirm(mutableListOf(ActionResult.Done(emptyMap())))
        val controller = controller(fake)

        controller.confirm("order_1")
        controller.confirm("order_1")

        assertEquals(1, fake.orderIds.size)
    }

    @Test
    fun `a retryable failure shows the message and leaves the order pressable`() = runTest {
        val fake = FakeConfirm(mutableListOf(ActionResult.Retryable("連不到伺服器")))
        val controller = controller(fake)

        controller.confirm("order_1")

        assertEquals("連不到伺服器", controller.state.value.message)
        assertTrue(controller.state.value.confirming.isEmpty())
        assertTrue(controller.state.value.confirmed.isEmpty())
    }

    /**
     * 整個狀態機存在的理由：重試沿用同一個 requestId，伺服器才擋得掉第二張廚房單
     * （functions/src/orders/confirmGuestOrderInput.ts）。
     */
    @Test
    fun `retrying the same order reuses the same request id`() = runTest {
        val fake = FakeConfirm(
            mutableListOf(ActionResult.Retryable("連不到伺服器"), ActionResult.Done(emptyMap())),
        )
        val controller = controller(fake)

        controller.confirm("order_1")
        controller.confirm("order_1")

        assertEquals(listOf("req_1", "req_1"), fake.requestIds)
        assertEquals(setOf("order_1"), controller.state.value.confirmed)
    }

    @Test
    fun `two different orders each get their own request id`() = runTest {
        val fake = FakeConfirm(mutableListOf(ActionResult.Done(emptyMap())))
        val controller = controller(fake)

        controller.confirm("order_1")
        controller.confirm("order_2")

        assertEquals(listOf("req_1", "req_2"), fake.requestIds)
        assertEquals(setOf("order_1", "order_2"), controller.state.value.confirmed)
    }

    /** 伺服器寫給店員看的訊息要原樣顯示（例如「這張單已經結帳了，不用再確認」）。 */
    @Test
    fun `a refused action shows the server message and does not mark it confirmed`() = runTest {
        val fake = FakeConfirm(mutableListOf(ActionResult.Refused("這張單已經結帳了，不用再確認")))
        val controller = controller(fake)

        controller.confirm("order_1")

        assertEquals("這張單已經結帳了，不用再確認", controller.state.value.message)
        assertTrue(controller.state.value.confirmed.isEmpty())
        assertTrue(controller.state.value.confirming.isEmpty())
    }

    /**
     * 例外不能把那張單留在「確認中」——留著的話那顆按鈕就永遠按不下去了
     * （OrderController 的送出鍵踩過同一個坑）。
     */
    @Test
    fun `an unexpected failure clears the in flight mark and propagates`() {
        val controller = PendingConfirmController(
            sendConfirm = { _, _ -> throw IllegalStateException("送不出去") },
            newRequestId = { "req_1" },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { controller.confirm("order_1") }
        }

        assertTrue(controller.state.value.confirming.isEmpty())
        assertFalse("order_1" in controller.state.value.confirmed)
    }

    @Test
    fun `dismissing the message clears it`() = runTest {
        val fake = FakeConfirm(mutableListOf(ActionResult.Retryable("連不到伺服器")))
        val controller = controller(fake)

        controller.confirm("order_1")
        controller.dismissMessage()

        assertNull(controller.state.value.message)
    }

    /** 下一個動作一開始就把上一則訊息收掉，不然店員會對著舊訊息判斷新結果。 */
    @Test
    fun `starting another confirm clears the previous message`() = runTest {
        val fake = FakeConfirm(
            mutableListOf(ActionResult.Retryable("連不到伺服器"), ActionResult.Done(emptyMap())),
        )
        val controller = controller(fake)

        controller.confirm("order_1")
        assertEquals("連不到伺服器", controller.state.value.message)

        controller.confirm("order_2")

        assertNull(controller.state.value.message)
    }
}
