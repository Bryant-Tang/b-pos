package io.github.bryanttang.bpos.ui.tables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.tables.Table
import io.github.bryanttang.bpos.tables.TableStatus
import io.github.bryanttang.bpos.tables.Zone
import io.github.bryanttang.bpos.tables.displayLabel
import io.github.bryanttang.bpos.ui.theme.BPosTheme

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
            .semantics {
                contentDescription =
                    "$label，${cardSubtitle(onPlan)}，${statusDescription(onPlan.status)}"
            },
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
            // 人數與單數擠在同一行，不是各佔一行：卡片只有 96dp，
            // 三行文字會把桌名壓到看不清楚，而桌名才是店員第一眼要找的東西。
            Text(
                text = cardSubtitle(onPlan),
                style = MaterialTheme.typography.labelSmall,
                color = colors.content,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }

        // 待確認的角標：有顧客自助單在等人按確認。
        colors.badge?.let { badgeColor ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor),
            )
        }
    }
}

/**
 * 卡片上桌名底下那一行：座位數，以及本桌今日的單數。
 *
 * 單數只在超過一張時才講——每桌都掛一個「1 張單」等於沒有資訊，
 * 而「3 張單」是店員真的需要注意的事（SPEC 第六節〈同桌多單〉）。
 */
internal fun cardSubtitle(onPlan: TableOnPlan): String {
    val seats = "${onPlan.table.seats} 人"
    return if (onPlan.orderCount > 1) "$seats・${onPlan.orderCount} 張單" else seats
}

private fun statusDescription(status: TableStatus): String = when (status) {
    TableStatus.EMPTY -> "空桌"
    TableStatus.OCCUPIED -> "用餐中"
    TableStatus.PENDING_CONFIRM -> "待確認"
    TableStatus.PAID -> "已結帳"
}

private val CARD_SIZE: Dp = 96.dp
private val FLOOR_PADDING: Dp = 16.dp

/*
 * 以下是給 Android Studio 看的預覽。
 *
 * 尺寸用 1280×800，就是店裡那台平板橫放的樣子——預設的手機尺寸看不出這個畫面
 * 實際上長怎樣，而這個畫面的重點就是「一眼看完整間店」。
 *
 * 裡面的店名、桌號、區域全部是虛構的（CLAUDE.md 第一節），要改請繼續用假資料。
 */

private val previewZones = listOf(
    Zone(zoneId = "zone_1f", name = "一樓", sort = 1),
    Zone(zoneId = "zone_2f", name = "二樓", sort = 2),
)

private fun previewTable(
    id: String,
    label: String,
    x: Float,
    y: Float,
    status: TableStatus,
    orderCount: Int = 0,
    zoneId: String = "zone_1f",
) = TableOnPlan(
    table = Table(
        tableId = id,
        label = label,
        zoneId = zoneId,
        x = x,
        y = y,
        sort = 0,
        seats = 4,
    ),
    status = status,
    orderCount = orderCount,
)

@Preview(name = "桌位總覽（平板橫放）", showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun TableOverviewPreview() {
    BPosTheme {
        TableOverview(
            zones = previewZones,
            tables = listOf(
                previewTable("t_a1", "A1", 0f, 0f, TableStatus.EMPTY),
                previewTable("t_a2", "A2", 0.3f, 0f, TableStatus.OCCUPIED, orderCount = 1),
                previewTable("t_a3", "A3", 0.6f, 0f, TableStatus.PENDING_CONFIRM, orderCount = 2),
                previewTable("t_a4", "A4", 0.9f, 0f, TableStatus.PAID),
                previewTable("t_b1", "窗邊包廂一號", 0f, 0.5f, TableStatus.OCCUPIED, orderCount = 3),
                previewTable("t_b2", "B2", 0.45f, 0.5f, TableStatus.EMPTY),
                previewTable("t_b3", "B3", 1f, 1f, TableStatus.OCCUPIED),
            ),
            onTableClick = {},
        )
    }
}

/**
 * 只有一個區域時不該出現分頁列——一整排只有「一樓」一個頁籤是白佔一條高度。
 * 這個預覽就是用來看那件事有沒有生效的。
 */
@Preview(name = "桌位總覽（單一區域）", showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun TableOverviewSingleZonePreview() {
    BPosTheme {
        TableOverview(
            zones = listOf(previewZones.first()),
            tables = listOf(
                previewTable("t_a1", "A1", 0f, 0f, TableStatus.OCCUPIED, orderCount = 2),
                previewTable("t_a2", "A2", 0.5f, 0.5f, TableStatus.PAID),
            ),
            onTableClick = {},
        )
    }
}
