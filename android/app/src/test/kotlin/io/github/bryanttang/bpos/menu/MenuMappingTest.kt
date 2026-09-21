package io.github.bryanttang.bpos.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方法名一律用 ASCII：Kotlin 的反引號方法名會原樣變成檔名，
 * locale 是 POSIX 的機器上中文方法名會讓整個測試模組編不起來。
 *
 * 資料全部是虛構的（CLAUDE.md 第一節）。
 */
class MenuMappingTest {

    @Test
    fun `a published menu document maps to a menu`() {
        val menu = toMenu(menuDoc())

        assertEquals(1_700_000_000_000, menu?.version)
        assertEquals(2, menu?.categories?.size)
        assertEquals(2, menu?.items?.size)
        assertEquals(1, menu?.optionGroups?.size)

        val beefNoodle = menu?.item("item_beef_noodle")
        assertEquals("牛肉麵", beefNoodle?.name)
        assertEquals(180, beefNoodle?.price)
        assertEquals(170, beefNoodle?.takeoutPrice)
        assertEquals(listOf("group_spice"), beefNoodle?.optionGroupIds)
    }

    @Test
    fun `firestore numbers arrive as long and still become ints`() {
        // Firestore 的整數一律回 Long，不會是 Int。直接 as? Int 會全部變成 null，
        // 於是每個品項都被當成壞資料跳過，菜單整份空掉。
        val menu = toMenu(
            menuDoc(
                items = listOf(
                    itemDoc(price = 180L, sort = 3L, takeoutPrice = 170L),
                ),
            ),
        )

        val item = menu?.item("item_beef_noodle")
        assertEquals(180, item?.price)
        assertEquals(170, item?.takeoutPrice)
        assertEquals(3, item?.sort)
    }

    @Test
    fun `a document without a version is not a usable menu`() {
        assertNull(toMenu(menuDoc() - "version"))
        assertNull(toMenu(menuDoc(version = "1700000000000")))
    }

    @Test
    fun `an item with an unreadable price is dropped rather than given a default`() {
        // 這是整支對映最要緊的一條：價格讀不出來時若退回 0，那道菜會被免費賣出去，
        // 而且畫面上完全看不出異常。寧可點不到，不可以點到錯的價格。
        val menu = toMenu(menuDoc(items = listOf(itemDoc() - "price")))
        assertTrue(menu!!.items.isEmpty())

        val negative = toMenu(menuDoc(items = listOf(itemDoc(price = -10L))))
        assertTrue(negative!!.items.isEmpty())

        val wrongType = toMenu(menuDoc(items = listOf(itemDoc(price = "180"))))
        assertTrue(wrongType!!.items.isEmpty())
    }

    @Test
    fun `a broken takeout price drops the item instead of falling back to the dine in price`() {
        // 退回內用價會讓外帶被多收錢，而且沒有人會發現。
        val menu = toMenu(menuDoc(items = listOf(itemDoc(takeoutPrice = -5L))))
        assertTrue(menu!!.items.isEmpty())

        // 但「沒填」是正常的，那就是跟內用同價。
        val noTakeout = toMenu(menuDoc(items = listOf(itemDoc() - "takeoutPrice")))
        assertNull(noTakeout?.item("item_beef_noodle")?.takeoutPrice)
    }

    @Test
    fun `an item pointing at a missing option group is dropped`() {
        // 辣度是必選組。組沒讀出來的話，店員會在完全沒被問到辣度的情況下
        // 把這碗麵送進廚房，而畫面上沒有任何線索說少問了一件事。
        val menu = toMenu(
            menuDoc(
                optionGroups = emptyList(),
                items = listOf(itemDoc()),
            ),
        )

        assertTrue(menu!!.items.isEmpty())
    }

    @Test
    fun `one broken item does not take the rest of the menu down`() {
        val menu = toMenu(
            menuDoc(
                items = listOf(
                    itemDoc() - "price",
                    itemDoc(itemId = "item_bubble_tea", name = "珍珠奶茶", optionGroupIds = emptyList()),
                ),
            ),
        )

        assertEquals(listOf("item_bubble_tea"), menu?.items?.map { it.itemId })
    }

    @Test
    fun `an option group with contradictory limits is skipped without killing the menu`() {
        // 單選組的 max 不可以大於 1（OptionGroup 的 init 會擋）。後台存進壞設定時
        // 要跳過那一組，不是讓整份菜單載不起來。
        val menu = toMenu(
            menuDoc(
                optionGroups = listOf(spiceGroupDoc(max = 2L)),
                items = listOf(itemDoc(optionGroupIds = emptyList())),
            ),
        )

        assertTrue(menu!!.optionGroups.isEmpty())
        assertEquals(1, menu.items.size)
    }

