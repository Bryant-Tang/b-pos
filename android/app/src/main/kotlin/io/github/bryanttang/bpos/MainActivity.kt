package io.github.bryanttang.bpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.sync.SyncStatus
import io.github.bryanttang.bpos.ui.theme.BPosTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val syncStatus = BposDatabase.get(this)
            .orderIntentOutboxDao()
            .observePendingCount()
            .map { SyncStatus.of(it) }

        setContent {
            BPosTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    StatusScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        syncStatus = syncStatus,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(
    versionName: String,
    syncStatus: Flow<SyncStatus>,
    modifier: Modifier = Modifier,
) {
    val status by syncStatus.collectAsStateWithLifecycle(initialValue = SyncStatus.UpToDate)
    StatusScreenContent(versionName = versionName, status = status, modifier = modifier)
}

/**
 * 目前顯示兩件事：這台平板裝的是哪一版，以及還有幾張單沒送上去。
 *
 * 版本是因為平板是 kiosk 模式整天不關機，出問題時第一個要問的就是
 * 「它到底裝了哪一版」，而 versionName 帶著 commit sha（見 app/build.gradle.kts）。
 *
 * 同步狀態是 SPEC 第六節要求常駐顯示的：店員不會去翻設定頁確認單送出去了沒有，
 * 斷網時如果畫面上沒有任何提示，一整個下午都不會有人發現。
 */
@Composable
fun StatusScreenContent(
    versionName: String,
    status: SyncStatus,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "b-pos", style = MaterialTheme.typography.displaySmall)
        Text(text = versionName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = when (status) {
                SyncStatus.UpToDate -> stringResource(R.string.sync_up_to_date)
                is SyncStatus.Pending -> stringResource(R.string.sync_pending, status.count)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun StatusScreenUpToDatePreview() {
    BPosTheme {
        StatusScreenContent(versionName = "0.12+abc1234", status = SyncStatus.UpToDate)
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun StatusScreenPendingPreview() {
    BPosTheme {
        StatusScreenContent(versionName = "0.12+abc1234", status = SyncStatus.Pending(3))
    }
}
