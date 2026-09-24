package io.github.bryanttang.bpos.ui.order

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.order.OpenOrder
import io.github.bryanttang.bpos.order.OpenOrderLine
import io.github.bryanttang.bpos.order.OrderStatus
import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.ui.theme.BPosTheme
import java.time.Instant

/**
 * 訂單明細：這張桌現在點了什麼、送出去了沒有、總共多少錢（SPEC 第六節〈畫面〉）。
 *
 * **畫面上的金額一個都不是平板算的**，全部照抄伺服器算好的欄位
 * （CLAUDE.md 第二節第一條）。點餐畫面的購物車有預估金額，那是還沒送出的單；
 * 這裡是已經成立的單，數字必須跟客人最後付的一致。
 *
 * 每張用餐中的單有自己的結帳鍵：一張桌可能同時有好幾張單（SPEC 第六節〈同桌多單〉），
 * 結的是哪一張要由店員指名。退點、轉桌、併桌要等平板那邊接上各自的函式。
 */
@Composable
fun OrderDetailScreen(
    tableLabel: String,
    orders: List<OpenOrder>,
    modifier: Modifier = Modifier,
    onCheckout: (OpenOrder) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(text = tableLabel, style = MaterialTheme.typography.headlineMedium)
        if (orders.size > 1) {
            // SPEC 第六節〈同桌多單〉：結帳後加點會產生新單，店員要看得到不只一張。
            Text(
                text = stringResource(R.string.order_count, orders.size),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (orders.isEmpty()) {
            Text(
                text = stringResource(R.string.order_none),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(orders, key = { it.orderId }) { order -> OrderCard(order, onCheckout = { onCheckout(order) }) }
        }
    }
}

@Composable
private fun OrderCard(order: OpenOrder, onCheckout: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(order.status.labelRes()),
                style = MaterialTheme.typography.labelLarge,
            )
            order.pickupCode?.let {
                Text(
                    text = stringResource(R.string.order_pickup_code, it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            for (line in order.activeLines) {
                LineRow(line)
            }

            if (order.voidedLines.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.order_voided_section),
                    style = MaterialTheme.typography.labelMedium,
                )
                for (line in order.voidedLines) {
                    LineRow(line)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            AmountRow(stringResource(R.string.order_subtotal), order.subtotal)
            if (order.serviceCharge != 0) {
                AmountRow(stringResource(R.string.order_service_charge), order.serviceCharge)
            }
            if (order.discount != 0) {
                AmountRow(stringResource(R.string.order_discount), -order.discount)
            }
            AmountRow(stringResource(R.string.order_total), order.total, emphasized = true)

            // 只有用餐中的單能結。待確認的單伺服器會擋（「請先確認再結帳」），
            // 那道關卡是 SPEC 第十二節階段 4 的驗收條件，不在這裡放一顆一定會被拒絕的鍵。
            if (order.status == OrderStatus.OPEN) {
                Button(
                    onClick = onCheckout,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.order_checkout))
                }
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
            Text(
                text = "${line.name} ×${line.qty}",
                style = MaterialTheme.typography.bodyLarge,
                // 作廢的行劃掉而不是刪掉：稅法要求作廢留痕，店員也要能對得起帳
                // （SPEC 第四節）。
                textDecoration = if (line.isVoided) TextDecoration.LineThrough else null,
            )
            if (line.options.isNotEmpty()) {
                Text(
                    text = line.options.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 已送廚房與還沒送，退起來是兩回事：一個要印作廢單、不能從帳上消失，
            // 另一個直接刪掉就好。店員按下退點之前就該看得出來是哪一種。
            if (line.isPrinted) {
                Text(
                    text = stringResource(R.string.order_line_printed),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            line.voidReason?.let {
                Text(
                    text = stringResource(R.string.order_void_reason, it),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text(text = "$${line.subtotal}", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AmountRow(label: String, amount: Int, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else null,
        )
        Text(
            text = "$$amount",
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else null,
        )
    }
}

/**
 * 狀態在畫面上怎麼講。
 *
 * [OrderStatus.UNKNOWN] 不含糊帶過：這台平板比伺服器舊的時候，店員要看得出來
 * 「這張單我看不懂」，而不是看到一個像正常狀態的字然後照著做。
 */
internal fun OrderStatus.labelRes(): Int = when (this) {
    OrderStatus.PENDING_CONFIRM -> R.string.order_status_pending_confirm
    OrderStatus.OPEN -> R.string.order_status_open
    OrderStatus.CLOSED -> R.string.order_status_closed
    OrderStatus.VOIDED -> R.string.order_status_voided
    OrderStatus.UNKNOWN -> R.string.order_status_unknown
}

private fun previewOrder(
    status: OrderStatus = OrderStatus.OPEN,
    lines: List<OpenOrderLine> = listOf(
        OpenOrderLine("l1", "牛肉麵", 2, listOf("小辣"), 360, Instant.EPOCH, null, null),
        OpenOrderLine("l2", "珍珠奶茶", 1, emptyList(), 60, null, null, null),
    ),
) = OpenOrder(
    orderId = "order_preview",
    orderType = OrderType.DINE_IN,
    status = status,
    source = io.github.bryanttang.bpos.order.OrderSource.STAFF,
    tableLabels = listOf("窗邊"),
    pickupCode = null,
    lines = lines,
    subtotal = lines.filter { it.voidedAt == null }.sumOf { it.subtotal },
    serviceCharge = 0,
    discount = 0,
    total = lines.filter { it.voidedAt == null }.sumOf { it.subtotal },
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)

@Preview(showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun OrderDetailPreview() {
    BPosTheme {
        OrderDetailScreen(tableLabel = "窗邊", orders = listOf(previewOrder()))
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun OrderDetailWithVoidedPreview() {
    BPosTheme {
        OrderDetailScreen(
            tableLabel = "窗邊",
            orders = listOf(
                previewOrder(
                    lines = listOf(
                        OpenOrderLine("l1", "牛肉麵", 2, listOf("小辣"), 360, Instant.EPOCH, null, null),
                        OpenOrderLine("l2", "珍珠奶茶", 1, emptyList(), 0, Instant.EPOCH, Instant.EPOCH, "上錯桌"),
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun OrderDetailEmptyPreview() {
    BPosTheme {
        OrderDetailScreen(tableLabel = "窗邊", orders = emptyList())
    }
}
