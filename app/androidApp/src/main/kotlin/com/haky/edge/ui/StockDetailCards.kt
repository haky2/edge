package com.haky.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haky.edge.model.Backtest
import com.haky.edge.model.DartDisclosure
import com.haky.edge.model.EarningsEntry
import com.haky.edge.model.FlowCorrelation
import com.haky.edge.model.FlowSensitivity
import com.haky.edge.model.NewsItem
import com.haky.edge.model.ShortSellingSummary
import com.haky.edge.model.SignalResult
import com.haky.edge.model.StockImpact
import com.haky.edge.model.ValuationBand
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import kotlin.math.abs

private val CardShape = RoundedCornerShape(12.dp)

// 접이식 카드 공통 래퍼. 제목 행 탭 → 펼침/접기. trailing = 우측 배지 등.
@Composable
internal fun CollapsibleCard(
    title: String,
    leadingEmoji: String? = null,
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
            leadingEmoji?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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

// ─── 관련 뉴스 ───────────────────────────────────────────

@Composable
internal fun NewsCard(news: List<NewsItem>) {
    val uri = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("관련 뉴스", style = MaterialTheme.typography.titleSmall)
        news.forEachIndexed { idx, a ->
            Column(
                modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(a.url) } }.padding(vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(a.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(shortDate(a.publishedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (idx < news.size - 1) HorizontalDivider()
        }
    }
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

private fun valuationBandColor(label: String): Color = when (label) {
    "역사적 저평가" -> ChangeDown
    "역사적 고평가" -> ChangeUp
    else -> OrangeAccent
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
    Canvas(modifier = modifier) {
        val trackH = 6.dp.toPx()
        val trackY = (size.height - trackH) / 2f
        drawRoundRect(Color.Gray.copy(alpha = 0.18f), Offset(0f, trackY), Size(size.width, trackH), CornerRadius(3.dp.toPx()))
        // 중앙값 눈금
        val midX = (size.width * medianFraction).coerceIn(0f, size.width - 1.5.dp.toPx())
        drawRect(Color.Gray.copy(alpha = 0.5f), Offset(midX, size.height / 2f - 5.dp.toPx()), Size(1.5.dp.toPx(), 10.dp.toPx()))
        // 현재값 마커
        val markW = 3.dp.toPx()
        val markX = (size.width - markW) * fraction
        drawRoundRect(markerColor, Offset(markX, size.height / 2f - 7.dp.toPx()), Size(markW, 14.dp.toPx()), CornerRadius(2.dp.toPx()))
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
            Text(
                "평소 익일 상승확률 ${bt.baselineWinRate}% · 평균 %+.2f%% (세로선=평소 기준)".format(bt.baselineAvgReturn),
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
            else BadgePill("표본 부족", Color.Gray)
        }
        ProbabilityBar(
            winFraction = s.winRate / 100f,
            baselineFraction = baseline / 100f,
            color = if (s.confident) accent else Color.Gray,
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
    Canvas(modifier = modifier) {
        val trackH = 6.dp.toPx()
        val trackY = (size.height - trackH) / 2f
        drawRoundRect(Color.Gray.copy(alpha = 0.18f), Offset(0f, trackY), Size(size.width, trackH), CornerRadius(3.dp.toPx()))
        drawRoundRect(color.copy(alpha = if (dimmed) 0.4f else 0.7f), Offset(0f, trackY), Size(size.width * winFraction.coerceIn(0f, 1f), trackH), CornerRadius(3.dp.toPx()))
        val bx = (size.width * baselineFraction).coerceIn(0f, size.width - 1.5.dp.toPx())
        drawRect(baselineColor.copy(alpha = 0.5f), Offset(bx, size.height / 2f - 5.dp.toPx()), Size(1.5.dp.toPx(), 10.dp.toPx()))
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
    val accent = if (absR < 0.1) Color.Gray else if (isPositive) ChangeUp else ChangeDown
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
            BadgePill(plainLabel, if (active) accent else Color.Gray)
        }
        StrengthBar(absR.coerceIn(0.0, 1.0).toFloat(), if (fc.confident) accent else Color.Gray, !fc.confident, modifier = Modifier.fillMaxWidth().height(6.dp))
        desc?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun StrengthBar(fraction: Float, color: Color, dimmed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRoundRect(Color.Gray.copy(alpha = 0.18f), size = size, cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(color.copy(alpha = if (dimmed) 0.4f else 0.7f), size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

// ─── 공매도 동향 ─────────────────────────────────────────

@Composable
internal fun ShortSellingCard(ss: ShortSellingSummary) {
    CollapsibleCard(title = "공매도 동향") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 설명 박스
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("공매도는 주식을 빌려서 파는 것이에요.", style = MaterialTheme.typography.bodySmall)
                Text("지금 비싸게 팔고 → 나중에 싸게 사서 갚아 차익을 얻는 방식이라, 하락에 베팅하는 세력이 많을수록 공매도 잔고가 늘어나요.", style = MaterialTheme.typography.bodySmall)
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ss.balanceChangePct?.let { pct ->
                                val isUp = pct > 0.5; val isDown = pct < -0.5
                                val c = if (isUp) ChangeUp else if (isDown) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant
                                Text("${if (isUp) "↑" else if (isDown) "↓" else "−"} ${if (pct >= 0) "+" else ""}%.1f%%".format(pct), style = MaterialTheme.typography.labelSmall, color = c)
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
        leadingEmoji = "📅",
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

// ─── DART 공시 ───────────────────────────────────────────

@Composable
internal fun DartDisclosureCard(list: List<DartDisclosure>) {
    if (list.isEmpty()) return
    val uri = LocalUriHandler.current
    CollapsibleCard(title = "공시 (${list.size}건, 30일)", leadingEmoji = "📄") {
        Column {
            list.forEachIndexed { idx, d ->
                if (idx > 0) HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(d.url) } }.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(d.reportName, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formattedDate8(d.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─── 지표 영향 ───────────────────────────────────────────

@Composable
internal fun MacroSignalCard(sig: StockImpact) {
    CollapsibleCard(
        title = "지표 영향",
        leadingEmoji = "📈",
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
                        Text(signalArrow(s.direction), style = MaterialTheme.typography.labelSmall, color = directionColor(s.direction))
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
