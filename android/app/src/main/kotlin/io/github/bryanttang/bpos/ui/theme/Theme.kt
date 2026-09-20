package io.github.bryanttang.bpos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 桌位總覽要用顏色分辨空桌／用餐中／待確認／已結帳（SPEC 第六節〈桌位狀態機〉），
// 所以不用動態取色：店員認的是固定的顏色，不是隨桌布變的顏色。
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun BPosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
