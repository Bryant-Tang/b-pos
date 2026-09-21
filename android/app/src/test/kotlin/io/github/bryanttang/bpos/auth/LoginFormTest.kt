package io.github.bryanttang.bpos.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginFormTest {

    @Test
    fun `a filled in form can be submitted`() {
        assertTrue(LoginForm(email = "clerk@example.com", password = "pw").canSubmit)
    }

    // 軟體鍵盤自動完成後會補一個空格，貼上來的 email 也常帶著空白。
    // 不修掉的話 Firebase 回 ERROR_INVALID_EMAIL，畫面上變成「帳號或密碼不對」，
    // 店員會盯著一個看起來完全正確的 email 反覆重打。
    @Test
    fun `surrounding whitespace is trimmed from the email`() {
        val form = LoginForm(email = "  clerk@example.com\n", password = "pw")

        assertEquals("clerk@example.com", form.normalizedEmail)
        assertTrue(form.canSubmit)
    }

    // 密碼不修剪：空白可能真的是密碼的一部分。
    @Test
    fun `the password is left exactly as typed`() {
        val form = LoginForm(email = "clerk@example.com", password = " pw ")

        assertTrue(form.canSubmit)
        assertEquals(" pw ", form.password)
    }

    @Test
    fun `an empty password blocks submitting`() {
        assertFalse(LoginForm(email = "clerk@example.com", password = "").canSubmit)
    }

    @Test
    fun `an address without an at sign blocks submitting`() {
        assertFalse(LoginForm(email = "clerk", password = "pw").canSubmit)
    }

    @Test
    fun `an at sign needs something on both sides`() {
        assertFalse(LoginForm(email = "@example.com", password = "pw").canSubmit)
        assertFalse(LoginForm(email = "clerk@", password = "pw").canSubmit)
    }

    // 規則刻意寬鬆：真正的驗證在 Firebase，這裡擋掉的只是註定失敗的來回。
    // 寫得更嚴唯一的效果是某天擋掉一個合法的 email。
    @Test
    fun `unusual but plausible addresses are allowed through`() {
        assertTrue(LoginForm(email = "a+b.c@sub.example.co", password = "pw").canSubmit)
    }
}