    @Test
    fun `an option group with no readable options is skipped`() {
        val menu = toMenu(
            menuDoc(
                optionGroups = listOf(spiceGroupDoc(options = emptyList())),
                items = listOf(itemDoc(optionGroupIds = emptyList())),
            ),
        )

        assertTrue(menu!!.optionGroups.isEmpty())
    }

    @Test
    fun `a negative price delta is kept because discounts are real options`() {
        // 「不要飯」折 10 元。這裡用 nonNegativeInt 會把合法的折扣選項丟掉。
        val menu = toMenu(
            menuDoc(
                optionGroups = listOf(
                    spiceGroupDoc(
                        options = listOf(
                            mapOf("optionId" to "opt_no_rice", "name" to "不要飯", "priceDelta" to -10L),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(-10, menu?.optionGroup("group_spice")?.option("opt_no_rice")?.priceDelta)
    }

    @Test
    fun `a missing price delta means no surcharge`() {
        val menu = toMenu(
            menuDoc(
                optionGroups = listOf(
                    spiceGroupDoc(
                        options = listOf(mapOf("optionId" to "opt_mild", "name" to "小辣")),
                    ),
                ),
            ),
        )

        assertEquals(0, menu?.optionGroup("group_spice")?.option("opt_mild")?.priceDelta)
    }

    @Test
    fun `garbage in the list fields does not throw`() {
        val menu = toMenu(
            mapOf(
                "version" to 1_700_000_000_000L,
                "categories" to "not a list",
                "items" to listOf("not a map", 42L),
                "optionGroups" to null,
            ),
        )

        assertEquals(1_700_000_000_000, menu?.version)
        assertTrue(menu!!.categories.isEmpty())
        assertTrue(menu.items.isEmpty())
        assertTrue(menu.optionGroups.isEmpty())
    }

    @Test
    fun `available defaults to true so a missing flag never hides a dish`() {
        val menu = toMenu(menuDoc(items = listOf(itemDoc() - "available")))
        assertEquals(true, menu?.item("item_beef_noodle")?.available)

        val soldOut = toMenu(menuDoc(items = listOf(itemDoc(available = false))))
        assertEquals(false, soldOut?.item("item_beef_noodle")?.available)
    }

    private fun menuDoc(
        version: Any? = 1_700_000_000_000L,
        categories: List<Map<String, Any?>> = listOf(
            mapOf("categoryId" to "cat_noodle", "name" to "麵食", "sort" to 1L),
            mapOf("categoryId" to "cat_drink", "name" to "飲料", "sort" to 2L),
        ),
        items: List<Map<String, Any?>> = listOf(
            itemDoc(),
            itemDoc(itemId = "item_bubble_tea", name = "珍珠奶茶", optionGroupIds = emptyList()),
        ),
        optionGroups: List<Map<String, Any?>> = listOf(spiceGroupDoc()),
    ): Map<String, Any?> = mapOf(
        "version" to version,
        "categories" to categories,
        "items" to items,
        "optionGroups" to optionGroups,
    )

    private fun itemDoc(
        itemId: String = "item_beef_noodle",
        name: String = "牛肉麵",
        price: Any? = 180L,
        takeoutPrice: Any? = 170L,
        categoryId: String = "cat_noodle",
        optionGroupIds: List<String> = listOf("group_spice"),
        available: Boolean = true,
        sort: Any? = 1L,
    ): Map<String, Any?> = mapOf(
        "itemId" to itemId,
        "name" to name,
        "price" to price,
        "takeoutPrice" to takeoutPrice,
        "categoryId" to categoryId,
        "optionGroupIds" to optionGroupIds,
        "available" to available,
        "sort" to sort,
    )

    private fun spiceGroupDoc(
        max: Any? = 1L,
        options: List<Map<String, Any?>> = listOf(
            mapOf("optionId" to "opt_mild", "name" to "小辣", "priceDelta" to 0L),
            mapOf("optionId" to "opt_hot", "name" to "大辣", "priceDelta" to 0L),
        ),
    ): Map<String, Any?> = mapOf(
        "groupId" to "group_spice",
        "name" to "辣度",
        "type" to "single",
        "min" to 1L,
        "max" to max,
        "options" to options,
    )
}
