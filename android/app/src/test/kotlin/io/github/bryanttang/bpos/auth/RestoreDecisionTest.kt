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

        val decision = decideRestore(SignInResult.Success(fresh), cached)

        assertEquals(RestoreDecision.Restore(fresh), decision)
    }

    // 拿得到 token、claims 卻不對 = 老闆把角色收回了。
    @Test
    fun `claims that no longer qualify sign the user out`() {
        val decision = decideRestore(SignInResult.Failure(SignInError.NOT_A_STAFF_ACCOUNT), cached)

        assertEquals(RestoreDecision.SignOut, decision)
    }

    // 這條是 SPEC 第零節第三條：早上開店 Wi-Fi 還沒好的時候，
    // 平板不能因為換不到 token 就把店員踢回登入畫面——他也登不進去。
    @Test
    fun `no token at all keeps the cached session`() {
        val decision = decideRestore(parsed = null, cached = cached)

        assertEquals(RestoreDecision.KeepOffline(cached), decision)
    }

    // 離線而且從來沒登入過，就真的只能等網路。
    @Test
    fun `no token and no cache means signed out`() {
        val decision = decideRestore(parsed = null, cached = StaffSession.SignedOut)

        assertEquals(RestoreDecision.SignOut, decision)
    }
}
