package io.github.bryanttang.bpos.ui.order

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.ui.AppServices
import io.github.bryanttang.bpos.ui.nav.Screen

/**
 * 訂單明細接上這張桌的訂單監聽。
 *
 * 畫面本身是唯讀的：加點、退點、轉桌、併桌要等各自的伺服器函式做出來。
 * 這裡先接上加點，因為它不需要新函式——加點就是掛同一個 `tableId` 開一張新單
 * （SPEC 第六節〈同桌多單〉），走的是既有的 createOrder 那條路。
 */
@Composable
fun OrderDetailRoute(
    screen: Screen.OrderDetail,
    services: AppServices,
    onAddMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val orders by remember(screen.tableId) { services.tableOrders.observe(screen.tableId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Column(modifier = modifier) {
        OrderDetailScreen(
            tableLabel = screen.tableLabel,
            orders = orders,
            modifier = Modifier.weight(1f),
        )

        Button(
            onClick = onAddMore,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(stringResource(R.string.order_add_more))
        }
    }
}
