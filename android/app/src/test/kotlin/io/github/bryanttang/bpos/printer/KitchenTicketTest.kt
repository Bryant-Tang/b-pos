package io.github.bryanttang.bpos.printer

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 *
 * 資料全部是虛構的（CLAUDE.md 第一條）：店名用「範例餐廳」，品項用「牛肉麵」。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitchenTicketTest {

    private fun ticket(
        kind: KitchenTicket.Kind = KitchenTicket.Kind.NEW,
        tableLabel: String? = "A1",
        pickupCode: String? = null,
        lines: List<KitchenTicket.Line> = listOf(KitchenTicket.Line("牛肉麵", 1)),
    ) = KitchenTicket(
        kind = kind,
        tableLabel = tableLabel,
        pickupCode = pickupCode,
        orderedAt = "19:05",
        lines = lines,
    )

    private fun texts(rows: List<TicketRow>) = rows.filterIsInstance<TicketRow.Text>().map { it.text }

    @Test
    fun `the table number is the biggest thing on the ticket`() {
        val rows = layoutKitchenTicket(ticket())

        val headline = rows.filterIsInstance<TicketRow.Text>().first()
        assertEquals("A1", headline.text)
        assertEquals(2, headline.scale)
        assertEquals(TicketRow.Align.CENTER, headline.align)
    }

    // 加點單一定要在最上面講清楚。廚師看到一張跟剛剛很像的單子時，
    // 第一個要排除的疑問就是「這是不是重印」——分不出來就會做出兩份。
    @Test
    fun `an addition ticket says so before anything else`() {
        val rows = layoutKitchenTicket(ticket(kind = KitchenTicket.Kind.ADDITION))

        val first = rows.filterIsInstance<TicketRow.Text>().first()
        assertTrue(first.text.contains("加"))
        assertEquals(2, first.scale)
    }

    @Test
    fun `a takeout ticket shows the pickup code instead of a table`() {
        val rows = layoutKitchenTicket(ticket(tableLabel = null, pickupCode = "0042"))

        assertEquals("外帶 0042", rows.filterIsInstance<TicketRow.Text>().first().text)
    }

    // 候位單綁桌之前兩個都沒有。印「外帶」而不是留白：沒有標頭的單子，
    // 廚師無從判斷那是漏印還是真的沒有桌號。
    @Test
    fun `a ticket with neither table nor pickup code still has a headline`() {
        val rows = layoutKitchenTicket(ticket(tableLabel = null, pickupCode = null))

        assertEquals("外帶", rows.filterIsInstance<TicketRow.Text>().first().text)
    }

    // 廚房單不印金額，一個數字都不印（SPEC 第七節）。
    @Test
    fun `no prices appear anywhere on a kitchen ticket`() {
        val rows = layoutKitchenTicket(
            ticket(lines = listOf(KitchenTicket.Line("牛肉麵", 2), KitchenTicket.Line("珍珠奶茶", 1))),
        )

        val body = texts(rows).joinToString("\n")
        assertFalse(body.contains("$"))
        assertFalse(body.contains("元"))
        assertFalse(body.contains("小計"))
        assertFalse(body.contains("總計"))
    }

    @Test
    fun `the quantity is flushed to the right edge`() {
        val rows = layoutKitchenTicket(ticket(lines = listOf(KitchenTicket.Line("牛肉麵", 2))))

        val item = texts(rows).single { it.contains("牛肉麵") }
        assertEquals(DEFAULT_COLUMNS, displayWidth(item))
        assertTrue(item.endsWith("×2"))
    }

    /**
     * 名稱太長時數量跟著最後一行。數量印在第一行而名稱折到第二行的話，
     * 掃過去會看成兩個不同的品項。
     */
    @Test
    fun `a long name wraps and keeps the quantity on its last line`() {
        val long = "招牌紅燒牛肉麵加辣加麵不要香菜"
        val rows = layoutKitchenTicket(ticket(lines = listOf(KitchenTicket.Line(long, 3))))

        val itemLines = texts(rows).filter { it.isNotBlank() && !it.startsWith(" ") && it != "A1" && it != "19:05" }
        assertTrue(itemLines.size > 1)
        assertFalse(itemLines.first().contains("×"))
        assertTrue(itemLines.last().endsWith("×3"))
        assertTrue(itemLines.all { displayWidth(it) <= DEFAULT_COLUMNS })
    }

    // 規格與備註縮排掛在品項底下，廚師才不會把規格看成另一個品項。
    @Test
    fun `options and notes are indented under the item`() {
        val rows = layoutKitchenTicket(
            ticket(
                lines = listOf(
                    KitchenTicket.Line("牛肉麵", 1, options = listOf("大辣", "加麵"), note = "先出"),
                ),
            ),
        )

        val body = texts(rows)
        assertTrue(body.any { it == "  大辣" })
        assertTrue(body.any { it == "  加麵" })
        assertTrue(body.any { it == "  備註：先出" })
    }

    @Test
    fun `a blank note is left off`() {
        val rows = layoutKitchenTicket(ticket(lines = listOf(KitchenTicket.Line("牛肉麵", 1, note = "   "))))

        assertFalse(texts(rows).any { it.contains("備註") })
    }

    /**
     * 桌號是老闆在後台自己打的文字，不是兩位數的編號，所以它可以比一行還長。
     * 放大一倍的標頭一行只放得下 8 個中文字，超過的部分如果被裁掉，
     * 廚房會收到一張桌號少了尾巴的單子，然後把菜送到別桌。
     */
    @Test
    fun `a long table label wraps instead of being cut off`() {
        val label = "二樓靠窗的大圓桌包廂"

        val rows = layoutKitchenTicket(ticket(tableLabel = label))
        val banner = rows.filterIsInstance<TicketRow.Text>().takeWhile { it.scale == 2 }

        assertTrue(banner.size > 1)
        assertEquals(label, banner.joinToString("") { it.text })
        for (row in banner) {
            assertTrue(row.text, displayWidth(row.text) <= DEFAULT_COLUMNS / row.scale)
        }
    }

    @Test
    fun `nothing on the ticket is wider than the paper`() {
        val rows = layoutKitchenTicket(
            ticket(
                kind = KitchenTicket.Kind.ADDITION,
                tableLabel = "二樓靠窗的大圓桌包廂",
                lines = listOf(
                    KitchenTicket.Line("招牌紅燒牛肉麵", 12, options = listOf("大辣不要香菜不要蔥花多加麵")),
                ),
            ),
        )

        for (row in rows.filterIsInstance<TicketRow.Text>()) {
            assertTrue(row.text, displayWidth(row.text) <= DEFAULT_COLUMNS / row.scale)
        }
    }

    @Test
    fun `an empty ticket is rejected rather than printed blank`() {
        val error = runCatching { ticket(lines = emptyList()) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
