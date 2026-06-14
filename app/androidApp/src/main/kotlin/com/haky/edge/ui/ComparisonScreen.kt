package com.haky.edge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haky.edge.api.EdgeApi
import com.haky.edge.model.Comparison
import com.haky.edge.model.ComparisonDetail
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.PurpleAccent
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// ── ComparisonScreen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen(
    itemA: WatchItem,
    itemB: WatchItem,
    api: EdgeApi,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val mode = AppPrefs.getMode(ctx)
    var comparison by remember { mutableStateOf<Comparison?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load(force: Boolean = false) {
        loading = true
        errorText = null
        comparison = runCatching {
            api.getComparison(codeA = itemA.code, codeB = itemB.code, mode = mode, refresh = force)
        }.onFailure { errorText = "불러오기 실패: ${it.message}" }.getOrNull()
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("종목 비교") },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { load(force = true) } }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "재생성")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                loading -> Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("비교 분석 중…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                comparison != null -> {
                    val c = comparison!!
                    MetricsTable(c)
                    CommentCard(c, mode)
                }
                errorText != null -> Text(errorText!!, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── 핵심 지표 테이블 ──────────────────────────────────────────────────────────

@Composable
private fun MetricsTable(c: Comparison) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            // 헤더
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(c.a.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("지표", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.width(70.dp))
                Text(c.b.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
            MetricRow("현재가",
                left = "${fmtPrice(c.a.price)}원", right = "${fmtPrice(c.b.price)}원",
                leftSub = rateText(c.a.changeRate), rightSub = rateText(c.b.changeRate),
                leftColor = rateColor(c.a.changeRate), rightColor = rateColor(c.b.changeRate))
            HorizontalDivider()
            MetricRow("52주 위치",
                left = "${c.a.week52PosPct.toInt()}%", right = "${c.b.week52PosPct.toInt()}%",
                leftSub = week52Label(c.a.week52PosPct), rightSub = week52Label(c.b.week52PosPct))
            if (c.a.per > 0 || c.b.per > 0) {
                HorizontalDivider()
                MetricRow("PER / PBR",
                    left = if (c.a.per > 0) "${fmtDec(c.a.per)}배 / ${fmtDec(c.a.pbr)}배" else "-",
                    right = if (c.b.per > 0) "${fmtDec(c.b.per)}배 / ${fmtDec(c.b.pbr)}배" else "-",
                    leftSub = c.a.valuationLabel, rightSub = c.b.valuationLabel,
                    leftColor = valuationColor(c.a.valuationLabel),
                    rightColor = valuationColor(c.b.valuationLabel))
            }
            if (c.a.upsidePct != null || c.b.upsidePct != null) {
                HorizontalDivider()
                MetricRow("목표가 괴리",
                    left = upsideText(c.a.upsidePct), right = upsideText(c.b.upsidePct),
                    leftColor = upsideColor(c.a.upsidePct), rightColor = upsideColor(c.b.upsidePct))
            }
            HorizontalDivider()
            MetricRow("외인 3일",
                left = flowText(c.a.foreignNet3d), right = flowText(c.b.foreignNet3d),
                leftColor = flowColor(c.a.foreignNet3d), rightColor = flowColor(c.b.foreignNet3d))
            HorizontalDivider()
            MetricRow("기관 3일",
                left = flowText(c.a.institutionNet3d), right = flowText(c.b.institutionNet3d),
                leftColor = flowColor(c.a.institutionNet3d), rightColor = flowColor(c.b.institutionNet3d))
            if (c.a.quarterlyYoy != null || c.b.quarterlyYoy != null) {
                HorizontalDivider()
                MetricRow("분기순익 YoY",
                    left = yoyText(c.a.quarterlyYoy), right = yoyText(c.b.quarterlyYoy),
                    leftColor = yoyColor(c.a.quarterlyYoy), rightColor = yoyColor(c.b.quarterlyYoy))
            }
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    left: String, right: String,
    leftSub: String? = null, rightSub: String? = null,
    leftColor: Color = MaterialTheme.colorScheme.onSurface,
    rightColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(left, style = MaterialTheme.typography.bodyMedium, color = leftColor,
                textAlign = TextAlign.Center)
            if (leftSub != null) Text(leftSub, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.width(70.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(right, style = MaterialTheme.typography.bodyMedium, color = rightColor,
                textAlign = TextAlign.Center)
            if (rightSub != null) Text(rightSub, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ── AI 비교 코멘트 카드 ───────────────────────────────────────────────────────

@Composable
private fun CommentCard(c: Comparison, mode: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = PurpleAccent)
                Text("비교 분석", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (mode == "aggressive") {
                    Surface(shape = RoundedCornerShape(50), color = Color(0xFFFF9500).copy(alpha = 0.12f)) {
                        Text("⚔️ 공격적 모드", style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF9500),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (c.generatedAt.isNotEmpty()) {
                    Text("오늘 ${c.generatedAt} 생성", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            ProseBlock(c.comment)
        }
    }
}

@Composable
private fun ProseBlock(text: String) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(modifier = Modifier.width(3.dp).fillMaxHeight(),
            shape = RoundedCornerShape(2.dp), color = PurpleAccent.copy(alpha = 0.35f)) {}
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            text.split("\n\n").forEach { para ->
                val t = para.trim()
                if (t.isNotEmpty() && t != "---") {
                    Text(parseMarkdownBold(t),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6,
                    )
                }
            }
        }
    }
}

// ── 비교 종목 피커 시트 ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparePickerSheet(
    currentCode: String,
    watchlist: List<WatchItem>,
    onSelect: (WatchItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text("비교할 종목 선택",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            HorizontalDivider()
            watchlist.filter { it.code != currentCode }.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                onSelect(item)
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        Text(item.code, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

// ── 포맷 헬퍼 ────────────────────────────────────────────────────────────────

private fun fmtPrice(p: Long): String =
    NumberFormat.getNumberInstance(Locale.KOREA).format(p)

private fun fmtDec(v: Double): String =
    if (v == v.toLong().toDouble()) "${v.toLong()}" else String.format("%.1f", v)

private fun rateText(r: Double) = "${if (r >= 0) "+" else ""}${String.format("%.2f", r)}%"
private fun rateColor(r: Double) = when {
    r > 0 -> ChangeUp; r < 0 -> ChangeDown; else -> Color.Gray
}

private fun week52Label(pct: Double) = when {
    pct < 20 -> "저점권"; pct < 40 -> "저중간"; pct < 60 -> "중간"
    pct < 80 -> "고중간"; else -> "고점권"
}

private fun valuationColor(label: String?) = when {
    label == null -> Color.Unspecified
    label.contains("저평가") -> ChangeDown
    label.contains("고평가") -> ChangeUp
    else -> Color.Unspecified
}

private fun upsideText(pct: Double?) =
    if (pct == null) "-" else "${if (pct >= 0) "+" else ""}${String.format("%.1f", pct)}%"

private fun upsideColor(pct: Double?) = when {
    pct == null -> Color.Gray
    pct >= 5 -> ChangeUp; pct <= -5 -> ChangeDown; else -> Color.Unspecified
}

private fun flowText(v: Long): String {
    if (v == 0L) return "보합"
    val a = abs(v); val sign = if (v > 0) "+" else "-"
    return when {
        a >= 100_000_000 -> "$sign${String.format("%.0f", a / 100_000_000.0)}억"
        a >= 10_000 -> "$sign${a / 10_000}만"
        else -> "$sign$a"
    }
}
private fun flowColor(v: Long) = when { v > 0 -> ChangeUp; v < 0 -> ChangeDown; else -> Color.Gray }

private fun yoyText(pct: Double?) =
    if (pct == null) "-" else "${if (pct >= 0) "+" else ""}${String.format("%.1f", pct)}%"

private fun yoyColor(pct: Double?) = when {
    pct == null -> Color.Gray
    pct > 10 -> ChangeUp; pct < -10 -> ChangeDown; else -> Color.Unspecified
}
