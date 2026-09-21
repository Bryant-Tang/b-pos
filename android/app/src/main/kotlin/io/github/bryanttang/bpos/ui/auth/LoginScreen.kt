package io.github.bryanttang.bpos.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.auth.LoginForm
import io.github.bryanttang.bpos.auth.SignInError
import io.github.bryanttang.bpos.auth.StaffSession
import io.github.bryanttang.bpos.ui.theme.BPosTheme
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    controller: LoginController,
    onSignedIn: (StaffSession.SignedIn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LoginScreenContent(
        state = state,
        onEmailChange = controller::onEmailChange,
        onPasswordChange = controller::onPasswordChange,
        onSubmit = {
            scope.launch { controller.submit()?.let(onSignedIn) }
        },
        modifier = modifier,
    )
}

@Composable
fun LoginScreenContent(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        OutlinedTextField(
            value = state.form.email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.login_email)) },
            singleLine = true,
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth().widthIn(max = FIELD_MAX_WIDTH),
        )

        OutlinedTextField(
            value = state.form.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = !state.submitting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            // 平板接實體鍵盤的店家不少，打完密碼按 Enter 就該送出。
            keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onSubmit() }),
            modifier = Modifier.fillMaxWidth().widthIn(max = FIELD_MAX_WIDTH),
        )

        if (state.error != null) {
            Text(
                text = stringResource(state.error.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.widthIn(min = BUTTON_MIN_WIDTH),
        ) {
            if (state.submitting) {
                // 按鈕本身已經被 canSubmit 關掉了，這個圈圈是給店員看的：
                // 慢網路下沒有它，畫面跟「按了沒反應」長得一模一樣。
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(stringResource(R.string.login_submit))
        }
    }
}

/**
 * 錯誤分類轉成店員看得懂的一句話。
 *
 * 每一句都要講出「接下來做什麼」：重打、看 Wi-Fi、還是找老闆。
 * 只寫「登入失敗」的訊息等於沒寫。
 */
private fun SignInError.messageRes(): Int = when (this) {
    SignInError.WRONG_CREDENTIALS -> R.string.login_error_wrong_credentials
    SignInError.NO_NETWORK -> R.string.login_error_no_network
    SignInError.ACCOUNT_DISABLED -> R.string.login_error_account_disabled
    SignInError.TOO_MANY_ATTEMPTS -> R.string.login_error_too_many_attempts
    SignInError.NOT_A_STAFF_ACCOUNT -> R.string.login_error_not_staff
    SignInError.UNKNOWN -> R.string.login_error_unknown
}

private val FIELD_MAX_WIDTH = 420.dp
private val BUTTON_MIN_WIDTH = 160.dp

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun LoginScreenEmptyPreview() {
    BPosTheme {
        LoginScreenContent(
            state = LoginUiState(),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun LoginScreenErrorPreview() {
    BPosTheme {
        LoginScreenContent(
            state = LoginUiState(
                form = LoginForm(email = "clerk@example.com", password = "wrong"),
                error = SignInError.WRONG_CREDENTIALS,
            ),
            onEmailChange = {},
            onPasswordChange = {},
            onSubmit = {},
        )
    }
}
