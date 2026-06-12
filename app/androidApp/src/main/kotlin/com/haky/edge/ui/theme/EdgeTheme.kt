package com.haky.edge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5856D6),       // 보라 (iOS accent)
    onPrimary = Color.White,
    secondary = Color(0xFF5856D6),
    surface = Color.White,
    background = Color(0xFFF2F2F7),    // iOS systemGroupedBackground
    onBackground = Color(0xFF1C1C1E),
)

@Composable
fun EdgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}

val ChangeUp = Color(0xFFFF3B30)      // 상승 빨강 (한국 시장 컨벤션)
val ChangeDown = Color(0xFF0A84FF)    // 하락 파랑
val OrangeAccent = Color(0xFFFF9500) // 주황 accent
val PurpleAccent = Color(0xFF5856D6) // 보라 accent
