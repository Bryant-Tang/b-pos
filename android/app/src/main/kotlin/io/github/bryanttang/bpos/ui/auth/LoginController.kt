package io.github.bryanttang.bpos.ui.auth

import io.github.bryanttang.bpos.auth.AuthClient
import io.github.bryanttang.bpos.auth.LoginForm
import io.github.bryanttang.bpos.auth.SignInError
import io.github.bryanttang.bpos.auth.SignInResult
import io.github.bryanttang.bpos.auth.StaffSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 登入畫面現在的樣子。
 *
 * [error] 是上一次送出的結果，不是即時驗證：店員還在打字的時候跳「格式不對」
 * 只會讓人分心，而且那時候他本來就還沒打完。
 */
data class LoginUiState(
    val form: LoginForm = LoginForm(),
    val submitting: Boolean = false,
    val error: SignInError? = null,
) {
    val canSubmit: Boolean get() = form.canSubmit && !submitting
}

/**
 * 登入畫面的狀態機。
 *
 * 獨立成一個不碰 Compose 也不碰 Firebase 的類別，是為了驗這件事：
 * **送出中不能再送一次。** 店裡的 Wi-Fi 慢的時候，按下去沒有反應，
 * 店員會再按一次、再一次。每一次都是一趟 signInWithEmailAndPassword，
 * 連續幾次就會踩到 Firebase 的 ERROR_TOO_MANY_REQUESTS，
 * 接下來幾分鐘連密碼正確都登不進去——而原本只是網路慢而已。
 */
class LoginController(private val authClient: AuthClient) {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = edit { it.copy(email = value) }

    fun onPasswordChange(value: String) = edit { it.copy(password = value) }

    /**
     * 送出登入。成功回傳 session，失敗回 null（原因放在 [state] 裡）。
     */
    suspend fun submit(): StaffSession.SignedIn? {
        val current = _state.value
        if (!current.canSubmit) return null

        _state.value = current.copy(submitting = true, error = null)

        return when (val result = authClient.signIn(current.form.normalizedEmail, current.form.password)) {
            is SignInResult.Success -> {
                _state.update { it.copy(submitting = false) }
                result.session
            }

            is SignInResult.Failure -> {
                _state.update { it.copy(submitting = false, error = result.error) }
                null
            }
        }
    }

    /**
     * 動到任何一格就把錯誤訊息收掉：訊息講的是上一次送出的那組帳密，
     * 內容一改它就不再對應畫面上的東西了。
     */
    private fun edit(change: (LoginForm) -> LoginForm) {
        _state.update { it.copy(form = change(it.form), error = null) }
    }
}
