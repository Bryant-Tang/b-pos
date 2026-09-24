package io.github.bryanttang.bpos.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.R
import io.github.bryanttang.bpos.order.CloseReceipt
import io.github.bryanttang.bpos.order.OpenOrder
import io.github.bryanttang.bpos.order.OpenOrderLine
import io.github.bryanttang.bpos.order.OrderSource
import io.github.bryanttang.bpos.order.OrderStatus
import io.github.bryanttang.bpos.remote.PaymentMethod
import io.github.bryanttang.bpos.sync.OrderType
import io.github.bryanttang.bpos.ui.theme.BPosTheme
import java.time.Instant

/**
 * 結帳表單：這張單點了什麼、合計多少、怎麼付、收了多少現金。
 *
 * **畫面上的合計是伺服器寫在單上的數字**，平板沒有算。伺服器結帳時也是拿單上的數字
 * 鎖住，不重算（closeOrder.ts 第 1 點）。
 *
 * 沒有折扣、也沒有「分開結帳」：折扣要伺服器重算（SPEC 第六節〈離線時的限制〉），
 * 分開結帳要 `splitOrder`，兩支都還沒有。與其放兩顆按下去會出錯的鍵，不如先不放
 * （CLAUDE.md 第四節）。
 */
@Composable
fun CheckoutForm(
    order: OpenOrder,
    method: PaymentMethod,
    receivedText: String,
    closing: Boolean,
    onMethodChange: (PaymentMethod) -> Unit,
    onReceivedChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cash = parseCashInput(receivedText, order.total)
    val canClose = !closing && (method != PaymentMethod.CASH || cash is CashInput.Ok)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            // 平板橫放時寬度一千多 dp，輸入框和按鈕拉滿整個寬度很難讀。
            // wrapContentWidth 先放掉 fillMaxSize 帶進來的最小寬度，widthIn 才壓得住。
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (line in order.activeLines) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${line.name} ×${line.qty}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "$${line.subtotal}", style = MaterialTheme.typography.bodyLarge)
            }
        }
        HorizontalDivider()
        AmountRow(stringResource(R.string.order_total), order.total, emphasized = true)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (option in PaymentMethod.entries) {
                FilterChip(
                    selected = option == method,
                    onClick = { onMethodChange(option) },
                    label = { Text(stringResource(option.labelRes())) },
                    enabled = !closing,
                )
            }
        }

        if (method == PaymentMethod.CASH) {
            OutlinedTextField(
                value = receivedText,
                onValueChange = { onReceivedChange(digitsOnly(it)) },
                label = { Text(stringResource(R.string.checkout_received)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !closing,
                isError = cash is CashInput.TooLittle,
                supportingText = (cash as? CashInput.TooLittle)?.let {
                    { Text(stringResource(R.string.checkout_received_too_little, it.total)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(onClick = onClose, enabled = canClose, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (closing) R.string.checkout_sending else R.string.checkout_action),
            )
        }

        Text(
            text = stringResource(R.string.checkout_offline_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 結好了。
 *
 * 找零放最大，因為那是店員接下來要做的事——從抽屜拿錢出來。
 * 總額、收到、找零三個數字並排列出來，店員多打一個零的時候，在把錢拿出去之前看得到。
 */
@Composable
fun CheckoutReceiptView(
    result: CheckoutResult,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            // 平板橫放時寬度一千多 dp，輸入框和按鈕拉滿整個寬度很難讀。
            // wrapContentWidth 先放掉 fillMaxSize 帶進來的最小寬度，widthIn 才壓得住。
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (result) {
            is CheckoutResult.Closed -> ClosedReceipt(result.receipt, result.received)
            CheckoutResult.ClosedUnreadable -> Text(
                text = stringResource(R.string.checkout_unreadable),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.checkout_done))
        }
    }
}

@Composable
private fun ClosedReceipt(receipt: CloseReceipt, received: Int?) {
    if (receipt.replayed) {
        Text(
            text = stringResource(R.string.checkout_replayed),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    AmountRow(stringResource(R.string.order_total), receipt.total)
    // 重送拿回來的是當時那一次的結果，而這次輸入的收款金額可能跟當時不一樣。
    // 兩個湊在一起會變成「收到 1000、找零 200」這種對不起來的畫面，所以只列伺服器的數字。
    if (received != null && !receipt.replayed) {
        AmountRow(stringResource(R.string.checkout_received_label), received)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(R.string.checkout_change), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "$${receipt.change}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
    }

    if (receipt.lookupCode.isNotBlank()) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.checkout_lookup_code, receipt.lookupCode),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.checkout_lookup_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AmountRow(label: String, amount: Int, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
        Text(text = label, style = style, fontWeight = if (emphasized) FontWeight.Bold else null)
        Text(text = "$$amount", style = style, fontWeight = if (emphasized) FontWeight.Bold else null)
    }
}

private fun PaymentMethod.labelRes(): Int = when (this) {
    PaymentMethod.CASH -> R.string.checkout_method_cash
    PaymentMethod.MOBILE -> R.string.checkout_method_mobile
    PaymentMethod.CARD -> R.string.checkout_method_card
}

private val previewOrder = OpenOrder(
    orderId = "order_preview",
    orderType = OrderType.DINE_IN,
    status = OrderStatus.OPEN,
    source = OrderSource.STAFF,
    tableLabels = listOf("窗邊"),
    pickupCode = null,
    lines = listOf(
        OpenOrderLine("l1", "牛肉麵", 2, listOf("小辣"), 360, Instant.EPOCH, null, null),
        OpenOrderLine("l2", "珍珠奶茶", 1, emptyList(), 60, Instant.EPOCH, null, null),
    ),
    subtotal = 420,
    serviceCharge = 0,
    discount = 0,
    total = 420,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)

@Preview(showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun CheckoutFormPreview() {
    BPosTheme {
        CheckoutForm(
            order = previewOrder,
            method = PaymentMethod.CASH,
            receivedText = "300",
            closing = false,
            onMethodChange = {},
            onReceivedChange = {},
            onClose = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 1000)
@Composable
private fun CheckoutReceiptPreview() {
    BPosTheme {
        CheckoutReceiptView(
            result = CheckoutResult.Closed(
                receipt = CloseReceipt(total = 420, change = 80, lookupCode = "0000", replayed = false),
                received = 500,
            ),
            onDone = {},
        )
    }
}
