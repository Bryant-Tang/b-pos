package io.github.bryanttang.bpos.ui.tables

import io.github.bryanttang.bpos.tables.LiveSession
import io.github.bryanttang.bpos.tables.Table
import io.github.bryanttang.bpos.tables.TableStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FloorViewTest {

    private val now: Instant = Instant.parse("2026-09-21T11:00:00Z")

    private fun table(id: String, label: String) = Table(
        tableId = id,
        label = label,
        zoneId = "zone_hall",
        x = 0.1f,
        y = 0.1f,
        sort = 1,
        seats = 4,
    )

    private fun session(
        id: String,
        tableId: String,
        isActive: Boolean = true,
        readableUntil: Instant? = null,
        orderId: String? = null,
    ) = LiveSession(
        sessionId = id,
        tableId = tableId,
        isActive = isActive,
        readableUntil = readableUntil,
        orderId = orderId,
    )

    @Test
    fun `a table nobody has sat at is still on the plan`() {
        // 少了補空桌這一步，平面圖上會破一個洞，店員以為那張桌被刪了。
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1")),
            sessions = emptyList(),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        assertEquals(1, view.size)
        assertEquals(TableStatus.EMPTY, view.single().status)
        assertEquals(0, view.single().orderCount)
    }

    @Test
    fun `an active session makes the table occupied and counts its order`() {
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1")),
            sessions = listOf(session("s1", "table_a1", orderId = "order_1")),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        assertEquals(TableStatus.OCCUPIED, view.single().status)
        assertEquals(1, view.single().orderCount)
    }

    @Test
    fun `an unconfirmed guest order shows as pending confirm`() {
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1")),
            sessions = listOf(session("s1", "table_a1", orderId = "order_1")),
            pendingConfirmOrderIds = setOf("order_1"),
            now = now,
        )

        assertEquals(TableStatus.PENDING_CONFIRM, view.single().status)
    }

    @Test
    fun `a paid session and a new one on the same table count as two orders`() {
        // SPEC 明講不要假設「一桌一 session」：前一組客人在門口看帳單，
        // 新客人已經坐下。店員最需要看到的就是這個 2。
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1")),
            sessions = listOf(
                session("s1", "table_a1", isActive = false, readableUntil = now.plusSeconds(60), orderId = "order_1"),
                session("s2", "table_a1", orderId = "order_2"),
            ),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        // active 優先於已結帳：這張桌現在有人在用。
        assertEquals(TableStatus.OCCUPIED, view.single().status)
        assertEquals(2, view.single().orderCount)
    }

    @Test
    fun `the same order on two sessions is only counted once`() {
        // 併桌會讓一張單掛在多個 session 底下。
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1")),
            sessions = listOf(
                session("s1", "table_a1", orderId = "order_1"),
                session("s2", "table_a1", orderId = "order_1"),
            ),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        assertEquals(1, view.single().orderCount)
    }

    @Test
    fun `a session with no order counts zero`() {
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1")),
            sessions = listOf(session("s1", "table_a1", orderId = null)),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        assertEquals(TableStatus.OCCUPIED, view.single().status)
        assertEquals(0, view.single().orderCount)
    }

    @Test
    fun `an expired paid session leaves the table empty`() {
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1")),
            sessions = listOf(
                session("s1", "table_a1", isActive = false, readableUntil = now, orderId = "order_1"),
            ),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        // readableUntil 是「可讀到這個時刻為止」，剛好到點就已經過期。
        assertEquals(TableStatus.EMPTY, view.single().status)
    }

    @Test
    fun `sessions belonging to another table do not leak across`() {
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1"), table("table_a2", "A2")),
            sessions = listOf(session("s1", "table_a2", orderId = "order_1")),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        val byId = view.associateBy { it.table.tableId }
        assertEquals(TableStatus.EMPTY, byId.getValue("table_a1").status)
        assertEquals(0, byId.getValue("table_a1").orderCount)
        assertEquals(TableStatus.OCCUPIED, byId.getValue("table_a2").status)
        assertEquals(1, byId.getValue("table_a2").orderCount)
    }

    @Test
    fun `the plan keeps the order the tables came in`() {
        val view = tablesOnPlan(
            tables = listOf(table("table_a1", "A1"), table("table_a2", "A2")),
            sessions = emptyList(),
            pendingConfirmOrderIds = emptySet(),
            now = now,
        )

        assertEquals(listOf("table_a1", "table_a2"), view.map { it.table.tableId })
    }
}
