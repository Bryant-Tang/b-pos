package io.github.bryanttang.bpos.ui.tables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.tables.Table
import io.github.bryanttang.bpos.tables.TableStatus
import io.github.bryanttang.bpos.tables.Zone
import io.github.bryanttang.bpos.tables.displayLabel

/** 一張桌子連同它現在該顯示的狀態。 */
data class TableOnPlan(
    val table: Table,
    val status: TableStatus,
    /** 本桌今日的單數，卡片上顯示「N 張單」（SPEC 第六節〈同桌多單〉）。 */
    val orderCount: Int = 0,
)

/**
 * 桌位總覽：依區域分頁，桌位按平面圖座標排列，顏色標示狀態。
 *
 * 座標是 0 到 1 的相對值，渲染時乘上容器大小（SPEC 第三節）。
 * 後台在筆電編輯、平板在店裡顯示，螢幕尺寸不同，存相對座標兩邊才會一致。
 */
@Composable
fun TableOverview(
    zones: List<Zone>,
    tables: List<TableOnPlan>,
    onTableClick: (Table) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedZoneIndex by remember(zones) { mutableStateOf(0) }
    val orderedZones = remember(zones) { zones.sortedBy { it.sort } }

    Column(modifier = modifier.fillMaxSize()) {
        if (orderedZones.size > 1) {
            TabRow(selectedTabIndex = selectedZoneIndex) {
                orderedZones.forEachIndexed { index, zone ->
                    Tab(
                        selected = index == selectedZoneIndex,
                        onClick = { selectedZoneIndex = index },
                        text = { Text(zone.name) },
                    )
                }
            }
        }

        val currentZone = orderedZones.getOrNull(selectedZoneIndex)
        val zoneTables = remember(currentZone, tables) {
            tables.filter { it.table.zoneId == currentZone?.zoneId }
        }

        FloorPlan(
            tables = zoneTables,
            onTableClick = onTableClick,
            modifier = Modifier.fillMaxSize().padding(FLOOR_PADDING),
        )
    }
}

/**
 * 平面圖本身。
 *
 * 用 [BoxWithConstraints] 拿到容器實際大小，再把每張桌的相對座標換算成位移。
 * 換算時扣掉卡片自身的大小，否則靠右或靠下的桌子會有一半跑到畫面外。
 */
@Composable
private fun FloorPlan(
    tables: List<TableOnPlan>,
    onTableClick: (Table) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val usableWidth = maxWidth - CARD_SIZE
        val usableHeight = maxHeight - CARD_SIZE

        tables.forEach { onPlan ->
            TableCard(
                onPlan = onPlan,
                onClick = { onTableClick(onPlan.table) },
                modifier = Modifier.offset(
                    x = usableWidth * onPlan.table.x,
                    y = usableHeight * onPlan.table.y,
                ),
            )
        }
    }
}

@Composable
private fun TableCard(
    onPlan: TableOnPlan,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = colorsFor(onPlan.status)
    val label = onPlan.table.label

    Box(
        modifier = modifier
            .size(CARD_SIZE)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.container)
            .clickable(onClick = onClick)
            // 截斷後的桌名對讀螢幕的人沒有用，所以無障礙描述給完整的名字與狀態。
            .semantics { contentDescription = "$label，${statusDescription(onPlan.status)}" },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = displayLabel(label),
                style = MaterialTheme.typography.titleMedium,
                color = colors.content,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (onPlan.orderCount > 1) {
                Text(
                    text = "${onPlan.orderCount} 張單",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.content,
                )
            }
        }

        // 待確認的角標：有顧客自助單在等人按確認。
        colors.badge?.let { badgeColor ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor)
                    .fillMaxWidth(),
            )
        }
    }
}

private fun statusDescription(status: TableStatus): String = when (status) {
    TableStatus.EMPTY -> "空桌"
    TableStatus.OCCUPIED -> "用餐中"
    TableStatus.PENDING_CONFIRM -> "待確認"
    TableStatus.PAID -> "已結帳"
}

private val CARD_SIZE: Dp = 96.dp
private val FLOOR_PADDING: Dp = 16.dp
