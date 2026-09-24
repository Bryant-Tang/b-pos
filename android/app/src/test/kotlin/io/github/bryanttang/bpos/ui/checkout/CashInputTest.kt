package io.github.bryanttang.bpos.ui.checkout

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class CashInputTest {

    @Test
    fun `an empty field is not ready`() {
        assertEquals(CashInput.Empty, parseCashInput("", total = 420))
    }

    @Test
    fun `less than the total is refused with the total named`() {
        assertEquals(CashInput.TooLittle(420), parseCashInput("400", total = 420))
    }

    /** 剛好付清是最常見的情況，不能被「必須大於」這種寫法擋掉。 */
    @Test
    fun `exactly the total is fine`() {
        assertEquals(CashInput.Ok(420), parseCashInput("420", total = 420))
    }

    @Test
    fun `more than the total is fine`() {
        assertEquals(CashInput.Ok(500), parseCashInput("500", total = 420))
    }

    @Test
    fun `the field keeps only digits`() {
        assertEquals("1000", digitsOnly("1,000元"))
    }

    @Test
    fun `the field is capped at six digits`() {
        assertEquals("123456", digitsOnly("1234567"))
    }
}
