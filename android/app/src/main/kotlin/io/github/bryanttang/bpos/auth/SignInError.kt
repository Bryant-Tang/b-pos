package io.github.bryanttang.bpos.auth

/**
 * 登入失敗的原因，分到「店員看得懂而且知道下一步該做什麼」為止。
 *
 * 分類的標準不是 Firebase 回了什麼碼，是**店員接下來該做什麼**：
 * 重打一次、等一下、還是去找老闆。分得比這細只會讓畫面上多出
 * 一堆看不懂又幫不上忙的字。
 */
enum class SignInError {
    /** 帳號或密碼不對。重打一次。 */
    WRONG_CREDENTIALS,

    /** 連不到 Firebase。看 Wi-Fi。 */
    NO_NETWORK,

    /** 帳號被停用。找老闆。 */
    ACCOUNT_DISABLED,

    /** 太多次失敗被暫時擋住。等一下再試。 */
    TOO_MANY_ATTEMPTS,

    /** 登入成功了，但這個帳號還沒有被指派店家與角色。找老闆。 */
    NOT_A_STAFF_ACCOUNT,

    /** 其他。 */
    UNKNOWN,
    ;

    companion object {
        /**
         * 把 `FirebaseAuthException.errorCode` 轉成上面的分類。
         *
         * **不要因為拿到 ERROR_USER_NOT_FOUND 就跟店員說「這個帳號不存在」。**
         * Firebase 現在預設開著 email enumeration protection，帳號不存在與密碼錯誤
         * 都回同一個 ERROR_INVALID_CREDENTIAL，就是為了不讓外人用登入框問出
         * 哪些 email 有註冊。我們把這三個碼收斂成同一句「帳號或密碼不對」，
         * 是配合這個設計，不是偷懶——分開講反而是把那道保護拆掉。
         */
        fun ofCode(code: String?): SignInError = when (code) {
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_WRONG_PASSWORD",
            "ERROR_USER_NOT_FOUND",
            "ERROR_INVALID_EMAIL",
            -> WRONG_CREDENTIALS

            "ERROR_USER_DISABLED" -> ACCOUNT_DISABLED
            "ERROR_TOO_MANY_REQUESTS" -> TOO_MANY_ATTEMPTS
            else -> UNKNOWN
        }
    }
}
