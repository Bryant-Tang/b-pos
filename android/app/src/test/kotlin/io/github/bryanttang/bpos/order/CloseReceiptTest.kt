package io.github.bryanttang.bpos.order

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseReceiptTest {

    private val payload = mapOf(
        "orderId" to "order_1",
        "outcome" to "closed",
        "businessDate" to "2000-01-01",
        "lookupCode" to "0000",
        "total" to 420L,
        "change" to 80L,
    )

    @Test
    fun `reads the server numbers as they are`() {
        val receipt = CloseReceipt.from(payload)

        assertEquals(CloseReceipt(total = 420, change = 80, lookupCode = "0000", replayed = false), receipt)
    }

    /** callable 的回應解成 JSON，整數可能是 Integer、Long 或 Double，看解析器心情。 */
    @Test
    fun `accepts any numeric type`() {
        val receipt = CloseReceipt.from(payload + mapOf("total" to 420, "change" to 80.0))

        assertEquals(420, receipt?.total)
        assertEquals(80, receipt?.change)
    }

    /** 找零退回 0 的話，店員會真的少找客人錢。 */
    @Test
    fun `a missing change is unreadable, not zero`() {
        assertNull(CloseReceipt.from(payload - "change"))
    }

    @Test
    fun `a missing total is unreadable`() {
        assertNull(CloseReceipt.from(payload - "total"))
    }

    @Test
    fun `a replayed result is marked as such`() {
        val receipt = CloseReceipt.from(payload + ("outcome" to "already_applied"))

        assertTrue(receipt!!.replayed)
    }

    /** 查詢碼缺了不影響找零，照樣顯示結果，畫面上那一行不出現就好。 */
    @Test
    fun `a missing lookup code does not block the receipt`() {
        val receipt = CloseReceipt.from(payload - "lookupCode")

        assertEquals("", receipt?.lookupCode)
        assertFalse(receipt!!.replayed)
    }
}
