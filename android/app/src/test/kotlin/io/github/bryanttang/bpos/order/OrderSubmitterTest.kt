// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// 原因見 sync/OutboxRepositoryTest.kt 開頭。
package io.github.bryanttang.bpos.order

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.data.local.OutboxState
import io.github.bryanttang.bpos.menu.MenuFixtures
import io.github.bryanttang.bpos.sync.OrderIntent
import io.github.bryanttang.bpos.sync.OrderIntentSender
import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.sync.OutboxRepository
import io.github.bryanttang.bpos.sync.SendResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 送出流程的測試：購物車 → 下單意圖 → 真的進到離線佇列。
 *
 * 跑在真的 Room 上（Robolectric 提供 Android 環境），因為這裡最值得驗的就是
 * 「重複送出會不會變成兩張單」，而那件事是資料庫的主鍵在擋的，用假的 DAO 驗等於沒驗。
 * 送出端固定不會被呼叫到——這個測試只到「進佇列」為止，之後怎麼送是佇列自己的事。
 */
@RunWith(RobolectricTestRunner::class)
class OrderSubmitterTest {

    private lateinit var db: BposDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var submitter: OrderSubmitter

    private val t0 = Instant.parse("2026-01-15T03:20:00Z")
    private val staffUid = "staff_uid_0001"
    private val tableId = "table_a1"

    private val mild = mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BposDatabase::class.java,
        ).allowMainThreadQueries().build()
        outbox = OutboxRepository(db.orderIntentOutboxDao(), NeverCalledSender(), FixedClock(t0))
        submitter = OrderSubmitter(outbox, FixedClock(t0))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun cartWithBeefNoodle(qty: Int = 1): Cart =
        (Cart().add(MenuFixtures.beefNoodle, mild, qty) as CartChange.Updated).cart

    private suspend fun submit(
        draft: OrderDraft,
        cart: Cart = cartWithBeefNoodle(),
        orderType: OrderType = OrderType.DINE_IN,
        table: String? = tableId,
    ): SubmitResult = submitter.submit(draft, cart, orderType, table, staffUid)

    // 一般情況：按下送出，單進到佇列，狀態是等待送出。
    @Test
    fun `a submitted order lands in the outbox as pending`() = runTest {
        val draft = OrderDraft("intent_0001", "order_0001")

        assertEquals(SubmitResult.Queued, submit(draft))
        assertEquals(OutboxState.PENDING, outbox.stateOf("intent_0001"))
    }

    // 這是這支類別存在的理由：店員手滑連按兩次送出，只會有一張單。
    // 斷網時畫面沒有立刻反應，店員本來就會再按一次，所以這不是罕見情況。
    @Test
    fun `pressing submit twice does not create a second order`() = runTest {
        val draft = OrderDraft("intent_0001", "order_0001")

        assertEquals(SubmitResult.Queued, submit(draft))
        assertEquals(SubmitResult.AlreadyQueued, submit(draft))

        assertEquals(1, db.orderIntentOutboxDao().dueForSending(Long.MAX_VALUE, 100).size)
    }

    // 換一張新單（新的 draft）就是一張新的單，不會被上一張擋掉。
    @Test
    fun `a new draft is a new order`() = runTest {
        assertEquals(SubmitResult.Queued, submit(OrderDraft("intent_0001", "order_0001")))
        assertEquals(SubmitResult.Queued, submit(OrderDraft("intent_0002", "order_0002")))

        assertEquals(2, db.orderIntentOutboxDao().dueForSending(Long.MAX_VALUE, 100).size)
    }

    // 空車不能送。
    @Test
    fun `an empty cart is rejected`() = runTest {
        val result = submit(OrderDraft("intent_0001", "order_0001"), cart = Cart())

        assertTrue((result as SubmitResult.Rejected).reason.contains("空"))
    }

    // 內用沒帶桌號要在這裡就擋下來，而不是幾秒後從雲端回來一則看不懂的拒絕。
    @Test
    fun `a dine in order without a table is rejected with a readable reason`() = runTest {
        val result = submit(OrderDraft("intent_0001", "order_0001"), table = null)

        assertTrue((result as SubmitResult.Rejected).reason.contains("桌號"))
    }

    // 外帶不可以帶桌號，綁桌要走型態轉換。
    @Test
    fun `a takeout order carrying a table is rejected`() = runTest {
        val result = submit(
            OrderDraft("intent_0001", "order_0001"),
            orderType = OrderType.TAKEOUT,
            table = tableId,
        )

        assertTrue((result as SubmitResult.Rejected).reason.contains("外帶"))
    }

    // 被擋下來的單不該在佇列裡留下任何東西。
    @Test
    fun `a rejected submit leaves the outbox untouched`() = runTest {
        submit(OrderDraft("intent_0001", "order_0001"), table = null)

        assertEquals(null, outbox.stateOf("intent_0001"))
    }

    // 每張單的 id 要不一樣，不然第二張會被當成第一張的重送而整筆消失。
    @Test
    fun `each new draft gets its own ids`() {
        val first = OrderDraft.new()
        val second = OrderDraft.new()

        assertTrue(first.intentId != second.intentId)
        assertTrue(first.intentId != first.orderId)
        assertTrue(first.orderId != second.orderId)
    }

    private class NeverCalledSender : OrderIntentSender {
        override suspend fun send(intent: OrderIntent): SendResult =
            throw AssertionError("送出流程只到進佇列為止，不該在這裡就打伺服器")
    }

    private class FixedClock(private val now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }
}
