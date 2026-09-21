// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// 原因見 sync/OutboxRepositoryTest.kt 開頭。
package io.github.bryanttang.bpos.order

import io.github.bryanttang.bpos.menu.MenuFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 店員在 bottom sheet 上點選項時，整組規格會怎麼變。
 *
 * 這條規則刻意不放在 Composable 裡：放進去就只能靠 Compose 測試驗，
 * 而漏掉單選組的「換」語意是那種畫面上看得到、但看不出原因的錯
 * （兩個辣度都被勾起來，「加入」卻按不下去）。
 */
class ToggleOptionTest {

    private val spice = MenuFixtures.spiceGroup
    private val topping = MenuFixtures.toppingGroup

    // 單選組是「換」不是「加」：已經選了小辣再點大辣，結果只有大辣。
    @Test
    fun `a single choice group replaces the previous pick`() {
        val before = mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD))

        val after = toggleOption(spice, before, MenuFixtures.OPTION_HOT)

        assertEquals(setOf(MenuFixtures.OPTION_HOT), after[MenuFixtures.GROUP_SPICE])
    }

    // 這是上一條沒做對時會停在的狀態：兩個都選起來，checkSelection 一定擋。
    // 拿來確認 toggleOption 真的擋掉了那條路，不是靠後面的檢查補救。
    @Test
    fun `a single choice group never reaches a state the checker rejects`() {
        val before = mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD))

        val after = toggleOption(spice, before, MenuFixtures.OPTION_HOT)

        assertEquals(
            SelectionCheck.Ok,
            checkSelection(MenuFixtures.menu, MenuFixtures.beefNoodle, after),
        )
    }

    // 複選組是「加」：兩個可以並存。
    @Test
    fun `a multi choice group adds to the existing picks`() {
        val before = mapOf(MenuFixtures.GROUP_TOPPING to setOf(MenuFixtures.OPTION_EXTRA_NOODLE))

        val after = toggleOption(topping, before, MenuFixtures.OPTION_NO_RICE)

        assertEquals(
            setOf(MenuFixtures.OPTION_EXTRA_NOODLE, MenuFixtures.OPTION_NO_RICE),
            after[MenuFixtures.GROUP_TOPPING],
        )
    }

    // 再點一次已經選起來的選項是取消，單選複選都一樣：點錯一次不該只能關掉重來。
    @Test
    fun `tapping a chosen option again clears it`() {
        val before = mapOf(MenuFixtures.GROUP_TOPPING to setOf(MenuFixtures.OPTION_EXTRA_NOODLE))

        val after = toggleOption(topping, before, MenuFixtures.OPTION_EXTRA_NOODLE)

        assertTrue(MenuFixtures.GROUP_TOPPING !in after)
    }

    // 必選的單選組也取消得掉，只是取消之後送不出去——那是 checkSelection 的事。
    // 這裡要驗的是店員有辦法改變心意。
    @Test
    fun `a required group can still be cleared and then blocks the submit`() {
        val before = mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD))

        val after = toggleOption(spice, before, MenuFixtures.OPTION_MILD)

        assertTrue(MenuFixtures.GROUP_SPICE !in after)
        assertTrue(
            checkSelection(MenuFixtures.menu, MenuFixtures.beefNoodle, after)
                is SelectionCheck.Invalid,
        )
    }

    // 整組被取消光時要把那個 key 拿掉，不是留一個空集合——
    // 跟購物車的正規化規則一致，否則兩台看起來一樣的購物車會併不起來。
    @Test
    fun `an emptied group is removed rather than left as an empty set`() {
        val before = mapOf(MenuFixtures.GROUP_TOPPING to setOf(MenuFixtures.OPTION_EXTRA_NOODLE))

        val after = toggleOption(topping, before, MenuFixtures.OPTION_EXTRA_NOODLE)

        assertEquals(emptyMap<String, Set<String>>(), after)
    }

    // 動一組不該影響另一組。
    @Test
    fun `toggling one group leaves the others alone`() {
        val before = mapOf(
            MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD),
            MenuFixtures.GROUP_TOPPING to setOf(MenuFixtures.OPTION_EXTRA_NOODLE),
        )

        val after = toggleOption(spice, before, MenuFixtures.OPTION_HOT)

        assertEquals(
            setOf(MenuFixtures.OPTION_EXTRA_NOODLE),
            after[MenuFixtures.GROUP_TOPPING],
        )
    }
}
