package io.github.bryanttang.bpos.remote

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import com.google.firebase.functions.FirebaseFunctionsException.Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 要跑 Robolectric 是因為 [Code] 的 static 初始化會碰到 Android 的類別，
 * 純 JVM 測試載不起來（ExceptionInInitializerError）。分類邏輯本身沒碰 Android。
 */
@RunWith(RobolectricTestRunner::class)
class CallableErrorsTest {

    /**
     * 這幾個錯誤碼一律是「再按一次有用」。分錯邊的代價不對稱：把暫時性錯誤標成永久的，
     * 店員會放棄那張單（其實再按就好）；反過來則是按到放棄。
     */
    @Test
    fun `transient codes are retryable`() {
        val transient = listOf(
            Code.UNAVAILABLE,
            Code.DEADLINE_EXCEEDED,
            Code.ABORTED,
            Code.RESOURCE_EXHAUSTED,
            Code.INTERNAL,
            Code.CANCELLED,
            Code.UNKNOWN,
        )

        for (code in transient) {
            assertTrue("$code 應該是可重試的", actionResultFor(code, null) is ActionResult.Retryable)
        }
    }

    @Test
    fun `state and permission codes are refused`() {
        val permanent = listOf(
            Code.NOT_FOUND,
            Code.FAILED_PRECONDITION,
            Code.PERMISSION_DENIED,
            Code.UNAUTHENTICATED,
            Code.INVALID_ARGUMENT,
        )

        for (code in permanent) {
            assertTrue("$code 不應該叫店員再按一次", actionResultFor(code, null) is ActionResult.Refused)
        }
    }

    /**
     * 伺服器那邊的訊息是寫給店員看的（confirmGuestOrder.ts 的 notConfirmableMessage），
     * 有就直接用，比翻成「操作失敗」有用得多。
     */
    @Test
    fun `the server message is shown as is`() {
        val result = actionResultFor(Code.FAILED_PRECONDITION, "這張單已經結帳了，不用再確認")

        assertEquals("這張單已經結帳了，不用再確認", (result as ActionResult.Refused).message)
    }

    @Test
    fun `a blank server message falls back to one that names the next step`() {
        val refused = actionResultFor(Code.NOT_FOUND, "   ") as ActionResult.Refused
        val retryable = actionResultFor(Code.UNAVAILABLE, null) as ActionResult.Retryable

        assertTrue(refused.message.isNotBlank())
        assertTrue(retryable.message.isNotBlank())
    }

    /** 送不出去的請求根本沒到伺服器，重送不可能造成第二次副作用。 */
    @Test
    fun `a network failure is retryable`() {
        assertTrue(networkFailureResult() is ActionResult.Retryable)
    }
}
