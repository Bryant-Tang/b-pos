package io.github.bryanttang.bpos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.auth.StaffSession
import io.github.bryanttang.bpos.sync.SyncStatus
import io.github.bryanttang.bpos.ui.nav.NavStack
import io.github.bryanttang.bpos.ui.nav.Screen
import io.github.bryanttang.bpos.ui.nav.addMoreFor
import io.github.bryanttang.bpos.ui.nav.orderDetailFor
import io.github.bryanttang.bpos.ui.nav.orderScreenFor
import io.github.bryanttang.bpos.ui.order.OrderRoute
import io.github.bryanttang.bpos.ui.order.OrderDetailRoute
import io.github.bryanttang.bpos.ui.tables.TablesRoute
import io.github.bryanttang.bpos.ui.tables.TablesUiState
import kotlinx.coroutines.flow.Flow

/**
 * 登入之後的整個 App：上面一條常駐列，下面是現在這個畫面。
 *
 * 導覽狀態放在這裡而不是各個畫面裡，那幾個畫面因此完全不知道彼此存在——
 * 桌位總覽只知道「某張桌被按了」，要去點餐還是看明細是這裡決定的。
 */
@Composable
fun BposApp(
    session: StaffSession.SignedIn,
    services: AppServices,
    syncStatus: Flow<SyncStatus>,
    versionName: String,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stack by remember(session.storeId) { mutableStateOf(NavStack.INITIAL) }
    val status by syncStatus.collectAsStateWithLifecycle(initialValue = SyncStatus.UpToDate)

    // 桌位那兩條監聽在這裡收，不在桌位總覽裡面收。
    //
    // 放在畫面裡的話，店員每次「進點餐、送出、回總覽」都會重新訂閱一次，而每一次
    // 重新訂閱都要再付一次整份初始快照的讀取——那是店裡一整天重複幾百次的動作。
    // 收在這裡仍然是 lifecycle-aware 的：平板螢幕關掉或 App 切到背景就停收，
    // 不會有殘留的監聽器整夜算讀取（SPEC 第六節〈監聽器範圍〉第 2 條）。
    val tables by services.tables.state.collectAsStateWithLifecycle(initialValue = TablesUiState())

    // 平板是 kiosk 模式，系統返回鍵不該把整個 App 退掉。已經在桌位總覽時就不攔，
    // 讓系統照原本的行為處理（螢幕固定模式下那本來就是沒有反應）。
    BackHandler(enabled = stack.canGoBack) { stack = stack.pop() }

    Column(modifier = modifier.fillMaxSize()) {
        TopBar(
            title = titleOf(stack.current),
            canGoBack = stack.canGoBack,
            status = status,
            versionName = versionName,
            signOutEnabled = status is SyncStatus.UpToDate,
            onBack = { stack = stack.pop() },
            onSignOut = onSignOut,
        )
        HorizontalDivider()

        when (val screen = stack.current) {
            Screen.Tables -> TablesRoute(
                state = tables,
                // SPEC 第六節〈桌位狀態機〉：空桌按下去直接開單（店員日常不用按開桌），
                // 已經有人的桌按下去是看明細，加點從明細那一頁再開。
                onOpenOrder = { table -> stack = stack.push(orderScreenFor(table)) },
                onOpenDetail = { table -> stack = stack.push(orderDetailFor(table)) },
                modifier = Modifier.fillMaxSize(),
            )

            is Screen.Order -> OrderRoute(
                screen = screen,
                session = session,
                services = services,
                // 送出之後直接回總覽而不是退一層，理由見 NavStack.home()。
                onSubmitted = { stack = stack.home() },
                modifier = Modifier.fillMaxSize(),
            )

            is Screen.OrderDetail -> OrderDetailRoute(
                screen = screen,
                services = services,
                onAddMore = { stack = stack.push(addMoreFor(screen)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun titleOf(screen: Screen): String = when (screen) {
    Screen.Tables -> stringResource(R.string.nav_tables)
    is Screen.Order -> screen.tableLabel
    is Screen.OrderDetail -> screen.tableLabel
}

/**
 * 常駐列。
 *
 * 同步狀態是 SPEC 第六節要求一直看得到的：店員不會去翻設定頁確認單送出去了沒有，
 * 斷網時畫面上如果沒有提示，一整個下午都不會有人發現。
 *
 * 版本號也留著，因為平板是 kiosk 模式整天不關機，出問題時第一個要問的就是
 * 「它到底裝了哪一版」（versionName 帶著 commit sha，見 app/build.gradle.kts）。
 *
 * 登出之後要搬到設定畫面（SPEC 第六節的「設定」那一列），現在先放這裡，
 * 因為沒有它就沒辦法在同一台平板上換帳號。
 */
@Composable
private fun TopBar(
    title: String,
    canGoBack: Boolean,
    status: SyncStatus,
    versionName: String,
    signOutEnabled: Boolean,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canGoBack) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.nav_back)) }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = when (status) {
                SyncStatus.UpToDate -> stringResource(R.string.sync_up_to_date)
                is SyncStatus.Pending -> stringResource(R.string.sync_pending, status.count)
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(text = versionName, style = MaterialTheme.typography.bodySmall)

        // 還有單沒送出去就不能登出：登出會把 storeId 一起丟掉，
        // 而那些單需要它才知道要寫到哪家店底下（見 AuthClient.signOut）。
        TextButton(onClick = onSignOut, enabled = signOutEnabled) {
            Text(
                stringResource(
                    if (signOutEnabled) R.string.sign_out else R.string.sign_out_blocked,
                ),
            )
        }
    }
}
