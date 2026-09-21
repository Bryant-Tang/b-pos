// 測試方法名一律用 ASCII，中文寫在方法上方的註解裡。
//
// Kotlin 的反引號方法名會原樣變成 class 檔名，而 JVM 寫檔用作業系統 locale 的編碼。
// locale 是 POSIX/C 的機器（容器映像很常見）編碼是 ASCII，中文方法名會讓 Kotlin
// 編譯器丟 InvalidPathException 內部錯誤——不是測試失敗，是整個測試模組編不起來，
// 而且錯誤訊息完全看不出跟中文有關。不要順手改回中文。
package io.github.bryanttang.bpos.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MenuTest {

    // 今日售完的品項不該出現在點餐畫面上。
    @Test
    fun `sold out items are hidden from the grid`() {
        val soldOut = MenuFixtures.beefNoodle.copy(available = false)
        val menu = MenuFixtures.menu.copy(items = listOf(soldOut, MenuFixtures.bubbleTea))

        assertEquals(emptyList<MenuItem>(), menu.availableItemsIn(MenuFixtures.CATEGORY_NOODLE))
    }

    // 同一分類底下依 sort 排，不是依名字，也不是依資料進來的順序。
    @Test
    fun `items in a category are ordered by sort`() {
        val second = MenuFixtures.beefNoodle.copy(itemId = "item_pork_noodle", name = "排骨麵", sort = 2)
        val menu = MenuFixtures.menu.copy(
            items = listOf(second, MenuFixtures.beefNoodle, MenuFixtures.bubbleTea),
        )

        assertEquals(
            listOf(MenuFixtures.ITEM_BEEF_NOODLE, "item_pork_noodle"),
            menu.availableItemsIn(MenuFixtures.CATEGORY_NOODLE).map { it.itemId },
        )
    }

    // 選項組照品項自己列的順序回傳，而不是照菜單裡的順序；
    // 畫面上 bottom sheet 的排列要跟後台設定的一致。
    @Test
    fun `option groups follow the order the item declares`() {
        val reversed = MenuFixtures.beefNoodle.copy(
            optionGroupIds = listOf(MenuFixtures.GROUP_TOPPING, MenuFixtures.GROUP_SPICE),
        )

        assertEquals(
            listOf(MenuFixtures.GROUP_TOPPING, MenuFixtures.GROUP_SPICE),
            MenuFixtures.menu.optionGroupsOf(reversed).map { it.groupId },
        )
    }

    // 品項指到一個菜單裡沒有的選項組時要安靜跳過，不要讓整個畫面炸掉。
    // 後台刪掉一組規格、平板還拿著舊快照時就會這樣。
    @Test
    fun `an unknown option group is skipped instead of crashing`() {
        val item = MenuFixtures.beefNoodle.copy(optionGroupIds = listOf("group_never_seen"))

        assertEquals(emptyList<OptionGroup>(), MenuFixtures.menu.optionGroupsOf(item))
        assertNull(MenuFixtures.menu.optionGroup("group_never_seen"))
    }

    // 單選組不可能合法地選超過一個，讓後台的錯誤設定在建構時就被擋下來。
    @Test
    fun `a single choice group cannot allow more than one pick`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            OptionGroup(
                groupId = "group_broken",
                name = "辣度",
                type = OptionGroupType.SINGLE,
                min = 1,
                max = 2,
                options = emptyList(),
            )
        }

        assertEquals(true, error.message!!.contains("單選組"))
    }

    // max 小於 min 的組是設定錯誤，任何選擇都不可能滿足它。
    @Test
    fun `a group whose max is below its min is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OptionGroup(
                groupId = "group_broken",
                name = "加料",
                type = OptionGroupType.MULTI,
                min = 2,
                max = 1,
                options = emptyList(),
            )
        }
    }

    // 價格不可為負。負的價差是合法的（不要飯折 10 元），負的售價不是。
    @Test
    fun `a negative price is rejected but a negative option delta is fine`() {
        assertThrows(IllegalArgumentException::class.java) {
            MenuFixtures.beefNoodle.copy(price = -1)
        }

        assertEquals(-10, MenuFixtures.toppingGroup.option(MenuFixtures.OPTION_NO_RICE)!!.priceDelta)
    }
}
