package io.github.bryanttang.bpos.ui.pending

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bryanttang.bpos.order.OpenOrder
import io.github.bryanttang.bpos.ui.AppServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 待確認畫面接上 `confirmGuestOrder`。
 *
 * [orders] 從外面傳進來，這裡不自己訂閱：桌位總覽本來就一直在聽同一批單
 * （見 [io.github.bryanttang.bpos.ui.tables.TablesController]），再開一條就是同一份資料
 * 付兩次讀取。附帶的好處是這一頁開著的時候，客人新送出來的單會自己長出來。
 *
 * 控制器綁在 [services] 上而不是每次進畫面重建：店員按了確認、網路不好、退回總覽
 * 再進來——那張單的 `requestId` 要還是同一個，不然重送會被伺服器當成另一次確認，
 * 廚房收到兩張（functions/src/orders/confirmGuestOrderInput.ts）。
 */
@Composable
fun PendingConfirmRoute(
    orders: List<OpenOrder>,
    services: AppServices,
    modifier: Modifier = Modifier,
) {
    val controller = remember(services) {
        PendingConfirmController(sendConfirm = services.staffFunctions::confirmGuestOrder)
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        PendingConfirmScreen(
            orders = orders,
            confirming = state.confirming,
            confirmed = state.confirmed,
            onConfirm = { orderId -> scope.launch { controller.confirm(orderId) } },
            modifier = Modifier.weight(1f),
        )

        state.message?.let { message ->
            // 自己收掉，不要留一行紅字在畫面上整個下午（理由同 OrderRoute）。
            LaunchedEffect(message) {
                delay(MESSAGE_VISIBLE_MILLIS)
                controller.dismissMessage()
            }
            Snackbar(modifier = Modifier.padding(16.dp)) { Text(message) }
        }
    }
}

/** 訊息顯示多久。夠一眼看完，又不會擋著下一張單。 */
private const val MESSAGE_VISIBLE_MILLIS = 4_000L
