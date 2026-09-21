package io.github.bryanttang.bpos.auth

/**
 * 登入、登出、以及 App 開起來時判斷要讓誰進去。
 *
 * 做成介面是為了讓畫面那一層測得到：登入流程有「送出中不能再送一次」
 * 這種只有在慢網路下才會出事的規則，要驗它不需要一個真的 Firebase 專案。
 */
interface AuthClient {

    /**
     * App 開起來時呼叫一次。回傳 [StaffSession.SignedOut] 就顯示登入畫面。
     *
     * 離線時會沿用上次登入記下的 session，理由見 [SessionStore]。
     */
    suspend fun restoreSession(): StaffSession

    suspend fun signIn(email: String, password: String): SignInResult

    /**
     * 登出並清掉記住的 session。
     *
     * **呼叫前要先確認本地佇列已經清空。** 登出會把 storeId 一起丟掉，
     * 而沒送出去的意圖需要它才知道該寫到哪家店底下。這個判斷屬於設定畫面
     * （SPEC 第六節的「設定」那一列），不放在這裡——這裡只負責照做。
     */
    suspend fun signOut()
}
