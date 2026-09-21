// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// 原因見 sync/OutboxRepositoryTest.kt 開頭。
package io.github.bryanttang.bpos.ui.order

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.data.local.OrderIntentOutboxDao
import io.github.bryanttang.bpos.data.local.OrderIntentOutboxEntity
import io.github.bryanttang.bpos.menu.MenuFixtures
import io.github.bryanttang.bpos.menu.MenuLoad
import io.github.bryanttang.bpos.order.OrderDraft
import io.github.bryanttang.bpos.order.OrderSubmitter
import io.github.bryanttang.bpos.sync.OrderIntent
import io.github.bryanttang.bpos.sync.OrderIntentSender
import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.sync.OutboxRepository
import io.github.bryanttang.bpos.sync.SendResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 點餐畫面狀態機的測試。
 *
 * 跑在真的 Room 上（同 OrderSubmitterTest）：最值得驗的「連按兩次送出只會有一張單」
 * 是資料庫主鍵在擋的，用假的佇列等於把要驗的東西自己實作一遍。
 * 菜單那一側就不用真的 Room 了，這裡只在乎「拿到／沒拿到各自怎麼辦」。
 */
@RunWith(RobolectricTestRunner::class)
class OrderControllerTest {

    private lateinit var db: BposDatabase
    private lateinit var submitter: OrderSubmitter

    private val storeId = "store_demo"
    private val staffUid = "staff_uid_0001"
    private val tableId = "table_a1"

    /** 這個測試只到「進佇列」為止，之後怎麼送是佇列自己的事。 */
    private val neverSends = object : OrderIntentSender {
        override suspend fun send(intent: OrderIntent): SendResult =
            throw AssertionError("這個測試不該送到伺服器")
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BposDatabase::class.java,
        ).build()
        submitter = OrderSubmitter(
            OutboxRepository(dao = db.orderIntentOutboxDao(), sender = neverSends),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun controller(
        menu: MenuLoad = MenuLoad.Ready(MenuFixtures.menu, fetchedAtMillis = 0, fromCache = false),
        draft: OrderDraft = OrderDraft.new(),
    ) = OrderController(
        fetchMenu = { menu },
        submitter = submitter,
        tableId = tableId,
        orderType = OrderType.DINE_IN,
        draft = draft,
    )

    @Test
    fun `the first category is selected once the menu arrives`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)

