package io.github.bryanttang.bpos.printer

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TicketGeometryTest {

    private val geometry = TicketGeometry()

    @Test
    fun `the default paper is 80mm at 32 columns`() {
        assertEquals(576, geometry.widthPx)
        assertEquals(18, geometry.cellWidth)
        assertEquals(36, geometry.unit)
    }

    // 餘數留在右邊當邊界，不要平均分攤回每一格：每格差零點幾點，
    // 累積到行尾就是一兩格的偏移，數量那一欄會對不齊。
    @Test
    fun `an uneven paper width truncates the cell instead of spreading the remainder`() {
        assertEquals(12, TicketGeometry(widthPx = 384, columns = 32).cellWidth)
        assertEquals(11, TicketGeometry(widthPx = 380, columns = 32).cellWidth)
    }

    @Test
    fun `a doubled row is twice as tall`() {
        assertEquals(36, geometry.heightOf(TicketRow.Text("牛肉麵")))
        assertEquals(72, geometry.heightOf(TicketRow.Text("A1", scale = 2)))
    }

    @Test
    fun `rows are stacked in order`() {
        val rows = listOf(TicketRow.Text("A1", scale = 2), TicketRow.Rule, TicketRow.Text("牛肉麵"))

        val placed = geometry.place(rows).rows

        assertEquals(listOf(36, 108, 144), placed.map { it.top })
        assertEquals(listOf(72, 36, 36), placed.map { it.height })
    }

    /**
     * 上下各留一個單位。切刀在出紙口、印字頭在它後面幾公分，最後一行緊貼邊緣時，
     * 走紙的誤差會直接吃掉那一行。
     */
    @Test
    fun `the ticket keeps a margin above and below`() {
        val placement = geometry.place(listOf(TicketRow.Text("牛肉麵")))

        assertEquals(36, placement.rows.single().top)
        assertEquals(36 + 36 + 36, placement.heightPx)
    }

    @Test
    fun `paper too narrow for the requested columns is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TicketGeometry(widthPx = 32, columns = 32)
        }
    }
}
