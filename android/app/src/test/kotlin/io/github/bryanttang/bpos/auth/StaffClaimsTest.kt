package io.github.bryanttang.bpos.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffClaimsTest {

    private fun read(claims: Map<String, Any?>) =
        readStaffSession(uid = "uid_1", email = "clerk@example.com", claims = claims)

    // 正常的店員帳號。
    @Test
    fun `staff claims produce a signed in session`() {
        val result = read(mapOf("storeId" to "store_demo", "role" to "staff"))

        val session = (result as SignInResult.Success).session
        assertEquals("uid_1", session.uid)
        assertEquals("clerk@example.com", session.email)
        assertEquals("store_demo", session.storeId)
        assertEquals(StaffRole.STAFF, session.role)
    }

    // 老闆拿平板時一樣進得來。
    @Test
    fun `owner claims produce a signed in session`() {
        val result = read(mapOf("storeId" to "store_demo", "role" to "owner"))

        assertEquals(StaffRole.OWNER, (result as SignInResult.Success).session.role)
    }

    // setStaffRole 還沒跑過：帳號密碼是對的，但這個帳號還不能用。
    @Test
    fun `claims without a store are not a staff account`() {
        val result = read(mapOf("role" to "staff"))

        assertEquals(SignInError.NOT_A_STAFF_ACCOUNT, (result as SignInResult.Failure).error)
    }

    @Test
    fun `claims without a role are not a staff account`() {
        val result = read(mapOf("storeId" to "store_demo"))

        assertEquals(SignInError.NOT_A_STAFF_ACCOUNT, (result as SignInResult.Failure).error)
    }

    // 空字串的 storeId 會組出 tenants//order_intents 這種路徑，比沒有還糟。
    @Test
    fun `blank store id is rejected`() {
        val result = read(mapOf("storeId" to "   ", "role" to "staff"))

        assertEquals(SignInError.NOT_A_STAFF_ACCOUNT, (result as SignInResult.Failure).error)
    }

    // claim 寫成數字時不要 toString() 硬吞，那會生出對不到文件的 id。
    @Test
    fun `non string store id is rejected`() {
        val result = read(mapOf("storeId" to 12345L, "role" to "staff"))

        assertEquals(SignInError.NOT_A_STAFF_ACCOUNT, (result as SignInResult.Failure).error)
    }

    // 不認得的角色不要猜成 staff：猜錯的方向會是把權限放大。
    @Test
    fun `unknown role is rejected`() {
        val result = read(mapOf("storeId" to "store_demo", "role" to "manager"))

        assertEquals(SignInError.NOT_A_STAFF_ACCOUNT, (result as SignInResult.Failure).error)
    }

    // 匿名帳號沒有 email，但那不是登入失敗的理由。
    @Test
    fun `missing email does not fail the sign in`() {
        val result = readStaffSession(
            uid = "uid_1",
            email = null,
            claims = mapOf("storeId" to "store_demo", "role" to "staff"),
        )

        assertTrue(result is SignInResult.Success)
        assertEquals("", (result as SignInResult.Success).session.email)
    }
}
