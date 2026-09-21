// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// 原因見 MenuTest.kt 開頭。
package io.github.bryanttang.bpos.order

import io.github.bryanttang.bpos.menu.MenuFixtures
import io.github.bryanttang.bpos.sync.IntentLine
import io.github.bryanttang.bpos.sync.OrderIntent
import io.github.bryanttang.bpos.sync.OrderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class CartTest {

    private val menu = MenuFixtures.menu
    private val beefNoodle = MenuFixtures.beefNoodle
    private val bubbleTea = MenuFixtures.bubbleTea

    private val mild = mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD))
    private val hot = mapOf(MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_HOT))

    private fun cartOf(change: CartChange): Cart = (change as CartChange.Updated).cart

    private fun reasonOf(change: CartChange): String = (change as CartChange.Rejected).reason

    // 店員連點兩次同一個品項、同樣的規格，看到的該是「×2」而不是兩行。
    @Test
    fun `the same item with the same options merges into one line`() {
        var cart = Cart()
        cart = cartOf(cart.add(beefNoodle, mild))
        cart = cartOf(cart.add(beefNoodle, mild))

        assertEquals(1, cart.lines.size)
        assertEquals(2, cart.lines.single().qty)
    }

    // 規格不一樣就是不同的一列：小辣跟大辣要分開出單給廚房。
    @Test
    fun `the same item with different options stays on separate lines`() {
        var cart = Cart()
        cart = cartOf(cart.add(beefNoodle, mild))
        cart = cartOf(cart.add(beefNoodle, hot))

        assertEquals(2, cart.lines.size)
    }

    // 「沒選加料」與「加料選了空集合」是同一件事，要併成同一列。
    // 不正規化的話，畫面上會出現兩行一模一樣的牛肉麵。
    @Test
    fun `an empty option group is treated as not choosing that group`() {
        var cart = Cart()
        cart = cartOf(cart.add(beefNoodle, mild))
        cart = cartOf(cart.add(beefNoodle, mild + (MenuFixtures.GROUP_TOPPING to emptySet())))

        assertEquals(1, cart.lines.size)
        assertEquals(2, cart.lines.single().qty)
    }

    // 數量改成 0 等於把那一列拿掉，不要留一列 0 份在畫面上。
    @Test
    fun `setting a quantity to zero removes the line`() {
        var cart = cartOf(Cart().add(beefNoodle, mild))
        cart = cartOf(cart.setQty(0, 0))

        assertTrue(cart.isEmpty)
    }

    // 單列超過上限要擋下來並說原因，不是丟例外把畫面弄掛。
    @Test
    fun `a line beyond the quantity cap is rejected with a reason`() {
        val cart = cartOf(Cart().add(beefNoodle, mild, qty = IntentLine.MAX_QTY))
        val change = cart.add(beefNoodle, mild)

        assertTrue(reasonOf(change).contains("${IntentLine.MAX_QTY}"))
        // 被擋下來時原本那車不該有任何變化。
        assertEquals(IntentLine.MAX_QTY, cart.lines.single().qty)
    }

    // 一張單的列數也有上限，超過同樣是擋下來說原因。
    @Test
    fun `a cart beyond the line cap is rejected with a reason`() {
        var cart = Cart()
        repeat(OrderIntent.MAX_LINES) { index ->
            cart = cartOf(cart.add(beefNoodle.copy(itemId = "item_$index")))
        }

        val change = cart.add(bubbleTea)

        assertTrue(reasonOf(change).contains("${OrderIntent.MAX_LINES}"))
    }

    // 預估金額＝（單價＋規格價差）×數量，負的價差要往下減。
    @Test
    fun `the estimate applies option deltas and quantity`() {
        val cart = cartOf(
            Cart().add(
                beefNoodle,
                mild + (MenuFixtures.GROUP_TOPPING to setOf(MenuFixtures.OPTION_EXTRA_NOODLE)),
                qty = 2,
            ),
        )

        // （180 + 20）× 2
        assertEquals(400, cart.estimatedTotal(menu, OrderType.DINE_IN))
    }

    // 外帶用外帶價；沒設外帶價的品項退回內用價。
    @Test
    fun `takeout uses the takeout price and falls back to the dine in price`() {
        var cart = cartOf(Cart().add(beefNoodle, mild))
        cart = cartOf(cart.add(bubbleTea))

        // 牛肉麵外帶 170 + 珍奶沒有外帶價所以照 60
        assertEquals(230, cart.estimatedTotal(menu, OrderType.TAKEOUT))
        // 內用是 180 + 60
        assertEquals(240, cart.estimatedTotal(menu, OrderType.DINE_IN))
    }

    // 菜單快照過期、車上有查不到的品項時，預估金額當 0 而不是讓畫面炸掉。
    @Test
    fun `an item missing from the menu contributes zero to the estimate`() {
        val cart = cartOf(Cart().add(beefNoodle.copy(itemId = "item_never_seen")))

        assertEquals(0, cart.estimatedTotal(menu, OrderType.DINE_IN))
    }

    // 送出去的內容只有 itemId、數量與選項 id，一個金額欄位都沒有。
    // 這是 CLAUDE.md 第二節第一條，伺服器的 schema 是 .strict()，多帶會整筆被拒。
    @Test
    fun `intent lines carry no prices and no names`() {
        val cart = cartOf(Cart().add(beefNoodle, mild, qty = 3))
        val line = cart.toIntentLines().single()

        assertEquals(MenuFixtures.ITEM_BEEF_NOODLE, line.itemId)
        assertEquals(3, line.qty)
        assertEquals(MenuFixtures.OPTION_MILD, line.options.single().optionId)
        // IntentLine 的實例欄位就這三個；有人加了 price 之類的欄位這個斷言會先壞掉。
        // 只看非 static 的欄位：companion 裡的 const（MAX_QTY 等）會編成 static 欄位，
        // 它們是上限常數不是資料，不該被算進「這個型別帶了什麼出去」。
        assertEquals(
            setOf("itemId", "qty", "options"),
            IntentLine::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    // 同樣的選擇不該因為 Set 的迭代順序而送出不同的內容，
    // 否則同一筆單重送時很難看出「這兩筆其實一樣」。
    @Test
    fun `options are emitted in a deterministic order`() {
        val selection = mapOf(
            MenuFixtures.GROUP_TOPPING to setOf(
                MenuFixtures.OPTION_NO_RICE,
                MenuFixtures.OPTION_EXTRA_NOODLE,
            ),
            MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD),
        )
        val reversed = mapOf(
            MenuFixtures.GROUP_SPICE to setOf(MenuFixtures.OPTION_MILD),
            MenuFixtures.GROUP_TOPPING to setOf(
                MenuFixtures.OPTION_EXTRA_NOODLE,
                MenuFixtures.OPTION_NO_RICE,
            ),
        )

        val first = cartOf(Cart().add(beefNoodle, selection)).toIntentLines()
        val second = cartOf(Cart().add(beefNoodle, reversed)).toIntentLines()

        assertEquals(first, second)
        assertEquals(
            listOf(MenuFixtures.GROUP_SPICE, MenuFixtures.GROUP_TOPPING, MenuFixtures.GROUP_TOPPING),
            first.single().options.map { it.groupId },
        )
    }

    // 總份數算的是份數不是列數，畫面上的「共 N 份」靠它。
    @Test
    fun `total quantity counts portions not lines`() {
        var cart = cartOf(Cart().add(beefNoodle, mild, qty = 2))
        cart = cartOf(cart.add(bubbleTea, qty = 3))

        assertEquals(2, cart.lines.size)
        assertEquals(5, cart.totalQty)
    }
}