        val state = controller.state.value
        assertFalse(state.loading)
        assertEquals(MenuFixtures.CATEGORY_NOODLE, state.selectedCategoryId)
    }

    @Test
    fun `no menu at all is not an empty menu`() = runTest {
        // 空菜單看起來像「這間店今天什麼都沒有」，店員會以為老闆把品項全下架了。
        val controller = controller(menu = MenuLoad.Unavailable)
        controller.loadMenu(storeId)

        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.menu)
    }

    @Test
    fun `a cached menu is flagged as cached`() = runTest {
        val controller = controller(
            menu = MenuLoad.Ready(MenuFixtures.menu, fetchedAtMillis = 0, fromCache = true),
        )
        controller.loadMenu(storeId)

        assertTrue(controller.state.value.menuFromCache)
    }

    @Test
    fun `an item with no options goes straight into the cart`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)

        controller.onItemClick(MenuFixtures.bubbleTea)

        assertNull("不該拉起 sheet", controller.state.value.pendingOptions)
        assertEquals(1, controller.state.value.cart.totalQty)
    }

    @Test
    fun `an item with options opens the sheet instead`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)

        controller.onItemClick(MenuFixtures.beefNoodle)

        assertEquals(MenuFixtures.beefNoodle, controller.state.value.pendingOptions?.item)
        assertTrue("還沒按確定就不該進車", controller.state.value.cart.isEmpty)
    }

    @Test
    fun `confirming the sheet adds the item with its options`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)
        controller.onItemClick(MenuFixtures.beefNoodle)

        val mild = mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD))
        controller.onSelectionChange(mild)
        controller.onOptionsConfirm()

        val state = controller.state.value
        assertNull(state.pendingOptions)
        assertEquals(1, state.cart.lines.size)
        assertEquals(mild, state.cart.lines.single().selection)
    }

    @Test
    fun `dismissing the sheet adds nothing`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)
        controller.onItemClick(MenuFixtures.beefNoodle)

        controller.onOptionsDismiss()

        assertNull(controller.state.value.pendingOptions)
        assertTrue(controller.state.value.cart.isEmpty)
    }

    @Test
    fun `setting a quantity to zero removes the line`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)
        controller.onItemClick(MenuFixtures.bubbleTea)

        controller.onQtyChange(0, 0)

        assertTrue(controller.state.value.cart.isEmpty)
    }

    @Test
    fun `an empty cart is refused with a reason the staff can read`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)

        assertFalse(controller.submit(staffUid))

        assertNotNull(controller.state.value.message)
        assertFalse(controller.state.value.submitted)
    }

    @Test
    fun `submitting queues exactly one intent`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)
        controller.onItemClick(MenuFixtures.bubbleTea)

        assertTrue(controller.submit(staffUid))

        assertTrue(controller.state.value.submitted)
        assertEquals(1, db.orderIntentOutboxDao().observePendingCount().first())
    }

    @Test
    fun `pressing submit twice still queues only one intent`() = runTest {
        // 店裡 Wi-Fi 慢的時候按下去沒有反應，店員就是會再按一次。
        val controller = controller()
        controller.loadMenu(storeId)
        controller.onItemClick(MenuFixtures.bubbleTea)

        assertTrue(controller.submit(staffUid))
        assertFalse("這張單已經送出去了", controller.submit(staffUid))

        assertEquals(1, db.orderIntentOutboxDao().observePendingCount().first())
    }

    @Test
    fun `a new controller is a new order`() = runTest {
        // 退回總覽再進來是新的一張單，這時候才換一組新的 intentId。
        val first = controller()
        first.loadMenu(storeId)
        first.onItemClick(MenuFixtures.bubbleTea)
        assertTrue(first.submit(staffUid))

        val second = controller()
        second.loadMenu(storeId)
        second.onItemClick(MenuFixtures.bubbleTea)
        assertTrue(second.submit(staffUid))

        assertEquals(2, db.orderIntentOutboxDao().observePendingCount().first())
    }

    @Test
    fun `the same draft submitted twice is still one order`() = runTest {
        // 同一組 id 就是同一張單，即使是兩個控制器送的（例如畫面被重建）。
        val draft = OrderDraft.new()

        val first = controller(draft = draft)
        first.loadMenu(storeId)
        first.onItemClick(MenuFixtures.bubbleTea)
        assertTrue(first.submit(staffUid))

        val second = controller(draft = draft)
        second.loadMenu(storeId)
        second.onItemClick(MenuFixtures.bubbleTea)
        // 佇列看到重複的主鍵就忽略，對店員而言跟成功一樣。
        assertTrue(second.submit(staffUid))

        assertEquals(1, db.orderIntentOutboxDao().observePendingCount().first())
    }

    @Test
    fun `a storage failure releases the button instead of locking the order up`() = runTest {
        // 寫本機資料庫失敗（空間滿了、資料庫檔壞了）不能讓這張單卡死：
        // submitting 如果留在 true，之後按幾次送出都被開頭那個 return 擋掉，
        // 店員看到的是「按了沒反應」，只能整張重點一次。
        val controller = OrderController(
            fetchMenu = { MenuLoad.Ready(MenuFixtures.menu, fetchedAtMillis = 0, fromCache = false) },
            submitter = OrderSubmitter(
                OutboxRepository(
                    dao = WriteAlwaysFailsDao(db.orderIntentOutboxDao()),
                    sender = neverSends,
                ),
            ),
            tableId = tableId,
            orderType = OrderType.DINE_IN,
        )
        controller.loadMenu(storeId)
        controller.onItemClick(MenuFixtures.bubbleTea)

        assertFalse(controller.submit(staffUid))

        val state = controller.state.value
        assertFalse("按鈕要放開，讓店員再試一次", state.submitting)
        assertFalse("這張單沒送出去，不能當成送出了", state.submitted)
        assertNotNull("要告訴店員發生什麼事", state.message)
    }

    @Test
    fun `a message is cleared once the staff has seen it`() = runTest {
        val controller = controller()
        controller.loadMenu(storeId)
        controller.submit(staffUid)
        assertNotNull(controller.state.value.message)

        controller.dismissMessage()

        assertNull(controller.state.value.message)
    }
}

/**
 * 寫得進去的都寫不進去，其餘照舊。
 *
 * 用委派而不是整個實作一遍：這個測試在乎的只有 insert 失敗那一條路，
 * 其他方法照樣走真的 Room，將來 DAO 加方法也不用回來補。
 */
private class WriteAlwaysFailsDao(
    real: OrderIntentOutboxDao,
) : OrderIntentOutboxDao by real {
    override suspend fun insertIfAbsent(entry: OrderIntentOutboxEntity): Long =
        throw IllegalStateException("資料庫寫不進去")
}
