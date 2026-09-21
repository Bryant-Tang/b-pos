package io.github.bryanttang.bpos.ui.tables

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.bryanttang.bpos.tables.TableStatus

/**
 * 桌位卡片的配色（SPEC 第六節〈桌位狀態機〉的四種顯示狀態）。
 *
 * SPEC 指定的是「淺色／主色／強調色＋角標／淡色半透明」，這裡把它對應到
 * Material 3 的顏色角色，而不是寫死色碼——寫死的話深色模式會爛掉，
 * 而平板在店裡可能整天亮著、也可能被設成深色省電。
 *
 * **這裡不用動態取色（Material You）**：店員認的是固定的顏色，
 * 顏色跟著使用者桌布變的話，那套「藍色是用餐中」的直覺就不成立了。
 * 主題那邊已經明確不啟用 dynamicColorScheme，這裡只是再說明一次理由。
 */
@Immutable
data class TableCardColors(
    val container: Color,
    val content: Color,
    /** 待確認才有的角標；其他狀態為 null。 */
    val badge: Color?,
)

@Composable
fun colorsFor(status: TableStatus): TableCardColors {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        // 空桌：淺色，視覺上要「退後」，店員掃視時略過。
        TableStatus.EMPTY -> TableCardColors(
            container = scheme.surfaceVariant,
            content = scheme.onSurfaceVariant,
            badge = null,
        )

        // 用餐中：主色，這是桌位總覽上最常見也最該被看見的狀態。
        TableStatus.OCCUPIED -> TableCardColors(
            container = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            badge = null,
        )

        // 待確認：強調色加角標。有顧客自助單在等人按確認，
        // 沒人處理的話客人的餐不會進廚房，所以要最顯眼。
        TableStatus.PENDING_CONFIRM -> TableCardColors(
            container = scheme.tertiaryContainer,
            content = scheme.onTertiaryContainer,
            badge = scheme.error,
        )

        // 已結帳：淡色半透明。不需要動作，但還不能當成空桌——
        // 客人可能還在門口看帳單。
        TableStatus.PAID -> TableCardColors(
            container = scheme.surfaceVariant.copy(alpha = PAID_ALPHA),
            content = scheme.onSurfaceVariant.copy(alpha = PAID_ALPHA),
            badge = null,
        )
    }
}

private const val PAID_ALPHA = 0.45f
