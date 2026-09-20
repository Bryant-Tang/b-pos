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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.ui.theme.BPosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BPosTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    BuildInfo(
                        versionName = BuildConfig.VERSION_NAME,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

/**
 * 目前只顯示這台平板裝的是哪一版。
 *
 * 平板是 kiosk 模式、整天不關機，出問題時第一個要問的就是「它到底裝了哪一版」，
 * 而 versionName 帶著 commit sha（見 app/build.gradle.kts），看畫面就答得出來。
 */
@Composable
fun BuildInfo(versionName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "b-pos", style = MaterialTheme.typography.displaySmall)
        Text(text = versionName, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun BuildInfoPreview() {
    BPosTheme {
        BuildInfo(versionName = "0.12+abc1234")
    }
}
