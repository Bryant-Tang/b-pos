package io.github.bryanttang.bpos.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class SignInErrorTest {

    // 開著 email enumeration protection 時 Firebase 回的就是這個碼，
    // 帳號不存在與密碼錯誤都一樣。
    @Test
    fun `invalid credential maps to wrong credentials`() {
        assertEquals(SignInError.WRONG_CREDENTIALS, SignInError.ofCode("ERROR_INVALID_CREDENTIAL"))
    }

    // 舊專案還是會回這兩個碼，必須收斂到同一句話，
    // 否則登入框會變成「哪些 email 有註冊」的查詢介面。
    @Test
    fun `legacy credential codes map to the same message`() {
        assertEquals(SignInError.WRONG_CREDENTIALS, SignInError.ofCode("ERROR_WRONG_PASSWORD"))
        assertEquals(SignInError.WRONG_CREDENTIALS, SignInError.ofCode("ERROR_USER_NOT_FOUND"))
    }

    // 格式不對也是「重打一次」，不需要另外一句話。
    @Test
    fun `invalid email maps to wrong credentials`() {
        assertEquals(SignInError.WRONG_CREDENTIALS, SignInError.ofCode("ERROR_INVALID_EMAIL"))
    }

    // 這兩個要分開講，因為店員重打幾次都沒有用。
    @Test
    fun `disabled and throttled accounts keep their own reason`() {
        assertEquals(SignInError.ACCOUNT_DISABLED, SignInError.ofCode("ERROR_USER_DISABLED"))
        assertEquals(SignInError.TOO_MANY_ATTEMPTS, SignInError.ofCode("ERROR_TOO_MANY_REQUESTS"))
    }

    @Test
    fun `unrecognised and missing codes fall back to unknown`() {
        assertEquals(SignInError.UNKNOWN, SignInError.ofCode("ERROR_SOMETHING_NEW"))
        assertEquals(SignInError.UNKNOWN, SignInError.ofCode(null))
    }
}
