package io.github.bryanttang.bpos.auth

/**
 * App 開起來時，要讓誰進去。
 *
 * 這件事本身不難，難的是「讀不到 claims」有兩種完全相反的意思：
 * 可能是離線（應該放行，用上次記下的），也可能是權限被收回（應該踢出去）。
 * 分辨的依據是**有沒有拿到 token**，不是 claims 的內容：
 *
 * - 拿到 token、claims 也對 → 用新的，順便更新快取
 * - 拿到 token、claims 不對 → 老闆把這個人的角色收回了，登出
 * - 拿不到 token → 多半是離線，沿用快取，什麼都不要動
 *
 * 抽成一個純函式是為了讓這三條分支測得到：包在 FirebaseAuthClient 裡的話，
 * 要驗「離線時不會被踢出去」就得先有一個真的 FirebaseAuth。
 */
sealed interface RestoreDecision {

    /** 用這個 session，並把它寫回快取。 */
    data class Restore(val session: StaffSession.SignedIn) : RestoreDecision

    /** 拿不到 token，沿用快取裡的 session，不要寫回也不要登出。 */
    data class KeepOffline(val session: StaffSession.SignedIn) : RestoreDecision

    /** 回登入畫面，並清掉快取。 */
    data object SignOut : RestoreDecision
}

/**
 * @param parsed 這次從 ID token 解出來的結果；`null` 代表**根本沒拿到 token**
 *   （換不到、逾時、離線），不是「拿到了但內容不對」。
 * @param cached 上次登入時記下來的 session。
 * @param currentUid Firebase 現在認的那個人。快取要跟他對得起來才能沿用——
 *   對不起來代表中間有人登入過但沒收拾乾淨（見 [SignInDecision.Abandon]），
 *   那份快取屬於另一個人，沿用它會讓畫面顯示 A 登入著、送出去的單卻帶著 B 的 token。
 */
fun decideRestore(
    parsed: SignInResult?,
    cached: StaffSession,
    currentUid: String,
): RestoreDecision = when (parsed) {
    is SignInResult.Success -> RestoreDecision.Restore(parsed.session)
    is SignInResult.Failure -> RestoreDecision.SignOut
    null -> if (cached is StaffSession.SignedIn && cached.uid == currentUid) {
        RestoreDecision.KeepOffline(cached)
    } else {
        RestoreDecision.SignOut
    }
}
