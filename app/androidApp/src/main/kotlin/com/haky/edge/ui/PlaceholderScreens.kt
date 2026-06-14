package com.haky.edge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ──── 공용: 얇은 테두리 SegmentedButtonRow ─────────────────────────────────
// SettingsScreen 분석 모드 토글 + BriefingScreen 탭 선택기가 공통으로 사용.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> EdgeSegmentedButtonRow(
    items: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        items.forEachIndexed { index, item ->
            SegmentedButton(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = items.size),
                icon = {},
                border = BorderStroke(0.5.dp, borderColor),
                label = { Text(label(item), maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ──── 설정 탭 ─────────────────────────────────────────────────────────────

private val analysisModes = listOf("defensive" to "방어 🛡️", "aggressive" to "공격 ⚔️")

@Composable
fun SettingsScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var modeIndex by remember { mutableStateOf(if (AppPrefs.getMode(ctx) == "aggressive") 1 else 0) }

    Scaffold(
        topBar = { CompactHeader(title = "설정") }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            // ── 분석 모드 카드 ──
            Text(
                "분석 모드",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EdgeSegmentedButtonRow(
                        items = analysisModes,
                        selectedIndex = modeIndex,
                        onSelect = { i ->
                            modeIndex = i
                            AppPrefs.setMode(ctx, analysisModes[i].first)
                        },
                        label = { it.second },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (modeIndex == 1)
                            "⚔️ 공격적 모드는 계산된 지표에 근거한 단호한 의견을 제시해요. 브리핑에서는 포트폴리오 스탠스(비중 조절·현금 확보 등), 종목상세에서는 평단 손익·신호·밸류 위치를 근거로 개별 종목 매매 판단까지 포함돼요. 참고용이며 투자 책임은 본인에게 있어요."
                        else
                            "🛡️ 방어적 모드는 사실과 방향만 담백하게 전달해요. 적극적인 시장 스탠스 의견을 보려면 공격으로 바꿔보세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 앱 정보 카드 ──
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "앱 정보",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    InfoRow("버전", "1.0")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    InfoRow("데이터 출처", "한투 API · DART · 네이버 뉴스")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── disclaimer footer ──
            Text(
                text = "Edge 1.0 · 개인 투자 판단 보조 도구\n실제 투자 결정의 책임은 본인에게 있습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
