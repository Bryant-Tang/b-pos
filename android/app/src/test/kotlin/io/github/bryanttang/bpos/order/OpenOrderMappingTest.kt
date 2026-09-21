package io.github.bryanttang.bpos.order

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 *
 * 資料全部是虛構的（CLAUDE.md 第一條）：品項用「牛肉麵」「珍珠奶茶」，桌名用「窗邊」。
 */

import com.google.firebase.Timestamp
import io.github.bryanttang.bpos.sync.OrderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date

class OpenOrderMappingTest {

    private val createdAt = Instant.parse("2026-09-20T11:00:00Z")
    private val printedAt = Instant.parse("2026-09-20T11:05:00Z")
    private val voidedAt = Instant.parse("2026-09-20T11:20:00Z")

    private fun ts(instant: Instant) = Timestamp(Date(instant.toEpochMilli()))

    private fun line(vararg over: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "lineId" to "line_1",
        "itemId" to "item_beef_noodle",
        "name" to "牛肉麵",
        "qty" to 2L,
        "options" to listOf(mapOf("groupId" to "grp_spicy", "optionId" to "opt_mild", "name" to "小辣", "priceDelta" to 0L)),
        "subtotal" to 360L,
        "printedAt" to null,
        "voidedAt" to null,
        "voidReason" to null,
    ) + over

    private fun doc(vararg over: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "orderType" to "dine_in",
        "status" to "open",
        "source" to "staff",
        "tableLabels" to listOf("窗邊"),
        "pickupCode" to null,
        "lines" to listOf(line()),
        "subtotal" to 360L,
        "serviceCharge" to 0L,
        "discount" to 0L,
        "total" to 360L,
        "createdAt" to ts(createdAt),
        "updatedAt" to ts(createdAt),
    ) + over

    @Test
    fun `a normal order maps across`() {
        val order = toOpenOrder("order_1", doc())

        assertEquals("order_1", order.orderId)
        assertEquals(OrderType.DINE_IN, order.orderType)
        assertEquals(OrderStatus.OPEN, order.status)
        assertEquals(OrderSource.STAFF, order.source)
        assertEquals(listOf("窗邊"), order.tableLabels)
        assertEquals(360, order.total)
        assertEquals(createdAt, order.createdAt)

        val mapped = order.lines.single()
        assertEquals("line_1", mapped.lineId)
        assertEquals("牛肉麵", mapped.name)
        assertEquals(2, mapped.qty)
        assertEquals(listOf("小辣"), mapped.options)
        assertEquals(360, mapped.subtotal)
    }

    @Test
    fun `a takeout order keeps its pickup code`() {
        val order = toOpenOrder(
            "order_2",
            doc("orderType" to "takeout", "tableLabels" to emptyList<String>(), "pickupCode" to "0417"),
        )

        assertEquals(OrderType.TAKEOUT, order.orderType)
        assertEquals("0417", order.pickupCode)
        assertTrue(order.tableLabels.isEmpty())
    }

    @Test
    fun `printed and voided times come through`() {
        val order = toOpenOrder(
            "order_3",
            doc("lines" to listOf(line("printedAt" to ts(printedAt), "voidedAt" to ts(voidedAt), "voidReason" to "上錯桌"))),
        )

        val mapped = order.lines.single()
        assertEquals(printedAt, mapped.printedAt)
        assertEquals(voidedAt, mapped.voidedAt)
        assertEquals("上錯桌", mapped.voidReason)
        assertTrue(mapped.isPrinted)
        assertTrue(mapped.isVoided)
    }

    @Test
    fun `voided lines are separated from the ones still on the bill`() {
        val order = toOpenOrder(
            "order_4",
            doc(
                "lines" to listOf(
                    line(),
                    line("lineId" to "line_2", "name" to "珍珠奶茶", "voidedAt" to ts(voidedAt)),
                ),
            ),
        )

        assertEquals(listOf("牛肉麵"), order.activeLines.map { it.name })
        assertEquals(listOf("珍珠奶茶"), order.voidedLines.map { it.name })
        // 作廢的行仍留在 lines 裡供查核，不是被丟掉。
        assertEquals(2, order.lines.size)
    }

    /**
     * 平板是靠 App Distribution 更新的，店裡那台可能落後伺服器好幾版。
     * 猜成 open 的話，一張已經結完帳的單會顯示成還在用餐中，然後被收第二次錢。
     */
    @Test
    fun `an unknown status is not guessed`() {
        val order = toOpenOrder("order_5", doc("status" to "refunded"))

        assertEquals(OrderStatus.UNKNOWN, order.status)
        assertFalse(order.isEditable)
        // 品項與金額照樣讀得出來，店員看得到這張單有什麼。
        assertEquals(360, order.total)
        assertEquals(1, order.lines.size)
    }

    @Test
    fun `an unknown order type is null rather than a guess`() {
        assertNull(toOpenOrder("order_6", doc("orderType" to "delivery")).orderType)
    }

    @Test
    fun `open and pending confirm are the editable states`() {
        assertTrue(toOpenOrder("o", doc("status" to "open")).isEditable)
        assertTrue(toOpenOrder("o", doc("status" to "pending_confirm")).isEditable)
        assertFalse(toOpenOrder("o", doc("status" to "closed")).isEditable)
        assertFalse(toOpenOrder("o", doc("status" to "voided")).isEditable)
    }

    // 一個欄位壞掉不該讓整張桌的訂單讀不出來：店員按下去看到一片空白，
    // 而客人正站在櫃台前等結帳。
    @Test
    fun `a broken field only breaks that field`() {
        val order = toOpenOrder(
            "order_7",
            doc("total" to "三百六", "serviceCharge" to null, "tableLabels" to "窗邊"),
        )

        assertEquals(0, order.total)
        assertEquals(0, order.serviceCharge)
        assertTrue(order.tableLabels.isEmpty())
        // 壞掉的是那幾格，品項仍然讀得出來。
        assertEquals("牛肉麵", order.lines.single().name)
        assertEquals(360, order.subtotal)
    }

    @Test
    fun `an empty document does not blow up`() {
        val order = toOpenOrder("order_8", emptyMap())

        assertEquals(OrderStatus.UNKNOWN, order.status)
        assertEquals(OrderSource.UNKNOWN, order.source)
        assertTrue(order.lines.isEmpty())
        assertEquals(0, order.total)
        assertNull(order.createdAt)
        assertNull(order.pickupCode)
    }

    @Test
    fun `junk inside the lines array is skipped, not fatal`() {
        val order = toOpenOrder("order_9", doc("lines" to listOf("nope", 7L, line())))

        assertEquals(1, order.lines.size)
        assertEquals("牛肉麵", order.lines.single().name)
    }

    // Firestore 的整數一律回 Long，小數回 Double。金額在伺服器那邊全程是整數元。
    @Test
    fun `numbers arrive as Long and Double`() {
        val order = toOpenOrder("order_10", doc("total" to 360.0, "discount" to 50L))

        assertEquals(360, order.total)
        assertEquals(50, order.discount)
    }

    @Test
    fun `a blank pickup code is treated as none`() {
        assertNull(toOpenOrder("o", doc("pickupCode" to "")).pickupCode)
    }

    @Test
    fun `options without a name are left out`() {
        val order = toOpenOrder(
            "o",
            doc("lines" to listOf(line("options" to listOf(mapOf("groupId" to "grp_spicy"), mapOf("name" to "大辣"))))),
        )

        assertEquals(listOf("大辣"), order.lines.single().options)
    }
}
