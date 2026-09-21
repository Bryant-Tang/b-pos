package io.github.bryanttang.bpos.auth

/**
 * 一次登入嘗試的結局。
 *
 * 存在的理由跟 [decideRestore] 一樣：把「失敗之後要收拾什麼」寫成一個只有兩條路的
 * 型別，而不是散在 signIn() 裡的幾個 early return。
 *
 * 漏掉收拾會留下這個狀態：Firebase 的 `currentUser` 已經換成剛剛那個人，
 * 本機快取卻還是上一個人的 session。下次開機時如果又剛好離線，
 * 畫面上會是上一個人登入著，送出去的單卻帶著新那個人的 token——
 * 同一家店的話只是稽核記錄對不起來，不同店的話會被 Rules 擋掉，
 * 而店員完全看不出發生了什麼事。
 */
sealed interface SignInDecision {

    data class Accept(val session: StaffSession.SignedIn) : SignInDecision

    /** 撤掉這次登入：Firebase 端要登出，本機快取要清掉。 */
    data class Abandon(val error: SignInError) : SignInDecision
}

/**
 * @param parsed 這次登入後從 ID token 解出來的結果；`null` 代表**根本沒拿到 token**
 *   （換不到、逾時、離線）。密碼是對的，但我們還是不知道他屬於哪家店。
 */
fun decideSignIn(parsed: SignInResult?): SignInDecision = when (parsed) {
    is SignInResult.Success -> SignInDecision.Accept(parsed.session)
    is SignInResult.Failure -> SignInDecision.Abandon(parsed.error)
    null -> SignInDecision.Abandon(SignInError.NO_NETWORK)
}
