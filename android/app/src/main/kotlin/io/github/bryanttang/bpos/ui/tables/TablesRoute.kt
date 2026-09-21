package io.github.bryanttang.bpos.ui.tables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.tables.Table
import io.github.bryanttang.bpos.tables.TableStatus

/**
 * 桌位總覽接上真實資料。
 *
 * [state] 由 BposApp 收好傳進來，不在這裡訂閱——店員從總覽進點餐再回來是幾十秒的事，
 * 每次重新訂閱都要再付一次整份初始快照的讀取（SPEC 第六節〈監聽器範圍〉）。
 *
 * 按下一張桌要去哪裡在這裡決定，不在 [TableOverview] 裡：那個 composable 只認得
 * 「某張桌被按了」，換成之後要先跳確認或先選人數，改的都是這裡。
 */
@Composable
fun TablesRoute(
    state: TablesUiState,
    onOpenOrder: (Table) -> Unit,
    onOpenDetail: (Table) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Loading(modifier)
        return
    }

    TableOverview(
        zones = state.zones,
        tables = state.tables,
        onTableClick = { table ->
            val status = state.tables.firstOrNull { it.table.tableId == table.tableId }?.status
            // 空桌直接開單（SPEC：店員日常不需要按開桌）；已經有人或剛結帳的看明細，
            // 加點從明細那一頁再開。
            if (status == TableStatus.EMPTY) onOpenOrder(table) else onOpenDetail(table)
        },
        modifier = modifier,
    )
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.tables_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
