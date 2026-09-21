// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// 原因見 MenuTest.kt 開頭：中文方法名會讓 Kotlin 編譯器在 ASCII locale 的機器上
// 丟 InvalidPathException 內部錯誤，整個測試模組編不起來。
package io.github.bryanttang.bpos.order

import io.github.bryanttang.bpos.menu.MenuFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionSelectionTest {

    private val menu = MenuFixtures.menu
    private val beefNoodle = MenuFixtures.beefNoodle

    private fun reasonOf(check: SelectionCheck): String =
        (check as SelectionCheck.Invalid).reason

    // 必選的組沒選就不能加進購物車。
    @Test
    fun `a required group must be chosen`() {
        val check = checkSelection(menu, beefNoodle, emptyMap())

        assertTrue(reasonOf(check).contains("辣度"))
    }

    // 該選的都選了就過。
    @Test
    fun `a complete selection passes`() {
        val check = checkSelection(
            menu,
            beefNoodle,
            mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD)),
        )

        assertEquals(SelectionCheck.Ok, check)
    }

    // 單選組選了兩個要擋下來。
    @Test
    fun `a single choice group rejects two picks`() {
        val check = checkSelection(
            menu,
            beefNoodle,
            mapOf(
                MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD, MenuFixtures.OPTION_HOT),
            ),
        )

        assertTrue(reasonOf(check).contains("只能選一個"))
    }

    // 複選組超過 max 要擋下來。
    @Test
    fun `a multi choice group rejects more than max`() {
        val group = MenuFixtures.toppingGroup.copy(max = 1)
        val menuWithTighterGroup = menu.copy(optionGroups = listOf(MenuFixtures.spiceGroup, group))

        val check = checkSelection(
            menuWithTighterGroup,
            beefNoodle,
            mapOf(
                MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD),
                MenuFixtures.GROUP_TOPPING to setOf(
                    MenuFixtures.OPTION_EXTRA_NOODLE,
                    MenuFixtures.OPTION_NO_RICE,
                ),
            ),
        )

        assertTrue(reasonOf(check).contains("最多"))
    }

    // 可選的組整組不選是合法的。
    @Test
    fun `an optional group may be left empty`() {
        val check = checkSelection(
            menu,
            beefNoodle,
            mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD)),
        )

        assertEquals(SelectionCheck.Ok, check)
    }

    // 選到一個這個品項根本沒有的組，通常表示平板的菜單快照過期了。
    // 這種要擋下來並提示重新整理，不要照送讓伺服器回一則對不起畫面的錯誤。
    @Test
    fun `a group the item does not have is rejected as a stale menu`() {
        val check = checkSelection(
            menu,
            beefNoodle,
            mapOf(
                MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD),
                "group_never_seen" to setOf("opt_whatever"),
            ),
        )

        assertTrue(reasonOf(check).contains("重新整理"))
    }

    // 組是對的但選項 id 不存在，同樣是菜單過期。
    @Test
    fun `an option id that does not exist is rejected as a stale menu`() {
        val check = checkSelection(
            menu,
            beefNoodle,
            mapOf(MenuFixtures.GROUP_SPICE to setOf("opt_never_seen")),
        )

        assertTrue(reasonOf(check).contains("重新整理"))
    }

    // 價差會把整組加起來，而且負的價差要真的往下減。
    @Test
    fun `price deltas add up and negative ones subtract`() {
        val delta = priceDeltaOf(
            menu,
            beefNoodle,
            mapOf(
                MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD),
                MenuFixtures.GROUP_TOPPING to setOf(
                    MenuFixtures.OPTION_EXTRA_NOODLE,
                    MenuFixtures.OPTION_NO_RICE,
                ),
            ),
        )

        // 加麵 +20、不要飯 -10、小辣 0
        assertEquals(10, delta)
    }

    // 算預估金額時遇到查不到的選項要當 0，不要丟例外——這條路上只負責畫面顯示，
    // 合不合法是 checkSelection 的事，在這裡炸掉會讓整個購物車畫不出來。
    @Test
    fun `an unknown option contributes nothing to the estimate`() {
        val delta = priceDeltaOf(
            menu,
            beefNoodle,
            mapOf(MenuFixtures.GROUP_TOPPING to setOf("opt_never_seen")),
        )

        assertEquals(0, delta)
    }
}
