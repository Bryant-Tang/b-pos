package io.github.bryanttang.bpos.ui.nav

import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.tables.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {

    private val tableA1 = Table(
        tableId = "table_a1",
        label = "A1",
        zoneId = "zone_hall",
        x = 0.2f,
        y = 0.3f,
        sort = 1,
        seats = 4,
    )

    @Test
    fun `starts on the table overview with nowhere to go back to`() {
        assertEquals(Screen.Tables, NavStack.INITIAL.current)
        assertFalse(NavStack.INITIAL.canGoBack)
    }

    @Test
    fun `back at the bottom of the stack changes nothing`() {
        assertEquals(NavStack.INITIAL, NavStack.INITIAL.pop())
    }

    @Test
    fun `pushing and popping returns to where it started`() {
        val stack = NavStack.INITIAL.push(orderScreenFor(tableA1))

        assertEquals(orderScreenFor(tableA1), stack.current)
        assertTrue(stack.canGoBack)
        assertEquals(NavStack.INITIAL, stack.pop())
    }

    @Test
    fun `tapping the same table twice does not stack it twice`() {
        // 觸控螢幕上連點兩下會送兩次。疊兩層的話店員按一次返回還留在原地，
        // 看起來就像返回鍵壞了。
        val once = NavStack.INITIAL.push(orderScreenFor(tableA1))
        val twice = once.push(orderScreenFor(tableA1))

        assertEquals(once, twice)
        assertEquals(NavStack.INITIAL, twice.pop())
    }

    @Test
    fun `a different table does stack`() {
        val tableA2 = tableA1.copy(tableId = "table_a2", label = "A2")
        val stack = NavStack.INITIAL
            .push(orderScreenFor(tableA1))
            .push(orderScreenFor(tableA2))

        assertEquals(orderScreenFor(tableA2), stack.current)
        assertEquals(orderScreenFor(tableA1), stack.pop().current)
    }

    @Test
    fun `home drops everything above the table overview`() {
        val stack = NavStack.INITIAL
            .push(orderDetailFor(tableA1))
            .push(orderScreenFor(tableA1))

        val home = stack.home()

        assertEquals(Screen.Tables, home.current)
        assertFalse(home.canGoBack)
    }

    @Test
    fun `the order screen from a table carries its id and label`() {
        val screen = orderScreenFor(tableA1)

        assertEquals("table_a1", screen.tableId)
        assertEquals("A1", screen.tableLabel)
        assertEquals(OrderType.DINE_IN, screen.orderType)
    }

    @Test
    fun `adding more to a table opens a dine-in order on the same table`() {
        // 加點就是掛同一個 tableId 開一張新單，不是回到原本那張。
        val detail = orderDetailFor(tableA1)
        val order = addMoreFor(detail)

        assertEquals(detail.tableId, order.tableId)
        assertEquals(detail.tableLabel, order.tableLabel)
        assertEquals(OrderType.DINE_IN, order.orderType)
    }

    @Test
    fun `pop on an untouched stack returns the same instance`() {
        val stack = NavStack.INITIAL
        assertSame(stack.screens, stack.pop().screens)
    }
}
