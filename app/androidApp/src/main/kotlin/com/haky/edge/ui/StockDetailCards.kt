package com.haky.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haky.edge.model.AnalogHorizon
import com.haky.edge.model.AnalogReport
import com.haky.edge.model.Analysis
import com.haky.edge.model.Backtest
import com.haky.edge.model.CatalystItem
import com.haky.edge.model.CatalystReport
import androidx.compose.material3.TextButton
import com.haky.edge.model.FactsRichness
import com.haky.edge.model.EarningsEntry
import com.haky.edge.model.FlowCorrelation
import com.haky.edge.model.FlowSensitivity
import com.haky.edge.model.ShortSellingSummary
import com.haky.edge.model.PeerMetric
import com.haky.edge.model.PeerValuation
import com.haky.edge.model.PriceLimits
import com.haky.edge.model.SignalResult
import com.haky.edge.model.StockImpact
import com.haky.edge.model.StockWarning
import com.haky.edge.model.ValuationBand
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.EdgeTheme
import com.haky.edge.ui.theme.OrangeAccent
import com.haky.edge.ui.theme.PurpleAccent
import kotlin.math.abs

private val CardShape = RoundedCornerShape(12.dp)

// 접이식 카드 공통 래퍼. 제목 행 탭 → 펼침/접기. trailing = 우측 배지 등.
@Composable
internal fun CollapsibleCard(
    title: String,
    leadingEmoji: String? = null,
    leadingIcon: ImageVector? = null,
    initiallyExpanded: Boolean = false,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, CardShape)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                leadingIcon != null -> Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                leadingEmoji != null -> Text(leadingEmoji, style = MaterialTheme.typography.bodyMedium)
            }
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            trailing?.invoke(this)
            Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            HorizontalDivider()
            Column(modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) { content() }
        }
    }
}

