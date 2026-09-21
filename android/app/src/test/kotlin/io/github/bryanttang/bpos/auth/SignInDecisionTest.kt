package io.github.bryanttang.bpos.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class SignInDecisionTest {

    private val session = StaffSession.SignedIn(
        uid = "uid_1",
        email = "clerk@example.com",
        storeId = "store_demo",
        role = StaffRole.STAFF,
    )

    @Test
    fun `valid claims accept the sign in`() {
        assertEquals(SignInDecision.Accept(session), decideSignIn(SignInResult.Success(session)))
    }

    @Test
    fun `claims that do not qualify abandon the sign in`() {
        val decision = decideSignIn(SignInResult.Failure(SignInError.NOT_A_STAFF_ACCOUNT))

        assertEquals(SignInDecision.Abandon(SignInError.NOT_A_STAFF_ACCOUNT), decision)
    }

    /**
     * 網路斷在「密碼驗過了」與「換到新 token」之間。
     *
     * 這條路很容易被寫成直接回傳錯誤就算了——但那樣 Firebase 端已經換成這個人，
     * 本機快取卻還是上一個人的。下次離線開機時畫面會顯示上一個人登入著，
     * 送出去的單卻帶著新那個人的 token。所以這裡也要 Abandon。
     */
    @Test
    fun `no token at all abandons the sign in instead of just reporting it`() {
        assertEquals(SignInDecision.Abandon(SignInError.NO_NETWORK), decideSignIn(null))
    }
}
