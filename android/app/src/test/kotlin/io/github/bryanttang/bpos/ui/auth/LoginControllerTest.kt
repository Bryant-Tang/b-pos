package io.github.bryanttang.bpos.ui.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import io.github.bryanttang.bpos.auth.AuthClient
import io.github.bryanttang.bpos.auth.SignInError
import io.github.bryanttang.bpos.auth.SignInResult
import io.github.bryanttang.bpos.auth.StaffRole
import io.github.bryanttang.bpos.auth.StaffSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginControllerTest {

    private val session = StaffSession.SignedIn(
        uid = "uid_1",
        email = "clerk@example.com",
        storeId = "store_demo",
        role = StaffRole.STAFF,
    )

    /**
     * 假的登入後端。[gate] 沒有完成之前 signIn 會一直卡著，
     * 用來重現「網路很慢、店員盯著沒反應的畫面」那個瞬間。
     */
    private class FakeAuthClient(
        private val result: SignInResult,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : AuthClient {
        var calls = 0
            private set
        var lastEmail: String? = null
            private set
        var lastPassword: String? = null
            private set

        override suspend fun restoreSession(): StaffSession = StaffSession.SignedOut

        override suspend fun signIn(email: String, password: String): SignInResult {
            calls++
            lastEmail = email
            lastPassword = password
            gate?.await()
            return result
        }

        override suspend fun signOut() = Unit
    }

    private fun fill(controller: LoginController, email: String = "clerk@example.com") {
        controller.onEmailChange(email)
        controller.onPasswordChange("pw")
    }

    @Test
    fun `a successful sign in returns the session`() = runTest {
        val controller = LoginController(FakeAuthClient(SignInResult.Success(session)))
        fill(controller)

        assertEquals(session, controller.submit())
        assertFalse(controller.state.value.submitting)
        assertNull(controller.state.value.error)
    }

    @Test
    fun `a failed sign in keeps the reason on screen`() = runTest {
        val client = FakeAuthClient(SignInResult.Failure(SignInError.WRONG_CREDENTIALS))
        val controller = LoginController(client)
        fill(controller)

        assertNull(controller.submit())
        assertEquals(SignInError.WRONG_CREDENTIALS, controller.state.value.error)
        assertFalse(controller.state.value.submitting)
    }

    // 沒填完就不要浪費一趟來回。
    @Test
    fun `an incomplete form never reaches the auth client`() = runTest {
        val client = FakeAuthClient(SignInResult.Success(session))
        val controller = LoginController(client)
        controller.onEmailChange("clerk@example.com")

        assertNull(controller.submit())
        assertEquals(0, client.calls)
    }

    /**
     * 這一條是這個類別存在的理由。
     *
     * 慢網路下按鈕按下去沒反應，店員會再按一次、再一次。每一次都是一趟
     * signInWithEmailAndPassword，連續幾次就會踩到 Firebase 的
     * ERROR_TOO_MANY_REQUESTS，接下來幾分鐘連密碼正確都登不進去。
     */
    @Test
    fun `a second submit while the first is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = FakeAuthClient(SignInResult.Success(session), gate)
        val controller = LoginController(client)
        fill(controller)

        // UNDISPATCHED：讓第一次送出立刻跑到 gate.await() 那個懸停點，
        // 而不是排進 runTest 的排程等著。沒有這個的話這條測試驗的是
        // 「還沒開始送」而不是「送到一半」，兩種情況下第二次都不會送出，
        // 於是 submitting 那個防線壞掉了也測不出來。
        val first = async(start = CoroutineStart.UNDISPATCHED) { controller.submit() }
        // 第一次已經把 submitting 立起來了，這時候的再按一次不該送出去。
        assertTrue(controller.state.value.submitting)
        assertNull(controller.submit())
        assertEquals(1, client.calls)

        gate.complete(Unit)
        assertEquals(session, first.await())
    }

    // 錯誤訊息講的是上一次送出的那組帳密，內容一改它就不再對應畫面上的東西。
    @Test
    fun `editing a field clears the previous error`() = runTest {
        val controller = LoginController(FakeAuthClient(SignInResult.Failure(SignInError.WRONG_CREDENTIALS)))
        fill(controller)
        controller.submit()

        controller.onPasswordChange("pw2")

        assertNull(controller.state.value.error)
    }

    // 空白要在送出前吃掉，不要讓 Firebase 回 ERROR_INVALID_EMAIL
    // 然後在畫面上變成「帳號或密碼不對」。
    @Test
    fun `the email is trimmed before it is sent`() = runTest {
        val client = FakeAuthClient(SignInResult.Success(session))
        val controller = LoginController(client)
        fill(controller, email = "  clerk@example.com  ")

        controller.submit()

        assertEquals("clerk@example.com", client.lastEmail)
    }

    // 密碼不修剪：空白可能真的是密碼的一部分。
    @Test
    fun `the password is sent exactly as typed`() = runTest {
        val client = FakeAuthClient(SignInResult.Success(session))
        val controller = LoginController(client)
        controller.onEmailChange("clerk@example.com")
        controller.onPasswordChange(" pw ")

        controller.submit()

        assertEquals(" pw ", client.lastPassword)
    }
}
