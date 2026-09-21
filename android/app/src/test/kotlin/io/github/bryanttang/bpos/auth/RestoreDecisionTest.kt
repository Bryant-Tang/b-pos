package io.github.bryanttang.bpos.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreDecisionTest {

    private val cached = StaffSession.SignedIn(
        uid = "uid_1",
        email = "clerk@example.com",
        storeId = "store_demo",
        role = StaffRole.STAFF,
    )

    @Test
    fun `fresh claims replace the cached session`() {
        val fresh = cached.copy(storeId = "store_other")

        val decision = decideRestore(SignInResult.Success(fresh), cached, cached.uid)

        assertEquals(RestoreDecision.Restore(fresh), decision)
    }

    // 拿得到 token、claims 卻不對 = 老闆把角色收回了。
    @Test
    fun `claims that no longer qualify sign the user out`() {
        val decision = decideRestore(SignInResult.Failure(SignInError.NOT_A_STAFF_ACCOUNT), cached, cached.uid)

        assertEquals(RestoreDecision.SignOut, decision)
    }

    // 這條是 SPEC 第零節第三條：早上開店 Wi-Fi 還沒好的時候，
    // 平板不能因為換不到 token 就把店員踢回登入畫面——他也登不進去。
    @Test
    fun `no token at all keeps the cached session`() {
        val decision = decideRestore(parsed = null, cached = cached, currentUid = cached.uid)

        assertEquals(RestoreDecision.KeepOffline(cached), decision)
    }

    // 離線而且從來沒登入過，就真的只能等網路。
    @Test
    fun `no token and no cache means signed out`() {
        val decision = decideRestore(parsed = null, cached = StaffSession.SignedOut, currentUid = "uid_1")

        assertEquals(RestoreDecision.SignOut, decision)
    }

    // 快取屬於另一個人，代表中間有人登入過但沒收拾乾淨。沿用它會讓畫面顯示
    // 前一個人登入著，送出去的單卻帶著現在這個人的 token。
    @Test
    fun `a cached session belonging to someone else is not reused`() {
        val decision = decideRestore(parsed = null, cached = cached, currentUid = "uid_2")

        assertEquals(RestoreDecision.SignOut, decision)
    }
}
