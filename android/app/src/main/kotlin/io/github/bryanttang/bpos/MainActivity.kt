package io.github.bryanttang.bpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import io.github.bryanttang.bpos.auth.AuthClient
import io.github.bryanttang.bpos.auth.FirebaseAuthClient
import io.github.bryanttang.bpos.auth.SharedPreferencesSessionStore
import io.github.bryanttang.bpos.auth.StaffSession
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.firebase.ensureFirebaseApp
import io.github.bryanttang.bpos.sync.SyncStatus
import io.github.bryanttang.bpos.ui.BposApp
import io.github.bryanttang.bpos.ui.appServices
import io.github.bryanttang.bpos.ui.auth.LoginController
import io.github.bryanttang.bpos.ui.auth.LoginScreen
import io.github.bryanttang.bpos.ui.theme.BPosTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val syncStatus = BposDatabase.get(this)
            .orderIntentOutboxDao()
            .observePendingCount()
            .map { SyncStatus.of(it) }

        // Application 開機時已經初始化過，這裡再叫一次是為了拿到「這台平板到底有沒有
        // 設定」這個答案（冪等，不會重複初始化）。沒有設定就不能走進 AppGate——
        // 那裡第一件事就是問 FirebaseAuth，而沒初始化時它丟的是 IllegalStateException，
        // 店員看到的會是「一打開就閃退」。
        val firebaseReady = ensureFirebaseApp(this, appFirebaseConfig())

        // getInstance() 包在 lambda 裡延後呼叫，理由見 FirebaseAuthClient 的建構子註解。
        val authClient = FirebaseAuthClient(
            authProvider = { FirebaseAuth.getInstance() },
            store = SharedPreferencesSessionStore(this),
        )

        setContent {
            BPosTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    if (firebaseReady) {
                        AppGate(
                            authClient = authClient,
                            versionName = BuildConfig.VERSION_NAME,
                            syncStatus = syncStatus,
                            modifier = Modifier.padding(padding),
                        )
                    } else {
                        NotConfiguredScreen(
                            versionName = BuildConfig.VERSION_NAME,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 決定這台平板現在要顯示登入畫面還是主畫面。
 *
 * 判斷結果在拿到之前是 null，這段期間顯示一行「確認登入狀態…」。
 * 不要預設成「未登入」再改掉：離線開機時 [AuthClient.restoreSession] 要等
 * token 逾時才回得來，先閃一下登入畫面又自己跳走，店員會以為剛剛被登出了。
 */
@Composable
private fun AppGate(
    authClient: AuthClient,
    versionName: String,
    syncStatus: Flow<SyncStatus>,
    modifier: Modifier = Modifier,
) {
    var session by remember { mutableStateOf<StaffSession?>(null) }
    val controller = remember(authClient) { LoginController(authClient) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(authClient) { session = authClient.restoreSession() }

    when (val current = session) {
        null -> CheckingScreen(modifier)

        StaffSession.SignedOut -> LoginScreen(
            controller = controller,
            onSignedIn = { session = it },
            modifier = modifier,
        )

        is StaffSession.SignedIn -> {
            // 這家店會用到的東西在登入之後才組得出來（storeId 來自 custom claim），
            // 而且綁在 storeId 上：換帳號換店時整包重組，上一家店的監聽不會殘留。
            val context = LocalContext.current
            val services = remember(current.storeId) { appServices(context, current.storeId) }

            BposApp(
                session = current,
                services = services,
                syncStatus = syncStatus,
                versionName = versionName,
                onSignOut = {
                    scope.launch {
                        authClient.signOut()
                        session = StaffSession.SignedOut
                    }
                },
                modifier = modifier,
            )
        }
    }
}

/**
 * 這包 App 沒有帶到 Firebase 專案設定時顯示的畫面。
 *
 * 會看到這一頁的通常是自己建置出來的 APK（本機或 PR 的 CI 建置刻意不帶真實設定值）。
 * 寫清楚「這包的問題，不是平板的問題」，才不會有人跑去重設 Wi-Fi 或重灌平板。
 * 版本號一起顯示，是為了回報時講得出手上這包是哪一版。
 */
@Composable
fun NotConfiguredScreen(versionName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.firebase_not_configured_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.firebase_not_configured_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(text = versionName, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CheckingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.login_checking),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun NotConfiguredPreview() {
    BPosTheme { NotConfiguredScreen(versionName = "0.12+abc1234") }
}
