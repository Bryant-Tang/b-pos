package io.github.bryanttang.bpos.printer

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TicketRendererTest {

    private val geometry = TicketGeometry()

    private val ticket = KitchenTicket(
        kind = KitchenTicket.Kind.NEW,
        tableLabel = "A1",
        pickupCode = null,
        orderedAt = "19:05",
        lines = listOf(KitchenTicket.Line("牛肉麵", 2, options = listOf("大辣"))),
    )

    @Test
    fun `the bitmap is exactly as wide as the paper`() {
        val bitmap = TicketRenderer(geometry).render(layoutKitchenTicket(ticket))

        assertEquals(geometry.widthPx, bitmap.width)
    }

    @Test
    fun `the bitmap is exactly as tall as the layout says`() {
        val rows = layoutKitchenTicket(ticket)

        val bitmap = TicketRenderer(geometry).render(rows)

        assertEquals(geometry.place(rows).heightPx, bitmap.height)
    }

    /**
     * 底色要是不透明的白。
     *
     * 這條要對 Bitmap 本身斷言，不能透過 [toMonoBitmap] 看——那邊會把全透明的點
     * 當成疊在白紙上，於是「忘了填底色」跟「填了白色」轉出來一模一樣，測了等於沒測。
     */
    @Test
    fun `the background is opaque white`() {
        val bitmap = TicketRenderer(geometry).render(layoutKitchenTicket(ticket))

        // 左上角在上邊界裡，任何一列都畫不到那裡。
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
    }

    /**
     * 往右移一格，畫出來就剛好差一格。
     *
     * 這是這支 renderer 唯一真正的主張：對齊由我們自己的格線決定，不由字型的前進量決定。
     * 驗位移而不是驗「有沒有超出格子」——抗鋸齒本來就會讓墨跡比前進量寬一兩點，
     * 拿墨跡的邊界當界線只會測到字型的長相。位移差一格才是「數量那一欄對得齊」的定義。
     *
     * 全形字佔兩格這件事由 [displayWidth] 決定，已經在 TextWidthTest 驗過，
     * 這裡不重複。
     */
    @Test
    fun `shifting a glyph one cell moves it exactly one cell width`() {
        val renderer = TicketRenderer(geometry)

        val shift = inkStart(renderer, " A") - inkStart(renderer, "A")

        assertEquals(geometry.cellWidth, shift)
    }

    /** 那一行第一個有墨的 x。 */
    private fun inkStart(renderer: TicketRenderer, text: String): Int {
        val mono = renderer.render(listOf(TicketRow.Text(text))).toMonoBitmap()
        for (x in 0 until mono.width) {
            for (y in 0 until mono.height) {
                if (mono[x, y]) return x
            }
        }
        throw AssertionError("這一行沒有畫出任何東西：$text")
    }

    @Test
    fun `the rule is drawn all the way across`() {
        val rows = listOf(TicketRow.Rule)
        val placed = geometry.place(rows).rows.single()

        val mono = TicketRenderer(geometry).render(rows).toMonoBitmap()

        val middle = placed.top + placed.height / 2
        assertTrue(mono[0, middle])
        assertTrue(mono[geometry.widthPx - 1, middle])
    }
}
