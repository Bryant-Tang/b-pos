package io.github.bryanttang.bpos.sync

/*
 * 測試方法名一律用 ASCII，中文寫在上方的註解裡。
 *
 * Kotlin 的反引號方法名會原樣變成檔案名稱（`ClassName$方法名$1.class`），
 * 而 JVM 寫檔用的是作業系統 locale 的編碼。locale 是 POSIX / C 的機器上
 * （容器映像常見）編碼是 ASCII，中文名稱會讓 Kotlin 編譯器直接丟
 * InvalidPathException 內部錯誤——不是測試失敗，是整個測試編譯不起來。
 * 這種錯誤只在某些機器上出現，很難查，所以不要把名字改回中文。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

/**
 * 測試資料一律虛構（CLAUDE.md 第一節）：店名、品項、桌號都是編出來的。
 */
class OrderIntentTest {

    private val now = Instant.parse("2026-01-15T03:20:00Z")

    private fun intent(
        orderType: OrderType = OrderType.DINE_IN,
        tableId: String? = "table_a1",
        lines: List<IntentLine> = listOf(IntentLine(itemId = "item_beef_noodle", qty = 2)),
    ) = OrderIntent(
        intentId = "intent_0001",
        orderId = "order_0001",
        orderType = orderType,
        tableId = tableId,
        lines = lines,
        createdBy = "staff_uid_0001",
        clientCreatedAt = now,
    )

    @Test
    // 內用單沒帶桌號就建不起來
    fun `dine-in intent without a table is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            intent(orderType = OrderType.DINE_IN, tableId = null)
        }
        assertEquals("內用單必須帶桌號", e.message)
    }

    @Test
    // 外帶單帶了桌號就建不起來
    fun `takeout intent with a table is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            intent(orderType = OrderType.TAKEOUT, tableId = "table_a1")
        }
    }

    @Test
    // 候位單不帶桌號是正常的
    fun `waitlist intent without a table is accepted`() {
        val result = intent(orderType = OrderType.WAITLIST, tableId = null)
        assertEquals(OrderType.WAITLIST, result.orderType)
    }

    @Test
    // 空的品項清單建不起來
    fun `empty line list is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            intent(lines = emptyList())
        }
    }

    @Test
    // 數量必須是正整數
    fun `quantity must be a positive integer`() {
        assertThrows(IllegalArgumentException::class.java) {
            IntentLine(itemId = "item_bubble_tea", qty = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntentLine(itemId = "item_bubble_tea", qty = -1)
        }
    }

    /**
     * 伺服器的 zod schema 上限是 999 與 100，兩邊要一致。
     * 這裡先擋住的好處是錯誤出現在店員按下送出的當下，而不是幾秒後從雲端回來。
     */
    @Test
    // 數量與品項筆數的上限與伺服器一致
    fun `quantity and line limits match the server`() {
        IntentLine(itemId = "item_bubble_tea", qty = 999)
        assertThrows(IllegalArgumentException::class.java) {
            IntentLine(itemId = "item_bubble_tea", qty = 1000)
        }

        val hundred = (1..100).map { IntentLine(itemId = "item_$it", qty = 1) }
        intent(lines = hundred)
        assertThrows(IllegalArgumentException::class.java) {
            intent(lines = hundred + IntentLine(itemId = "item_101", qty = 1))
        }
    }

    /**
     * Rules 用 hasOnly 擋多的欄位、hasAll 擋少的，所以送上去的鍵必須不多不少。
     * 這份清單與 firestore.rules 的白名單、intentSchema.ts 是同一份，改動要三邊一起改。
     */
    @Test
    // 送到 Firestore 的欄位不多也不少
    fun `firestore keys are exactly the allowed set`() {
        val map = intent().toFirestoreMap()
        assertEquals(
            setOf("intentId", "orderId", "orderType", "tableId", "lines", "createdBy", "clientCreatedAt"),
            map.keys,
        )
    }

    /**
     * 平板永遠不送金額（CLAUDE.md 第二節第一條）。
     * 這個測試是那條規則的守門員：日後有人在意圖裡加上 price 或 total，這裡會紅。
     */
    @Test
    // 送到 Firestore 的內容完全沒有金額欄位
    fun `intent carries no money fields at all`() {
        val map = intent(
            lines = listOf(
                IntentLine(
                    itemId = "item_beef_noodle",
                    qty = 2,
                    options = listOf(IntentLineOption(groupId = "group_spice", optionId = "option_mild")),
                ),
            ),
        ).toFirestoreMap()

        val forbidden = listOf("price", "unitprice", "subtotal", "total", "amount", "discount", "pricedelta")
        fun scan(value: Any?, path: String) {
            when (value) {
                is Map<*, *> -> value.forEach { (k, v) ->
                    val key = k.toString()
                    if (forbidden.any { key.lowercase().contains(it) }) {
                        throw AssertionError("意圖裡出現金額欄位：$path.$key")
                    }
                    scan(v, "$path.$key")
                }
                is List<*> -> value.forEachIndexed { i, v -> scan(v, "$path[$i]") }
            }
        }
        scan(map, "intent")
    }

    @Test
    // 選項只送 ID，不送名稱與加價
    fun `options carry ids only, no names or price deltas`() {
        val map = intent(
            lines = listOf(
                IntentLine(
                    itemId = "item_beef_noodle",
                    qty = 1,
                    options = listOf(IntentLineOption(groupId = "group_spice", optionId = "option_hot")),
                ),
            ),
        ).toFirestoreMap()

        @Suppress("UNCHECKED_CAST")
        val lines = map["lines"] as List<Map<String, Any?>>
        assertEquals(setOf("itemId", "qty", "options"), lines.single().keys)

        @Suppress("UNCHECKED_CAST")
        val options = lines.single()["options"] as List<Map<String, Any?>>
        assertEquals(setOf("groupId", "optionId"), options.single().keys)
    }

    @Test
    // 訂單型態的字串與伺服器一致
    fun `order type wire names match the server`() {
        assertEquals("dine_in", OrderType.DINE_IN.wireName)
        assertEquals("takeout", OrderType.TAKEOUT.wireName)
        assertEquals("waitlist", OrderType.WAITLIST.wireName)
        assertEquals(OrderType.DINE_IN, OrderType.fromWireName("dine_in"))
        assertThrows(IllegalArgumentException::class.java) { OrderType.fromWireName("dineIn") }
    }

    @Test
    // 進 Room 再出來是同一筆意圖
    fun `round trip through room preserves the intent`() {
        val original = intent(
            lines = listOf(
                IntentLine(
                    itemId = "item_beef_noodle",
                    qty = 3,
                    options = listOf(
                        IntentLineOption(groupId = "group_spice", optionId = "option_hot"),
                        IntentLineOption(groupId = "group_noodle", optionId = "option_thick"),
                    ),
                ),
                IntentLine(itemId = "item_bubble_tea", qty = 1),
            ),
        )
        assertEquals(original, original.toOutboxEntity().toOrderIntent())
    }
}
