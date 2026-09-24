package io.github.bryanttang.bpos.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.order.OpenOrder
import io.github.bryanttang.bpos.remote.PaymentMethod
import io.github.bryanttang.bpos.ui.AppServices
import io.github.bryanttang.bpos.ui.nav.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 結帳畫面接上這張單的即時內容與 `closeOrder`。
 *
 * 單的內容用監聽而不是從明細頁帶進來：店員在結帳畫面上的時候，另一台平板可能剛好
 * 退了一份。畫面上的合計要跟伺服器等一下鎖住的那個數字一致，帶進來的快照做不到。
 *
 * 顯示的優先順序是「結果 → 單 → 找不到」：結好之後這張單就搬進 archive、從監聽裡消失了，
 * 那時候要看的是結果，不是「這張單不見了」。
 */
@Composable
fun CheckoutRoute(
    screen: Screen.Checkout,
    services: AppServices,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = services.checkout
    val state by controller.state.collectAsStateWithLifecycle()
    // null 代表第一份快照還沒到。跟「這張桌沒有單」分開，否則一進畫面會先閃一下「找不到這張單」。
    val orders by remember(screen.tableId) {
        services.tableOrders.observe(screen.tableId).map<List<OpenOrder>, List<OpenOrder>?> { it }
    }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    // 表單的輸入跟著畫面走，離開就丟：那只是店員打到一半的東西。
    // 冪等鍵與結果才是要活過導覽的，那兩個在 controller 裡。
    var method by remember(screen) { mutableStateOf(PaymentMethod.CASH) }
    var receivedText by remember(screen) { mutableStateOf("") }

    val result = state.results[screen.orderId]
    val currentOrders = orders
    val order = currentOrders?.firstOrNull { it.orderId == screen.orderId }

    Column(modifier = modifier) {
        when {
            result != null -> CheckoutReceiptView(
                result = result,
                onDone = {
                    controller.finish(screen.orderId)
                    onDone()
                },
                modifier = Modifier.weight(1f),
            )

            currentOrders == null -> Centered(
                text = stringResource(R.string.checkout_loading),
                spinning = true,
                modifier = Modifier.weight(1f),
            )

            order == null -> Centered(
                text = stringResource(R.string.checkout_order_gone),
                spinning = false,
                modifier = Modifier.weight(1f),
            )

            else -> CheckoutForm(
                order = order,
                method = method,
                receivedText = receivedText,
                closing = screen.orderId in state.closing,
                onMethodChange = { method = it },
                onReceivedChange = { receivedText = it },
                onClose = {
                    val received = (parseCashInput(receivedText, order.total) as? CashInput.Ok)?.received
                    scope.launch { controller.close(screen.orderId, method, received) }
                },
                modifier = Modifier.weight(1f),
            )
        }

        state.message?.let { message ->
            // 自己收掉，理由同 OrderRoute。
            LaunchedEffect(message) {
                delay(MESSAGE_VISIBLE_MILLIS)
                controller.dismissMessage()
            }
            Snackbar(modifier = Modifier.padding(16.dp)) { Text(message) }
        }
    }
}

@Composable
private fun Centered(text: String, spinning: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (spinning) CircularProgressIndicator()
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 訊息顯示多久。比其他畫面長一點：結帳失敗的訊息常常帶著金額
 * （「收到的金額不足，這張單是 420 元」），要給店員時間看清楚。
 */
private const val MESSAGE_VISIBLE_MILLIS = 6_000L