// 선명한 방향 화살표(채운 삼각형). 유니코드 화살표가 얇아 잘 안 보이는 문제 해결.
@Composable
private fun ArrowGlyph(up: Boolean, color: Color, sizeDp: androidx.compose.ui.unit.Dp = 11.dp) {
    Canvas(modifier = Modifier.size(sizeDp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val w = size.width * 0.36f
        val h = size.height * 0.30f
        val p = androidx.compose.ui.graphics.Path().apply {
            if (up) { moveTo(cx, cy - h); lineTo(cx - w, cy + h); lineTo(cx + w, cy + h) }
            else { moveTo(cx, cy + h); lineTo(cx - w, cy - h); lineTo(cx + w, cy - h) }
            close()
        }
        drawPath(p, color)
    }
}

@Composable
private fun BadgePill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ─── 투자유의 칩 (시장경보·단기과열·정리매매·VI, 토스 기반) ─────────────
// 한투엔 없는 데이터. 발동 항목이 있을 때만 가격 바로 아래 노출. 위험도 높은 순(danger→warn→info) 정렬.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WarningChips(warnings: List<StockWarning>) {
    if (warnings.isEmpty()) return
    val order = mapOf("danger" to 0, "warn" to 1, "info" to 2)
    val sorted = warnings.sortedBy { order[it.severity] ?: 9 }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sorted.forEach { w ->
            val c = warningColor(w.severity)
            Text(
                w.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = c,
                modifier = Modifier
                    .background(c.copy(alpha = 0.15f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun warningColor(severity: String): Color = when (severity) {
    "danger" -> EdgeTheme.colors.up       // 빨강 (위험·경고·정리매매)
    "warn"   -> EdgeTheme.colors.orange   // 주황 (단기과열)
    else      -> EdgeTheme.colors.neutral // 회색 (VI 등 참고)
}

// ─── 가격 제한폭(상·하한가, 토스) ─────────────
// 현재가 대비 상/하한가 여력 %. 제한폭 도달 시 칩. 제한폭 없는 시장(미국 등)은 호출부에서 숨김.
@Composable
internal fun PriceLimitsLine(limits: PriceLimits, currentPrice: Long) {
    val upper = limits.upper
    val lower = limits.lower
    if (upper == null || lower == null || currentPrice <= 0L) return
    val upPct = (upper - currentPrice).toDouble() / currentPrice * 100
    val lowPct = (lower - currentPrice).toDouble() / currentPrice * 100
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            currentPrice >= upper -> BadgePill("상한가 도달", EdgeTheme.colors.up)
            currentPrice <= lower -> BadgePill("하한가 도달", EdgeTheme.colors.down)
        }
        Text(
            "상한가 ${upper.fmt()} (${"%+.1f%%".format(upPct)})  ·  하한가 ${lower.fmt()} (${"%+.1f%%".format(lowPct)})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── 뉴스·공시 영향 (호재/악재 판정) — 접이식 ─────────────
// 뉴스·공시를 카드 단위로 호재/악재·강도·선반영까지 판정. 접어도 netBias 배지로 결론은 보인다.
// Claude 호출이라 로딩이 느릴 수 있음(백엔드 30분 캐시 적중 시 즉시).

@Composable
internal fun CatalystCard(report: CatalystReport?, loading: Boolean, attempted: Boolean, onRetry: () -> Unit) {
    // 로드 실패(Claude 오류/타임아웃): 뉴스·공시 원문 섹션을 없앴으므로 빈 화면 방지용 폴백.
    if (report == null && !loading && attempted) {
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, CardShape).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("뉴스·공시 영향을 불러오지 못했어요",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("다시 시도") }
        }
        return
    }
    if (report == null && !loading) return
    val uri = LocalUriHandler.current
    CollapsibleCard(
        title = "뉴스·공시 영향",
        leadingEmoji = "📊",
        trailing = {
            if (report != null) BadgePill(report.netBias, netBiasColor(report.netBias))
            else CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        },
    ) {
        if (report != null) {
            if (report.summary.isNotEmpty()) {
                Text(report.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (report.items.isEmpty()) {
                Text("최근 7일 새 재료(공시·뉴스)가 없습니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                report.items.forEach { c ->
                    HorizontalDivider()
                    CatalystRow(c) { runCatching { uri.openUri(c.url) } }
                }
            }
        } else {
            Text("재료 분석 중…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CatalystRow(c: CatalystItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BadgePill("${c.sentiment} ${c.strength}", sentimentColor(c.sentiment))
            Text(c.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(c.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(catalystDate(c), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(c.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (c.reason.isNotEmpty()) {
            Text(c.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (c.preReflected) {
            val note = c.preReflectedNote?.let { " · $it" } ?: ""
            Text("⚠ 선반영 가능성$note", style = MaterialTheme.typography.labelSmall, color = OrangeAccent)
        }
    }
}

// 공시는 YYYYMMDD, 뉴스는 RFC822 → 표기 분기.
private fun catalystDate(c: CatalystItem): String =
    if (c.source == "공시") formattedDate8(c.date) else shortDate(c.date)

@Composable
private fun sentimentColor(s: String): Color = when (s) {
    "호재" -> ChangeUp    // 한국 관례: 상승/호재 = 빨강
    "악재" -> ChangeDown
    else   -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun netBiasColor(b: String): Color = when (b) {
    "호재우위" -> ChangeUp
    "악재우위" -> ChangeDown
    else       -> MaterialTheme.colorScheme.onSurfaceVariant
}

// "Wed, 04 Jun 2026 10:30:00 +0900" → "06/04 10:30" (실패 시 앞 16자)
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private fun shortDate(raw: String): String {
    // "EEE, dd MMM yyyy HH:mm:ss Z" 형태 직접 파싱.
    return runCatching {
        val parts = raw.split(" ")
        val day = parts[1]
        val mon = (MONTHS.indexOf(parts[2]) + 1).toString().padStart(2, '0')
        val time = parts[4].substring(0, 5)
        "$mon/$day $time"
    }.getOrElse { raw.take(16) }
}

// ─── 밸류에이션 히스토리 밴드 ────────────────────────────

@Composable
internal fun ValuationBandCard(band: ValuationBand) {
    val showPer = band.perCurrent > 0 && band.perMax > band.perMin
    val showPbr = band.pbrCurrent > 0 && band.pbrMax > band.pbrMin
    if (!showPer && !showPbr) return
    CollapsibleCard(
        title = "밸류에이션 히스토리",
        trailing = { Text("${band.yearsUsed}년 밴드", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showPer) ValuationBandRow("PER", band.perCurrent, band.perMin, band.perMax, band.perMedian, band.perLabel)
            if (showPbr) {
                if (showPer) HorizontalDivider()
                ValuationBandRow("PBR", band.pbrCurrent, band.pbrMin, band.pbrMax, band.pbrMedian, band.pbrLabel)
            }
            Text("연도말 종가 기준, 상장주식수 근사치 — 분할·증자 시 오차 가능", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun valuationBandColor(label: String): Color = when (label) {
    "역사적 하단권" -> ChangeDown
    "역사적 상단권" -> ChangeUp
    else -> OrangeAccent  // 중간권·계산 불가
}

@Composable
private fun ValuationBandRow(name: String, current: Double, bandMin: Double, bandMax: Double, median: Double, label: String) {
    val color = valuationBandColor(label)
    val frac = if (bandMax > bandMin) ((current - bandMin) / (bandMax - bandMin)).toFloat().coerceIn(0f, 1f) else 0.5f
    val medFrac = if (bandMax > bandMin) ((median - bandMin) / (bandMax - bandMin)).toFloat().coerceIn(0f, 1f) else 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.2f배".format(current), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            BadgePill(label, color)
        }
        RangeBar(frac, medFrac, color, modifier = Modifier.fillMaxWidth().height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("%.1f배".format(bandMin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("중앙 %.1f배".format(median), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.1f배".format(bandMax), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// 범위 바: 회색 트랙 + 중앙값 눈금 + 현재값 컬러 마커.
@Composable
private fun RangeBar(fraction: Float, medianFraction: Float, markerColor: Color, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val trackH = 6.dp.toPx()
        val trackY = (size.height - trackH) / 2f
        drawRoundRect(trackColor.copy(alpha = 0.18f), Offset(0f, trackY), Size(size.width, trackH), CornerRadius(3.dp.toPx()))
        // 중앙값 눈금
        val midX = (size.width * medianFraction).coerceIn(0f, size.width - 1.5.dp.toPx())
        drawRect(trackColor.copy(alpha = 0.5f), Offset(midX, size.height / 2f - 5.dp.toPx()), Size(1.5.dp.toPx(), 10.dp.toPx()))
        // 현재값 마커
        val markW = 3.dp.toPx()
        val markX = (size.width - markW) * fraction
        drawRoundRect(markerColor, Offset(markX, size.height / 2f - 7.dp.toPx()), Size(markW, 14.dp.toPx()), CornerRadius(2.dp.toPx()))
    }
}

// ─── 동종(peer) 상대 밸류 ────────────────────────────────

@Composable
internal fun PeerValuationCard(pv: PeerValuation) {
    if (pv.per == null && pv.pbr == null) return
    CollapsibleCard(
        title = "동종 상대 밸류",
        trailing = { Text("${pv.clusterLabel} · 경쟁사 ${pv.peerCount}곳", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pv.per?.let { PeerMetricRow("PER", it) }
            pv.pbr?.let {
                if (pv.per != null) HorizontalDivider()
                PeerMetricRow("PBR", it)
            }
            Text("같은 사업 경쟁사 중앙값과 비교 — KIS 기준값, 상대 위치 참고용", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun peerValuationColor(label: String): Color = when (label) {
    "동종 대비 낮음" -> ChangeDown  // 동종보다 싼 편
    "동종 대비 높음" -> ChangeUp
    else -> OrangeAccent           // 비슷
}

@Composable
private fun PeerMetricRow(name: String, m: PeerMetric) {
    val color = peerValuationColor(m.label)
    val lo = minOf(m.peerMin, m.current)
    val hi = maxOf(m.peerMax, m.current)
    val frac = if (hi > lo) ((m.current - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) else 0.5f
    val medFrac = if (hi > lo) ((m.peerMedian - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) else 0.5f
    val diff = "%s%.0f%%".format(if (m.diffPct >= 0) "+" else "", m.diffPct)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.2f배".format(m.current), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            Text("($diff)", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.weight(1f))
            BadgePill(m.label, color)
        }
        RangeBar(frac, medFrac, color, modifier = Modifier.fillMaxWidth().height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("동종 최저 %.1f".format(m.peerMin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("중앙 %.1f배".format(m.peerMedian), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("최고 %.1f".format(m.peerMax), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── 검증된 신호 (백테스트) ──────────────────────────────

@Composable
internal fun BacktestCard(bt: Backtest) {
    val shown = bt.signals.filter { it.n > 0 }
    if (shown.isEmpty()) return
    CollapsibleCard(
        title = "검증된 신호",
        trailing = { Text("최근 ${bt.tradingDays}거래일 실측", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val baseAvg = "%+.2f".format(bt.baselineAvgReturn)
            Text(
                "평소 익일 상승확률 ${bt.baselineWinRate}% · 평균 ${baseAvg}% (세로선=평소 기준)",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            shown.forEachIndexed { idx, s ->
                if (idx > 0) HorizontalDivider()
                BacktestRow(s, bt.baselineWinRate)
            }
            Text("이 종목 과거 통계일 뿐 미래를 보장하지 않아요. 표본(n)이 작으면 참고만 하세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BacktestRow(s: SignalResult, baseline: Int) {
    val edgeUp = s.edge >= 0
    val accent = if (edgeUp) ChangeUp else ChangeDown
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.signal, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            Text("표본 ${s.n}일", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (s.confident) BadgePill("${if (edgeUp) "+" else ""}%.1f%%p".format(s.edge), accent)
            else BadgePill("표본 부족", EdgeTheme.colors.neutral)
        }
        ProbabilityBar(
            winFraction = s.winRate / 100f,
            baselineFraction = baseline / 100f,
            color = if (s.confident) accent else EdgeTheme.colors.neutral,
            dimmed = !s.confident,
            baselineColor = onSurface,
            modifier = Modifier.fillMaxWidth().height(10.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("익일 상승확률 ${s.winRate}%", style = MaterialTheme.typography.labelSmall, color = if (s.confident) onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("평균 %+.2f%%".format(s.avgReturn), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// 익일 상승확률 바 + 평소(baseline) 세로선.
@Composable
private fun ProbabilityBar(winFraction: Float, baselineFraction: Float, color: Color, dimmed: Boolean, baselineColor: Color, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val trackH = 6.dp.toPx()
        val trackY = (size.height - trackH) / 2f
        drawRoundRect(trackColor.copy(alpha = 0.18f), Offset(0f, trackY), Size(size.width, trackH), CornerRadius(3.dp.toPx()))
        drawRoundRect(color.copy(alpha = if (dimmed) 0.4f else 0.7f), Offset(0f, trackY), Size(size.width * winFraction.coerceIn(0f, 1f), trackH), CornerRadius(3.dp.toPx()))
        val bx = (size.width * baselineFraction).coerceIn(0f, size.width - 1.5.dp.toPx())
        drawRect(baselineColor.copy(alpha = 0.5f), Offset(bx, size.height / 2f - 5.dp.toPx()), Size(1.5.dp.toPx(), 10.dp.toPx()))
    }
}

// ─── 매수 프리모템 (F5) ──────────────────────────────────

@Composable
internal fun PremortemCard(pm: com.haky.edge.model.Premortem) {
    val activeCount = pm.invalidations.count { it.active }
    CollapsibleCard(
        title = "매수 가설 점검",
        trailing = { Text("감시 중 ${activeCount}개", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pm.reason.isNotBlank()) {
                Text("매수 사유: ${pm.reason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pm.bullCase.isNotBlank()) Text("맞다면: ${pm.bullCase}", style = MaterialTheme.typography.bodySmall)
            if (pm.bearCase.isNotBlank()) Text("틀렸다면: ${pm.bearCase}", style = MaterialTheme.typography.bodySmall)
            if (pm.invalidations.isNotEmpty()) {
                HorizontalDivider()
                Text("무효화 조건", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                pm.invalidations.forEach { inv ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (inv.active) "👁" else "⚠️", style = MaterialTheme.typography.labelSmall)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(inv.description, style = MaterialTheme.typography.bodySmall,
                                color = if (inv.active) MaterialTheme.colorScheme.onSurface else OrangeAccent)
                            inv.anchor?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            inv.firedAt?.let { Text("발동됨 · ${it.take(10)}", style = MaterialTheme.typography.labelSmall, color = OrangeAccent) }
                        }
                    }
                }
            }
            Text("가설이 틀렸음을 빨리 알기 위한 조건이에요. 발동해도 매매 지시가 아니라 점검 신호예요.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── 유사 국면 통계 (F1) ─────────────────────────────────

@Composable
internal fun AnalogCard(an: AnalogReport) {
    if (an.n <= 0) return
    CollapsibleCard(
        title = "유사 국면 통계",
        trailing = { Text("과거 ${an.n}개 국면 실측", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            an.vectorToday?.let { v ->
                Text(
                    "오늘 상태: 52주 ${v.pos52w.toInt()}% · 20일 %+.1f%% · 거래량 %.1f배 · RSI ${v.rsi14.toInt()}".format(v.ret20, v.volumeRatio),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            an.horizons.forEachIndexed { idx, h ->
                if (idx > 0) HorizontalDivider()
                AnalogHorizonRow(h)
            }
            Text(an.caveat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnalogHorizonRow(h: AnalogHorizon) {
    val up = h.median >= 0
    val accent = if (up) ChangeUp else ChangeDown
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${h.days}일 후", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            BadgePill("중앙값 %+.1f%%".format(h.median), accent)
        }
        ProbabilityBar(
            winFraction = (h.winRate / 100f).toFloat(),
            baselineFraction = 0.5f,
            color = accent,
            dimmed = false,
            baselineColor = onSurface,
            modifier = Modifier.fillMaxWidth().height(10.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("상승 확률 ${h.winRate.toInt()}%", style = MaterialTheme.typography.labelSmall, color = onSurface)
            Text("평균 %+.1f%% · 범위 %.1f~%+.1f%%".format(h.avg, h.min, h.max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── 수급-가격 민감도 ────────────────────────────────────

@Composable
internal fun FlowSensitivityCard(fs: FlowSensitivity) {
    val shown = fs.items.filter { it.n > 0 }
    if (shown.isEmpty()) return
    val days = shown.first().n
    CollapsibleCard(
        title = "수급-가격 민감도",
        trailing = { Text("최근 ${days}거래일 기준", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("외인·기관이 많이 살수록 이 종목 주가가 그날 같이 올랐나요?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.forEachIndexed { idx, fc ->
                if (idx > 0) HorizontalDivider()
                FlowCorrRow(fc)
            }
            Text("수급은 전일까지 장후 확정값 기준이에요. 과거 통계라 미래를 보장하지 않아요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FlowCorrRow(fc: FlowCorrelation) {
    val absR = abs(fc.r)
    val isPositive = fc.r >= 0
    val accent = if (absR < 0.1) EdgeTheme.colors.neutral else if (isPositive) ChangeUp else ChangeDown
    val active = fc.confident && absR >= 0.1
    val plainLabel = when {
        !fc.confident -> "표본 부족"
        absR < 0.1 -> "별 관계 없어요"
        isPositive -> if (absR < 0.3) "조금 같이 올라요" else if (absR < 0.5) "어느 정도 같이 올라요" else "강하게 같이 올라요"
        else -> if (absR < 0.3) "조금 반대로 움직여요" else if (absR < 0.5) "어느 정도 반대로 움직여요" else "강하게 반대로 움직여요"
    }
    val desc: String? = if (active) {
        if (isPositive) "${fc.investor}이 많이 살수록 그날 주가가 같이 오른 경향이에요"
        else "${fc.investor}이 많이 살수록 그날 주가가 오히려 내린 경향이에요"
    } else null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fc.investor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            Text("${fc.n}일", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            BadgePill(plainLabel, if (active) accent else EdgeTheme.colors.neutral)
        }
        StrengthBar(absR.coerceIn(0.0, 1.0).toFloat(), if (fc.confident) accent else EdgeTheme.colors.neutral, !fc.confident, modifier = Modifier.fillMaxWidth().height(6.dp))
        desc?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun StrengthBar(fraction: Float, color: Color, dimmed: Boolean, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        drawRoundRect(trackColor.copy(alpha = 0.18f), size = size, cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(color.copy(alpha = if (dimmed) 0.4f else 0.7f), size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

// ─── 공매도 동향 ─────────────────────────────────────────

@Composable
internal fun ShortSellingCard(ss: ShortSellingSummary) {
    CollapsibleCard(title = "공매도 동향") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 설명 (배경 박스 없이 다른 카드와 통일)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(parseMarkdownBold("공매도는 **주식을 빌려서 파는** 것이에요."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("지금 비싸게 팔고 → 나중에 싸게 사서 갚아 차익을 얻는 방식이라, 하락에 베팅하는 세력이 많을수록 공매도 잔고가 늘어나요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("잔고 증가", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = ChangeUp)
                        Text("하락 베팅 강화\n단기 하락 압력", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("잔고 감소", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = ChangeDown)
                        Text("숏커버링(청산 매수)\n단기 상승 압력", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("잔고는 T+2일 지연 확정이라, 최신 2거래일은 '집계 중'으로 보여요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 수치
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("공매도 거래량", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${formatShortVol(ss.recentVolume)}주", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(ss.recentVolumeDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(modifier = Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("공매도 잔고", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val bal = ss.balance
                    if (bal != null) {
                        Text("${formatShortVol(bal)}주", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ss.balanceChangePct?.let { pct ->
                                val isUp = pct > 0.5; val isDown = pct < -0.5
                                val c = if (isUp) ChangeUp else if (isDown) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant
                                if (isUp || isDown) ArrowGlyph(isUp, c)
                                val pctStr = "%.1f".format(pct)
                                Text("${if (pct >= 0) "+" else ""}$pctStr%", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c)
                            }
                            ss.balanceDate?.let { Text("($it 확정)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    } else {
                        Text("집계 중", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("T+2일 지연", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun formatShortVol(vol: Long): String =
    if (vol >= 10_000) "%.1f만".format(vol / 10_000.0) else vol.fmt()

// ─── 실적 일정 ───────────────────────────────────────────

@Composable
internal fun EarningsCard(e: EarningsEntry) {
    val days = e.daysUntil
    CollapsibleCard(
        title = "실적 일정",
        leadingIcon = Icons.Filled.CalendarToday,
        trailing = { Text(ddayBadge(days), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = ddayColor(days)) },
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(e.reportName, style = MaterialTheme.typography.bodySmall)
                Text("제출 기한: ${formattedDate8(e.dueDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BadgePill(ddayBadge(days), ddayColor(days))
        }
    }
}

private fun ddayBadge(days: Int): String = when {
    days == 0 -> "D-day"
    days > 0 -> "D-$days"
    else -> "D+${abs(days)}"
}

@Composable
private fun ddayColor(days: Int): Color = when {
    days < 14 -> ChangeUp
    days < 30 -> OrangeAccent
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ─── 지표 영향 ───────────────────────────────────────────

@Composable
internal fun MacroSignalCard(sig: StockImpact) {
    CollapsibleCard(
        title = "지표 영향",
        leadingIcon = Icons.Filled.TrendingUp,
        trailing = { if (sig.net != "-") BadgePill(sig.net, netColor(sig.net)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("섹터: ${sig.sectorLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                BadgePill(sig.net, netColor(sig.net))
            }
            if (sig.signals.isEmpty()) {
                Text("아직 지원하지 않는 종목이에요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sig.signals.forEach { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.padding(top = 3.dp)) {
                            if (s.direction != 0) ArrowGlyph(s.direction > 0, directionColor(s.direction))
                            else Text("→", style = MaterialTheme.typography.labelSmall, color = directionColor(s.direction))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("${s.indicator} ${signedPct(s.changeRate)}%", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                            Text(s.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun netColor(net: String): Color = when (net) {
    "우호적" -> ChangeUp
    "부담" -> ChangeDown
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun signalArrow(d: Int): String = if (d > 0) "↑" else if (d < 0) "↓" else "→"

@Composable
private fun directionColor(d: Int): Color = if (d > 0) ChangeUp else if (d < 0) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant

private fun signedPct(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v)

// "20260814" → "2026.08.14"
private fun formattedDate8(d: String): String =
    if (d.length != 8) d else "${d.substring(0, 4)}.${d.substring(4, 6)}.${d.substring(6, 8)}"

// ─── 지표 해석 (계산 기반, LLM 없음) ────────────────────

@Composable
internal fun InterpretationCard(
    quote: com.haky.edge.model.Quote,
    flows: List<com.haky.edge.model.InvestorFlow>,
    targetPrice: com.haky.edge.model.TargetPriceInfo?,
) {
    val ctx = com.haky.edge.analysis.StockAnalysis.priceContext(quote)
    val streaks = com.haky.edge.analysis.StockAnalysis.flowStreaks(flows)
    val hasValuation = quote.per > 0 || quote.pbr > 0
    if (ctx == null && !hasValuation && targetPrice == null && streaks.isEmpty()) return
    var helpExpanded by remember { mutableStateOf(false) }
    CollapsibleCard(title = "지표 해석") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ctx != null) {
                RangeGauge(ctx.pctInRange52w)
                InsightRow("52주 고점 대비", "%.1f%%".format(ctx.pctFromHigh52w))
                InsightRow("52주 저점 대비", "+%.1f%%".format(ctx.pctFromLow52w))
            }
            if (hasValuation) {
                if (ctx != null) HorizontalDivider()
                if (quote.sectorName.isNotEmpty()) InsightRow("업종", quote.sectorName)
                if (quote.per > 0) ValuationRow("PER", "%.2f배".format(quote.per), "이 회사가 지금처럼 벌면 몇 년 치 이익이 쌓여야 지금 주가만큼 되는지예요. 낮을수록 버는 것에 비해 주가가 싼 편이고, 성장 기대가 크면 높게 매겨져요.", helpExpanded)
                if (quote.pbr > 0) ValuationRow("PBR", "%.2f배".format(quote.pbr), "회사가 가진 재산(장부가치) 대비 주가예요. 1배면 딱 장부가치 수준, 낮을수록 자산 대비 싼 편.", helpExpanded)
                Text(
                    if (helpExpanded) "ⓘ 설명 접기 ▲" else "ⓘ PER·PBR이 뭐죠? ▼",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { helpExpanded = !helpExpanded }.padding(vertical = 2.dp),
                )
            }
            targetPrice?.let { tp ->
                if (ctx != null || hasValuation) HorizontalDivider()
                val upside = (tp.price - quote.price).toDouble() / quote.price * 100
                val c = if (upside >= 5) ChangeUp else if (upside < -5) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("컨센서스 목표주가", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("${tp.price.fmt()}원", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
                        Text(
                            "  ${if (upside >= 0) "▲" else "▼"}${"%.1f".format(abs(upside))}%",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = c,
                        )
                    }
                    Text(tp.basis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (streaks.isNotEmpty()) {
                if (ctx != null || hasValuation) HorizontalDivider()
                streaks.forEach { s ->
                    val sc = if (s.buying) ChangeUp else ChangeDown
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(6.dp).background(sc, androidx.compose.foundation.shape.CircleShape))
                        Text("${s.investor} ${s.days}일 연속 ${if (s.buying) "순매수" else "순매도"}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text("누적 ${flowAbbrev(s.net)}", style = MaterialTheme.typography.labelMedium, color = sc)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ValuationRow(label: String, value: String, meaning: String, expanded: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
        }
        if (expanded) Text(meaning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RangeGauge(pct: Double) {
    val color = when {
        pct < 25 -> ChangeDown
        pct < 75 -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> ChangeUp
    }
    val label = when {
        pct < 25 -> "저점권"
        pct < 50 -> "중하단"
        pct < 75 -> "중상단"
        else -> "고점권"
    }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("52주 위치", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${pct.toInt()}%  $label", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = color)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(5.dp)) {
            drawRoundRect(trackColor.copy(alpha = 0.18f), size = size, cornerRadius = CornerRadius(size.height / 2f))
            val w = (size.width * (pct / 100.0)).toFloat().coerceAtLeast(5f)
            drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2f))
        }
    }
}

// 순매수 누적량 축약(부호 포함): +1.2억 / +14만 / +234.
private fun flowAbbrev(n: Long): String {
    if (n == 0L) return "0"
    val sign = if (n > 0) "+" else "-"
    val a = abs(n).toDouble()
    return when {
        a >= 1e8 -> sign + "%.1f억".format(a / 1e8)
        a >= 1e4 -> sign + "%.0f만".format(a / 1e4)
        else -> sign + abs(n).fmt()
    }
}

// ─── AI 종합 코멘트 (C5) ─────────────────────────────────

// **굵게** + 한글 경계 버그 회피: CommonMark 파서 대신 정규식으로 직접 AnnotatedString 빌드.
// (iOS는 NSRegularExpression 직접 파싱 — 커밋 4a6e1ea. Compose도 동일 전략.)
internal fun parseMarkdownBold(s: String): AnnotatedString {
    // ### 헤딩 → **bold** 변환 (AI 응답에서 hash 헤딩 쓸 때)
    var text = Regex("^#{1,3} +(.+)$", RegexOption.MULTILINE).replace(s) { "**${it.groupValues[1]}**" }
    // ~~취소선~~ 은 내용만 남기고 제거
    text = Regex("~~(.+?)~~").replace(text) { it.groupValues[1] }
    text = text.replace("~~", "")
    return buildAnnotatedString {
        var cursor = 0
        for (m in Regex("""\*\*(.+?)\*\*""").findAll(text)) {
            if (m.range.first > cursor) append(text.substring(cursor, m.range.first).replace("**", ""))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor).replace("**", ""))
    }
}

internal data class CommentSection(val heading: String?, val body: List<String>)

// "\n\n" 블록 분리 후 **소제목**만 있는 줄을 헤더로, 이어지는 블록을 본문으로 묶음.
internal fun parseCommentSections(comment: String): List<CommentSection> {
    val blocks = comment.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() && it != "---" }
    val sections = mutableListOf<CommentSection>()
    var heading: String? = null
    var body = mutableListOf<String>()
    fun flush() {
        if (heading != null || body.isNotEmpty()) sections.add(CommentSection(heading, body.toList()))
        heading = null
        body = mutableListOf()
    }
    for (b in blocks) {
        val h = headingOnly(b)
        if (h != null) { flush(); heading = h } else body.add(b)
    }
    flush()
    return sections
}

private fun headingOnly(s: String): String? {
    if (!s.startsWith("**") || !s.endsWith("**") || s.length <= 4) return null
    val inner = s.substring(2, s.length - 2)
    if (inner.contains("**") || inner.contains("\n") || inner.length > 20) return null
    return inner
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AICommentCard(
    analysis: Analysis?,
    analyzing: Boolean,
    aggressive: Boolean,
    onRegenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = PurpleAccent)
            Text("AI 종합 코멘트", style = MaterialTheme.typography.titleSmall)
            if (aggressive) BadgePill("⚔️ 공격적 모드", OrangeAccent)
            Spacer(modifier = Modifier.weight(1f))
            if (analyzing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }

        if (analysis != null) {
            // 핵심 요약 — 풀 코멘트 위 강조 박스(보라). summary 없으면(옛 캐시) 건너뜀.
            analysis.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PurpleAccent.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(14.dp), tint = PurpleAccent)
                        Text("핵심 요약", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = PurpleAccent)
                    }
                    Text(
                        parseMarkdownBold(summary.trim()),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            val sections = remember(analysis.comment) { parseCommentSections(analysis.comment) }
            val collapsible = sections.size > 2
            var expanded by remember(analysis.comment) { mutableStateOf(false) }
            val visible = if (collapsible && !expanded) sections.take(2) else sections

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(PurpleAccent.copy(alpha = 0.35f), RoundedCornerShape(2.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    visible.forEach { sec ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            sec.heading?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = PurpleAccent)
                            }
                            sec.body.forEach { p ->
                                Text(
                                    parseMarkdownBold(p),
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            if (collapsible) {
                Text(
                    if (expanded) "접기 ▲" else "더보기 ▼",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = PurpleAccent,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp),
                )
            }

            analysis.factsRichness?.let { FactsRichnessRow(it) }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(aiCommentFreshLabel(analysis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (!analyzing) {
                        Text("↻ 재생성", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = PurpleAccent, modifier = Modifier.clickable { onRegenerate() })
                    }
                }
                Text("투자 판단과 책임은 본인에게 있습니다", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (analyzing) {
            Text("시세·수급·뉴스를 종합해 코멘트를 생성하고 있어요…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("코멘트를 불러오지 못했어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("↻ 다시 시도", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = PurpleAccent, modifier = Modifier.clickable { onRegenerate() }.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactsRichnessRow(r: FactsRichness) {
    val chips = listOf(
        (if (r.newsCount > 0) "뉴스 ${r.newsCount}건" else "뉴스 없음") to (r.newsCount > 0),
        "수급" to r.hasInvestorFlow,
        "연간재무" to r.hasFinancials,
        "분기실적" to r.hasQuarterlyIncome,
        "공매도" to r.hasShortSelling,
        "밸류밴드" to r.hasValuationBand,
        "백테스트" to r.hasBacktest,
        "수급민감도" to r.hasFlowSensitivity,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("근거 데이터", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            chips.forEach { (label, on) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (on) PurpleAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background((if (on) PurpleAccent else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// "참고용 · 오늘 HH:mm 생성 · N,NNN원 기준"
private fun aiCommentFreshLabel(a: Analysis): String {
    var label = if (a.generatedAt.isNotEmpty()) {
        if (todayYmd() == a.date) "참고용 · 오늘 ${a.generatedAt} 생성" else "참고용 · ${a.date} ${a.generatedAt} 생성"
    } else "참고용 · ${a.date} 기준"
    a.generatedPrice?.let { gp ->
        val price = gp.toLong()
        if (price > 0) label += " · ${price.fmt()}원 기준"
    }
    return label
}

private fun todayYmd(): String {
    val c = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
}
