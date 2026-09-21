package io.github.bryanttang.bpos.auth

/**
 * 登入框裡現在打了什麼。
 *
 * 這裡的檢查只為了一件事：省掉一次註定失敗的來回。真正的驗證在 Firebase，
 * 所以規則刻意寬鬆——email 只看有沒有 `@` 跟左右有沒有東西。
 * 寫得更嚴（完整的 RFC 5322 regex 之類）唯一的效果是某天擋掉一個合法的 email，
 * 而且那時候畫面上會顯示「格式不對」，沒有人猜得到是我們自己擋的。
 */
data class LoginForm(
    val email: String = "",
    val password: String = "",
) {
    /**
     * 去掉前後空白之後的 email。
     *
     * 平板的軟體鍵盤會在自動完成後補一個空格，店員也常把 email 從便條貼上來。
     * 帶著空白送出去，Firebase 回的是 ERROR_INVALID_EMAIL，畫面上變成
     * 「帳號或密碼不對」——店員盯著一個看起來完全正確的 email 反覆重打。
     * 所以這個空白要在送出前就吃掉。密碼不修剪，空白可能真的是密碼的一部分。
     */
    val normalizedEmail: String get() = email.trim()

    val canSubmit: Boolean
        get() = password.isNotEmpty() && normalizedEmail.looksLikeEmail()
}

private fun String.looksLikeEmail(): Boolean {
    val at = indexOf('@')
    return at > 0 && at < length - 1 && !contains(' ')
}
