package io.github.bryanttang.bpos.sync

import com.google.firebase.Timestamp
import io.github.bryanttang.bpos.data.local.OrderIntentOutboxEntity
import io.github.bryanttang.bpos.data.local.OutboxState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Date

/**
 * 意圖在三種表示法之間的轉換：領域模型、Room 的資料列、Firestore 的文件。
 *
 * 三邊的欄位名稱刻意保持一致，唯一的例外是 Room 用 snake_case 的欄位名
 * （SQL 的慣例）。Firestore 那邊的欄位名由伺服器的 zod schema 決定，
 * 而且是 `.strict()`——拼錯一個字就是整筆被拒絕，不是安靜忽略。
 */
internal val outboxJson = Json { ignoreUnknownKeys = false }

private val intentLinesSerializer = ListSerializer(IntentLine.serializer())

fun OrderIntent.toOutboxEntity(): OrderIntentOutboxEntity =
    OrderIntentOutboxEntity(
        intentId = intentId,
        orderId = orderId,
        orderType = orderType.wireName,
        tableId = tableId,
        linesJson = outboxJson.encodeToString(intentLinesSerializer, lines),
        createdBy = createdBy,
        clientCreatedAtMillis = clientCreatedAt.toEpochMilli(),
        state = OutboxState.PENDING.storedName,
        attempts = 0,
        // 第一次送出不等待，所以下次嘗試時間就是建立時間。
        nextAttemptAtMillis = clientCreatedAt.toEpochMilli(),
        lastError = null,
    )

fun OrderIntentOutboxEntity.toOrderIntent(): OrderIntent =
    OrderIntent(
        intentId = intentId,
        orderId = orderId,
        orderType = OrderType.fromWireName(orderType),
        tableId = tableId,
        lines = outboxJson.decodeFromString(intentLinesSerializer, linesJson),
        createdBy = createdBy,
        clientCreatedAt = Instant.ofEpochMilli(clientCreatedAtMillis),
    )

/**
 * 轉成要寫進 Firestore 的欄位。
 *
 * 這裡的鍵必須與 `functions/src/orders/intentSchema.ts` 以及 `firestore.rules`
 * 的白名單**完全一致**：Rules 用 `hasOnly` 擋多的、`hasAll` 擋少的，
 * 兩邊對不上就是寫不進去。
 */
fun OrderIntent.toFirestoreMap(): Map<String, Any?> = mapOf(
    "intentId" to intentId,
    "orderId" to orderId,
    "orderType" to orderType.wireName,
    "tableId" to tableId,
    "lines" to lines.map { line ->
        mapOf(
            "itemId" to line.itemId,
            "qty" to line.qty,
            "options" to line.options.map { option ->
                mapOf("groupId" to option.groupId, "optionId" to option.optionId)
            },
        )
    },
    "createdBy" to createdBy,
    // Rules 要求 clientCreatedAt is timestamp，所以不能送毫秒數字。
    "clientCreatedAt" to Timestamp(Date(clientCreatedAt.toEpochMilli())),
)
