package io.github.bryanttang.bpos.ui.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.auth.StaffSession
import io.github.bryanttang.bpos.ui.AppServices
import io.github.bryanttang.bpos.ui.nav.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 點餐畫面接上菜單與離線佇列。
 *
 * 控制器綁在這個 [Screen.Order] 上（`remember(screen)`）：同一張單從第一個品項按下去
 * 到送出，用的是同一組 intentId/orderId，連按兩次送出在佇列裡只會是一筆。
 * 退回總覽再進來是新的一張單，那時候才換一組新的 id。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderRoute(
    screen: Screen.Order,
    session: StaffSession.SignedIn,
    services: AppServices,
    onSubmitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember(screen) {
        OrderController(
            fetchMenu = services.menuRepository::load,
            submitter = services.submitter,
            tableId = screen.tableId,
            orderType = screen.orderType,
        )
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(controller) { controller.loadMenu(session.storeId) }

    val menu = state.menu
    when {
        state.loading -> Centered(stringResource(R.string.menu_loading), spinning = true, modifier = modifier)

        // 空菜單不能直接畫出來：那看起來像「這間店今天什麼都沒有」，
        // 店員會以為是老闆把品項全下架了（見 MenuLoad.Unavailable）。
        // 這裡一定要給一條路回去，不然店員就卡在這一頁了：退回總覽再進來雖然也會
        // 重讀，但畫面上沒寫，沒有人會想到。
        menu == null -> Unavailable(
            onRetry = { scope.launch { controller.loadMenu(session.storeId) } },
            modifier = modifier,
        )

        else -> Column(modifier = modifier) {
            if (state.menuFromCache) {
                Text(
                    text = stringResource(R.string.menu_from_cache),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            OrderScreen(
                menu = menu,
                selectedCategoryId = state.selectedCategoryId,
                cart = state.cart,
                orderType = screen.orderType,
                onCategorySelect = controller::onCategorySelect,
                onItemClick = controller::onItemClick,
                onQtyChange = controller::onQtyChange,
                onSubmit = {
                    scope.launch { if (controller.submit(session.uid)) onSubmitted() }
                },
                modifier = Modifier.weight(1f),
            )

            state.pendingOptions?.let { pending ->
                ModalBottomSheet(
                    onDismissRequest = controller::onOptionsDismiss,
                    sheetState = sheetState,
                ) {
                    OptionSheetContent(
                        menu = menu,
                        item = pending.item,
                        selection = pending.selection,
                        onSelectionChange = controller::onSelectionChange,
                        onConfirm = controller::onOptionsConfirm,
                    )
                }
            }

            state.message?.let { message ->
                // 自己收掉，不要留一行紅字在畫面上整個下午。訊息講的是剛剛那個動作
                // （車滿了、購物車是空的），下一個動作一發生它就不再對應任何東西。
                LaunchedEffect(message) {
                    delay(MESSAGE_VISIBLE_MILLIS)
                    controller.dismissMessage()
                }
                Snackbar(modifier = Modifier.padding(16.dp)) { Text(message) }
            }
        }
    }
}

@Composable
private fun Unavailable(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.menu_unavailable),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.menu_retry)) }
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

/** 訊息顯示多久。夠一眼看完，又不會擋著下一個動作。 */
private const val MESSAGE_VISIBLE_MILLIS = 4_000L
