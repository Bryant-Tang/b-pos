package io.github.bryanttang.bpos.ui.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.order.OpenOrder
import io.github.bryanttang.bpos.order.OpenOrderLine
import io.github.bryanttang.bpos.order.OrderSource
import io.github.bryanttang.bpos.order.OrderStatus
import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.ui.theme.BPosTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 待確認：顧客掃 QR 送出來、還沒被店員放行的單（SPEC 第六節〈畫面〉）。
 *
 * 這是顧客自助點餐那條路上最後一道人為關卡——沒有人按過確認，廚房就收不到單
 * （functions/src/orders/confirmGuestOrder.ts）。所以每一列都要看得到「客人到底點了什麼」，
 * 不是只給一個訂單號叫店員憑感覺按。
 *
 * **這一版只有確認，沒有退回。** SPEC 寫的是「逐單確認或退回」，但退回需要一支
 * 伺服器函式（整張單作廢要原子性地處理每一行、還要決定退回後那張單留在什麼狀態），
 * 那支還不存在。與其放一顆按下去會出錯的退回鍵，不如先不放
 * （CLAUDE.md 第四節：不要生成標著 TODO 的樁）。
 *
 * 金額一個都不是平板算的，全部照抄伺服器算好的欄位（CLAUDE.md 第二節第一條）。
 */
@Composable
fun PendingConfirmScreen(
    orders: List<OpenOrder>,
    confirming: Set<String>,
    confirmed: Set<String>,
    onConfirm: (orderId: String) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (orders.isEmpty()) {
            Text(
                text = stringResource(R.string.pending_confirm_none),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }

        Text(
            text = stringResource(R.string.pending_confirm_hint),
            style = MaterialTheme.typography.bodyMedium,
        )

        LazyColumn(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(orders, key = { it.orderId }) { order ->
                PendingOrderCard(
                    order = order,
                    // 送出中與已放行都不能再按：前者是還沒有結果，後者是這張單下一秒
                    // 就會從列表上消失（狀態變成 open，監聽收到就不見了）。
                    confirming = order.orderId in confirming,
                    confirmed = order.orderId in confirmed,
                    onConfirm = { onConfirm(order.orderId) },
                    zone = zone,
                )
            }
        }
    }
}

@Composable
private fun PendingOrderCard(
    order: OpenOrder,
    confirming: Boolean,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    zone: ZoneId,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = whereLabel(order),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    order.createdAt?.let {
                        Text(
                            text = stringResource(
                                R.string.pending_confirm_submitted_at,
                                TIME_FORMAT.withZone(zone).format(it),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Button(onClick = onConfirm, enabled = !confirming && !confirmed) {
                    Text(
                        stringResource(
                            when {
                                confirmed -> R.string.pending_confirm_done
                                confirming -> R.string.pending_confirm_sending
                                else -> R.string.pending_confirm_action
                            },
                        ),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 只列還在帳上的行。店員按確認是在放行「要進廚房的東西」，已退點的行不會進廚房，
            // 列出來只會讓他以為要做。要對帳的話訂單明細那一頁有完整的作廢紀錄。
            for (line in order.activeLines) {
                LineRow(line)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.order_total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$${order.total}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun LineRow(line: OpenOrderLine) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "${line.name} ×${line.qty}", style = MaterialTheme.typography.bodyLarge)
            if (line.options.isNotEmpty()) {
                Text(
                    text = line.options.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(text = "$${line.subtotal}", style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 這張單是哪一桌送來的。
 *
 * 外帶的顧客單顯示取餐號，因為那才是櫃檯叫得到人的東西；桌號欄位對外帶是空的。
 * 兩個都沒有時明講「未指定桌號」，不要顯示空白——空白會被當成畫面壞了。
 */
@Composable
private fun whereLabel(order: OpenOrder): String = when {
    order.tableLabels.isNotEmpty() -> order.tableLabels.joinToString("、")
    order.pickupCode != null -> stringResource(R.string.order_pickup_code, order.pickupCode)
    else -> stringResource(R.string.pending_confirm_no_table)
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun previewOrder(
    orderId: String,
    tableLabels: List<String> = listOf("窗邊"),
    lines: List<OpenOrderLine> = listOf(
        OpenOrderLine("l1", "牛肉麵", 2, listOf("小辣"), 360, null, null, null),
        OpenOrderLine("l2", "珍珠奶茶", 1, emptyList(), 60, null, null, null),
    ),
) = OpenOrder(
    orderId = orderId,
    orderType = OrderType.DINE_IN,
    status = OrderStatus.PENDING_CONFIRM,
    source = OrderSource.GUEST,
    tableLabels = tableLabels,
    pickupCode = null,
    lines = lines,
    subtotal = lines.sumOf { it.subtotal },
    serviceCharge = 0,
    discount = 0,
    total = lines.sumOf { it.subtotal },
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)

@Preview(showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun PendingConfirmPreview() {
    BPosTheme {
        PendingConfirmScreen(
            orders = listOf(
                previewOrder("order_1"),
                previewOrder("order_2", tableLabels = listOf("角落")),
            ),
            confirming = setOf("order_2"),
            confirmed = emptySet(),
            onConfirm = {},
            zone = ZoneId.of("UTC"),
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun PendingConfirmEmptyPreview() {
    BPosTheme {
        PendingConfirmScreen(
            orders = emptyList(),
            confirming = emptySet(),
            confirmed = emptySet(),
            onConfirm = {},
            zone = ZoneId.of("UTC"),
        )
    }
}
