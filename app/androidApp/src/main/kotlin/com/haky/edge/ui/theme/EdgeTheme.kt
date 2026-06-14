package com.haky.edge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

// ── Material 구조 색 (배경·표면·텍스트·구분선) — 다크/라이트 자동 전환 ──
// 정책: 회색 배경(grouped) 위에 순백/다크 섹션 카드가 떠오름. [[edge-bg-section-policy]]
private val LightColors = lightColorScheme(
    primary = Color(0xFF5856D6),       // 보라 (iOS accent)
    onPrimary = Color.White,
    secondary = Color(0xFF5856D6),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),         // 값·코멘트·금액 (검정)
    onSurfaceVariant = Color(0xFF8E8E93),  // 라벨·설명 (옅은 회색, iOS systemGray) — 값과 구별
    surfaceVariant = Color(0xFFE5E5EA),    // 옅은 박스 배경 (iOS systemGray5)
    outlineVariant = Color(0xFFD1D1D6),    // divider
    background = Color(0xFFF2F2F7),    // iOS systemGroupedBackground
    onBackground = Color(0xFF1C1C1E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5E5CE6),       // iOS dark systemIndigo
    onPrimary = Color.White,
    secondary = Color(0xFF5E5CE6),
    surface = Color(0xFF1C1C1E),           // 카드 (iOS dark secondarySystemGroupedBackground)
    onSurface = Color(0xFFF2F2F7),         // 값·코멘트·금액 (거의 흰색)
    onSurfaceVariant = Color(0xFF98989E),  // 라벨·설명 (iOS dark secondaryLabel 톤)
    surfaceVariant = Color(0xFF2C2C2E),    // 옅은 박스 배경 (iOS dark systemGray5)
    outlineVariant = Color(0xFF38383A),    // divider (iOS dark separator)
    background = Color(0xFF000000),    // iOS dark systemGroupedBackground (카드가 검정 위에 뜸)
    onBackground = Color(0xFFF2F2F7),
)

// ── 시맨틱 액센트 색 (상승/하락/중립/성공/강조) — 테마 인지형 중앙 토큰 ──
// 한국 시장 컨벤션: 상승=빨강, 하락=파랑 (다크에서도 유지, [[graphic-design-batch]] P3)
@Immutable
data class EdgeColors(
    val up: Color,           // 상승 빨강
    val down: Color,         // 하락 파랑
    val neutral: Color,      // 보합 회색
    val success: Color,      // 도달/익절 초록
    val purple: Color,       // 보라 accent
    val orange: Color,       // 주황 accent / 외인
    val teal: Color,         // 청록 / 기관
    val sell: Color,         // 매도 파랑 (systemBlue)
)

private val LightEdgeColors = EdgeColors(
    up = Color(0xFFFF3B30),
    down = Color(0xFF0A84FF),
    neutral = Color(0xFF8E8E93),
    success = Color(0xFF34C759),
    purple = Color(0xFF5856D6),
    orange = Color(0xFFFF9500),
    teal = Color(0xFF30B0C7),
    sell = Color(0xFF007AFF),
)

private val DarkEdgeColors = EdgeColors(
    up = Color(0xFFFF453A),      // iOS dark systemRed
    down = Color(0xFF0A84FF),    // iOS dark systemBlue (라이트와 동일값)
    neutral = Color(0xFF98989E),
    success = Color(0xFF30D158), // iOS dark systemGreen
    purple = Color(0xFF5E5CE6),  // iOS dark systemIndigo
    orange = Color(0xFFFF9F0A),  // iOS dark systemOrange
    teal = Color(0xFF40C8E0),    // 청록 다크 톤 (조금 밝게)
    sell = Color(0xFF0A84FF),    // iOS dark systemBlue
)

val LocalEdgeColors = staticCompositionLocalOf { LightEdgeColors }

/** `MaterialTheme.colorScheme`처럼 `EdgeTheme.colors`로 시맨틱 색 접근. */
object EdgeTheme {
    val colors: EdgeColors
        @Composable @ReadOnlyComposable get() = LocalEdgeColors.current
}

@Composable
fun EdgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalEdgeColors provides if (dark) DarkEdgeColors else LightEdgeColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}

// ── 하위호환 토큰 (기존 참조 보존, 테마 인지형으로 위임) ──
// 호출부가 @Composable 컨텍스트여야 함. 비-composable 헬퍼는 @Composable로 승격하거나 색을 인자로 받음.
val ChangeUp: Color
    @Composable @ReadOnlyComposable get() = LocalEdgeColors.current.up
val ChangeDown: Color
    @Composable @ReadOnlyComposable get() = LocalEdgeColors.current.down
val OrangeAccent: Color
    @Composable @ReadOnlyComposable get() = LocalEdgeColors.current.orange
val PurpleAccent: Color
    @Composable @ReadOnlyComposable get() = LocalEdgeColors.current.purple
