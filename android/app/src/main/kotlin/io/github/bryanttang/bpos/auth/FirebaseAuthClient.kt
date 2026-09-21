package io.github.bryanttang.bpos.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * 用 Firebase Auth 的 email/密碼登入。
 *
 * 平板不該每天重新登入（SPEC 第六節），所以登入狀態由 Firebase SDK 自己
 * 存在裝置上，我們只額外記下 claims 裡的 storeId 與角色（見 [SessionStore]）。
 */
class FirebaseAuthClient(
    /**
     * 跟 FirestoreOrderIntentSender 一樣收 provider 而不是 FirebaseAuth 本身：
     * `FirebaseAuth.getInstance()` 在 Firebase 還沒設定好時會丟例外，
     * 在建構子就取的話，那個例外會發生在畫面還沒畫出來的時候，
     * 店員看到的是閃退而不是一則錯誤訊息。
     */
    private val authProvider: () -> FirebaseAuth,
    private val store: SessionStore,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : AuthClient {

    constructor(
        auth: FirebaseAuth,
        store: SessionStore,
        timeout: Duration = DEFAULT_TIMEOUT,
    ) : this({ auth }, store, timeout)

    override suspend fun restoreSession(): StaffSession {
        val auth = authProvider()
        val user = auth.currentUser
        if (user == null) {
            // Firebase 說沒人登入，那快取裡那份就是殘留物。
            store.clear()
            return StaffSession.SignedOut
        }

        // false = 允許用快取的 token。離線開機時這是唯一拿得到 claims 的方式；
        // token 過期而且換不到新的時候它會失敗，那正是下面 null 的那條路。
        val parsed = tokenClaims(forceRefresh = false)
            ?.let { readStaffSession(user.uid, user.email, it) }

        return when (val decision = decideRestore(parsed, store.read())) {
            is RestoreDecision.Restore -> decision.session.also { store.write(it) }
            is RestoreDecision.KeepOffline -> decision.session
            RestoreDecision.SignOut -> {
                auth.signOut()
                store.clear()
                StaffSession.SignedOut
            }
        }
    }

    override suspend fun signIn(email: String, password: String): SignInResult {
        val auth = authProvider()

        val user = try {
            withTimeout(timeout.toMillis()) {
                auth.signInWithEmailAndPassword(email, password).await()
            }.user
        } catch (e: Throwable) {
            return SignInResult.Failure(errorOf(e))
        } ?: return SignInResult.Failure(SignInError.UNKNOWN)

        // true = 強制跟伺服器換一份新的。老闆很可能是剛剛才幫這個帳號跑
        // setStaffRole，SDK 手上那份 token 還是沒有 claims 的舊版本;
        // 不強制換的話，第一次登入一定會被判成「這個帳號還不能用」。
        val claims = tokenClaims(forceRefresh = true)
            ?: return SignInResult.Failure(SignInError.NO_NETWORK)

        return when (val parsed = readStaffSession(user.uid, user.email, claims)) {
            is SignInResult.Success -> parsed.also { store.write(it.session) }
            is SignInResult.Failure -> {
                // 不要留下「Firebase 認得你、但這台平板不知道你是哪家店」的半吊子狀態。
                // 那會讓下次開機時 currentUser 不是 null，卻什麼都做不了。
                auth.signOut()
                store.clear()
                parsed
            }
        }
    }

    override suspend fun signOut() {
        // 先清本地。取 FirebaseAuth 這一步有可能丟例外（Firebase 沒設定好），
        // 真發生時至少這台平板不會停在一個「登出按了沒反應」的畫面。
        store.clear()
        runCatching { authProvider().signOut() }
    }

    /** 拿目前這個使用者的 claims；拿不到就回 null，呼叫端自己決定那代表什麼。 */
    private suspend fun tokenClaims(forceRefresh: Boolean): Map<String, Any>? {
        val user = authProvider().currentUser ?: return null

        return try {
            withTimeout(timeout.toMillis()) {
                user.getIdToken(forceRefresh).await()
            }.claims
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            // 取消不是「拿不到 token」，是外面那支 coroutine 不要這個結果了。
            // 吃掉它會違反協作式取消的慣例，理由同 OutboxWorker.flushOrNull()。
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun errorOf(e: Throwable): SignInError = when (e) {
        // 逾時在這裡一律當成連不上。Firebase Auth 卡這麼久不會是密碼打錯，
        // 是封包出不去——店裡的 Wi-Fi 連著但沒有對外，就是這個樣子。
        is TimeoutCancellationException -> SignInError.NO_NETWORK
        is CancellationException -> throw e
        is FirebaseNetworkException -> SignInError.NO_NETWORK
        is FirebaseAuthException -> SignInError.ofCode(e.errorCode)
        else -> SignInError.UNKNOWN
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
