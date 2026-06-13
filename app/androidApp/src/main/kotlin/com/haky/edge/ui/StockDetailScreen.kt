package com.haky.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.Quote
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    item: WatchItem,
    initialQuote: Quote?,
    watchlistRepo: WatchlistRepository,
    actionLogRepo: ActionLogRepository,
    api: EdgeApi,
    onBack: () -> Unit,
) {
    var watchItem by remember { mutableStateOf(item) }
    var quote by remember { mutableStateOf(initialQuote) }
    var dailyBars by remember { mutableStateOf<List<com.haky.edge.model.DailyBar>>(emptyList()) }
    var chartPeriod by remember { mutableStateOf(ChartPeriod.M3) }
    var trendHelpExpanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var showPositionSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            try { quote = api.getQuote(watchItem.code) } catch (_: Exception) {}
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(watchItem.code) {
        try { dailyBars = api.getDaily(watchItem.code, bars = 160) } catch (_: Exception) {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(watchItem.name) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = { showPositionSheet = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "포지션 입력")
                    }
                    IconButton(onClick = { refresh() }) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                        }
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PriceHeader(code = watchItem.code, quote = quote)
            quote?.let { q ->
                PriceChartCard(
                    quote = q,
                    bars = dailyBars,
                    period = chartPeriod,
                    onPeriodChange = { chartPeriod = it },
                    avg = watchItem.avgPrice,
                    target = watchItem.targetPrice,
                    stop = watchItem.stopPrice,
                    trendHelpExpanded = trendHelpExpanded,
                    onTrendHelpToggle = { trendHelpExpanded = !trendHelpExpanded },
                )
            }
            PositionCard(
                item = watchItem,
                quote = quote,
                onEditClick = { showPositionSheet = true },
            )
        }
    }

    if (showPositionSheet) {
        PositionInputSheet(
            item = watchItem,
            watchlistRepo = watchlistRepo,
            onDismiss = { showPositionSheet = false },
            onSave = { updated ->
                watchItem = updated
                showPositionSheet = false
            },
        )
    }
}

// ─── 현재가 헤더 ─────────────────────────────────────────

@Composable
private fun PriceHeader(code: String, quote: Quote?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (quote != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = quote.price.fmt() + "원",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(4.dp))
            val chgColor = when {
                quote.changeRate > 0 -> ChangeUp
                quote.changeRate < 0 -> ChangeDown
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${if (quote.change >= 0) "▲" else "▼"} ${abs(quote.change).fmt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = chgColor,
                )
                Text(
                    text = "(%+.2f%%)".format(quote.changeRate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = chgColor,
                )
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text("불러오는 중...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── 가격 흐름 카드 (차트 + 시세) ────────────────────────

@Composable
private fun PriceChartCard(
    quote: Quote,
    bars: List<com.haky.edge.model.DailyBar>,
    period: ChartPeriod,
    onPeriodChange: (ChartPeriod) -> Unit,
    avg: Double?,
    target: Double?,
    stop: Double?,
    trendHelpExpanded: Boolean,
    onTrendHelpToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (bars.isNotEmpty() || period == ChartPeriod.TODAY) {
            Text("가격 흐름", style = MaterialTheme.typography.titleSmall)
            val opts = ChartPeriod.entries
            SegmentedToggle(
                options = opts.map { if (it == ChartPeriod.ALL) allPeriodLabel(bars.size) else it.label },
                selectedIndex = opts.indexOf(period),
                onSelect = { onPeriodChange(opts[it]) },
            )

            if (period == ChartPeriod.TODAY) {
                TodaySummary(quote, bars)
            } else if (bars.isNotEmpty()) {
                ChartLegend(expanded = trendHelpExpanded, onToggle = onTrendHelpToggle)
                PriceLineChart(
                    bars = bars,
                    displayCount = period.barCount,
                    avg = avg,
                    target = target,
                    stop = stop,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("거래량", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("빨강 = 평소 2배↑", style = MaterialTheme.typography.labelSmall, color = ChangeUp.copy(alpha = 0.65f))
                }
                VolumeBars(
                    bars = bars,
                    displayCount = period.barCount,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            }
            HorizontalDivider()
        }
        // 시세 그리드
        val gridRows = listOf(
            listOf("거래량" to quote.volume.fmt(), "시가" to quote.open.fmt()),
            listOf("고가" to quote.high.fmt(), "저가" to quote.low.fmt()),
            listOf("52주 최고" to quote.high52w.fmt(), "52주 최저" to quote.low52w.fmt()),
        )
        gridRows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { idx, (label, value) ->
                    if (idx > 0) Spacer(modifier = Modifier.width(16.dp))
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
}

// "전체" 라벨 — 거래일 수 기준 개월/년 환산 (22거래일 ≈ 1개월). iOS allPeriodLabel 대응.
private fun allPeriodLabel(barCount: Int): String {
    if (barCount <= 0) return "전체"
    val months = barCount / 22
    return when {
        months < 1 -> "전체"
        months < 12 -> "${months}개월"
        else -> "%.1f년".format(months / 12.0)
    }
}

// ─── 차트 범례 ───────────────────────────────────────────

@Composable
private fun ChartLegend(expanded: Boolean, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 고저 폭
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    modifier = Modifier
                        .size(width = 12.dp, height = 8.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
                )
                Text("고저 폭", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LegendLine("종가", MaterialTheme.colorScheme.onSurface, dash = false)
            LegendLine("추세선", OrangeAccent, dash = true)
            Text(
                "ⓘ",
                style = MaterialTheme.typography.labelSmall,
                color = OrangeAccent.copy(alpha = 0.8f),
                modifier = Modifier.clickable { onToggle() }.padding(2.dp),
            )
        }
        if (expanded) {
            Text(
                "추세선(주황 점선): 최근 20거래일 종가 평균. 현재가가 위면 단기 상승추세, 아래면 하락추세.\n고저 폭(회색 띠): 각 날의 하루 중 가격 변동 범위(고가~저가).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendLine(label: String, color: Color, dash: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (dash) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { Box(modifier = Modifier.size(width = 3.dp, height = 1.5.dp).background(color)) }
            }
        } else {
            Box(modifier = Modifier.size(width = 12.dp, height = 2.dp).background(color))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── 오늘 탭 요약 ────────────────────────────────────────

@Composable
private fun TodaySummary(q: Quote, bars: List<com.haky.edge.model.DailyBar>) {
    val avg20Vol = run {
        val recent = bars.take(20)
        if (recent.isEmpty()) 0.0 else recent.sumOf { it.volume.toDouble() } / recent.size
    }
    val volRatio = if (avg20Vol > 0) q.volume.toDouble() / avg20Vol else 0.0
    val priceUp = q.changeRate >= 0
    val asOf = bars.firstOrNull()?.date?.let { tradingDayLabel(it) }?.takeIf { it.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (asOf != null) {
            Text("$asOf 기준", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 시가 / 현재가
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OhlcStat("시가", q.open.fmt())
            Column(horizontalAlignment = Alignment.End) {
                Text("${q.price.fmt()} 원", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(
                    "${if (q.changeRate >= 0) "+" else ""}%.2f%%".format(q.changeRate),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (priceUp) ChangeUp else ChangeDown,
                )
            }
        }
        // 고가 / 저가
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OhlcStat("고가", q.high.fmt(), ChangeUp)
            OhlcStat("저가", q.low.fmt(), ChangeDown)
        }
        // 장중 위치 게이지
        if (q.high > q.low) {
            val pos = (q.price - q.low).toFloat() / (q.high - q.low).toFloat()
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Canvas(modifier = Modifier.fillMaxWidth().height(9.dp)) {
                    val trackH = 5.dp.toPx()
                    drawRoundRect(
                        color = Color.LightGray.copy(alpha = 0.4f),
                        topLeft = Offset(0f, (size.height - trackH) / 2f),
                        size = Size(size.width, trackH),
                        cornerRadius = CornerRadius(trackH / 2f),
                    )
                    val dotR = 4.5.dp.toPx()
                    val cx = (size.width - dotR * 2) * pos.coerceIn(0f, 1f) + dotR
                    drawCircle(if (priceUp) ChangeUp else ChangeDown, dotR, Offset(cx, size.height / 2f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("저 ${q.low.fmt()}", style = MaterialTheme.typography.labelSmall, color = ChangeDown)
                    Text("현재 위치", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("고 ${q.high.fmt()}", style = MaterialTheme.typography.labelSmall, color = ChangeUp)
                }
            }
        }
        HorizontalDivider()
        // 거래량 + 해석
        if (q.open == 0L) {
            Text(
                "장 시작 전 거래 데이터가 없어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("거래량", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${q.volume.fmt()}주", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                    if (avg20Vol > 0) {
                        Text("(평소의 %.1f배)".format(volRatio), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val intradayPos = if (q.high > q.low) (q.price - q.low).toDouble() / (q.high - q.low) else null
                val (emoji, title, desc) = volPriceSignal(priceUp, volRatio, intradayPos)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun OhlcStat(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = valueColor)
    }
}

// 최근 일봉 날짜(YYYYMMDD) → "M/d(요일)". iOS tradingDayLabel 대응.
private fun tradingDayLabel(ymd8: String): String {
    if (ymd8.length != 8) return ""
    val y = ymd8.substring(0, 4).toIntOrNull() ?: return ""
    val mo = ymd8.substring(4, 6).toIntOrNull() ?: return ""
    val d = ymd8.substring(6, 8).toIntOrNull() ?: return ""
    val cal = java.util.Calendar.getInstance().apply { set(y, mo - 1, d) }
    val dow = arrayOf("일", "월", "화", "수", "목", "금", "토")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    return "$mo/$d($dow)"
}

// 거래량×가격 방향 추론 (8케이스). iOS volPriceSignal 대응.
private fun volPriceSignal(priceUp: Boolean, ratio: Double, intradayPos: Double?): Triple<String, String, String> {
    val r = "%.1f".format(ratio)
    val tier = if (ratio >= 2.5) 3 else if (ratio >= 1.5) 2 else if (ratio >= 0.7) 1 else 0
    val posNote = when {
        intradayPos == null -> ""
        intradayPos >= 0.75 -> " 고가권에서 마감해 강세가 끝까지 유지됐어요."
        intradayPos <= 0.25 -> " 저가권에서 마감해 낙폭을 회복하지 못했어요."
        else -> ""
    }
    return when {
        priceUp && tier == 3 -> Triple("🚀", "폭발적 매수세", "평소의 ${r}배 거래량이 터지며 올랐어요. 기관·세력의 대량 매수가 들어왔을 가능성이 높아요. 다음 날 추가 상승인지 차익실현인지가 핵심이에요.$posNote")
        priceUp && tier == 2 -> Triple("📈", "강한 매수세", "거래량이 ${r}배로 실리며 가격이 올랐어요. 상승에 힘이 있는 날이에요. 거래량이 계속 동반되는지 확인해 보세요.$posNote")
        priceUp && tier == 1 -> Triple("↗️", "조심스러운 상승", "거래량 없이 올랐어요. 매수 주체가 약해 다음 날 되돌릴 수 있어요. 내일 거래량이 늘며 가격이 버텨주는지가 포인트예요.$posNote")
        priceUp -> Triple("🌤️", "거래위축 상승", "평소보다 거래가 적은데 올랐어요. 매도 압력이 약해 오른 것으로, 추세로 이어지려면 거래량이 동반돼야 해요.$posNote")
        tier == 3 -> Triple("💥", "투매성 하락", "평소의 ${r}배 거래량이 터지며 내렸어요. 대량 매도가 출회된 날이에요. 악재 확인이 필요하고, 단기 반등을 노린 저가 매수가 들어올 수도 있어요.$posNote")
        tier == 2 -> Triple("📉", "강한 매도세", "거래량이 ${r}배로 실리며 가격이 내렸어요. 하락에 힘이 실린 날이에요. 지지선을 이탈했는지 확인해 보세요.$posNote")
        tier == 1 -> Triple("↘️", "완만한 하락", "평범한 거래량에 소폭 내렸어요. 뚜렷한 악재보다는 차익실현이나 관망 분위기예요. 거래량이 터지지 않으면 추세 하락은 아닐 수 있어요.$posNote")
        else -> Triple("😴", "소강 하락", "거래도 적고 가격도 내렸어요. 뚜렷한 매도 주체 없이 관심이 식는 신호일 수 있어요. 거래량이 줄면서 하락하는 패턴은 장기 추세 약화 시그널이에요.$posNote")
    }
}

// ─── 포지션 카드 ─────────────────────────────────────────

@Composable
private fun PositionCard(item: WatchItem, quote: Quote?, onEditClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("내 포지션", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onEditClick) {
                Text(if (item.avgPrice == null) "입력" else "수정", style = MaterialTheme.typography.bodySmall)
            }
        }

        val price = quote?.price?.toDouble()
        val avgD = item.avgPrice
        val qtyL = item.qty

        if (avgD != null && qtyL != null && price != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            val qtyD = qtyL.toDouble()
            val pnl = (price - avgD) * qtyD
            val rate = if (avgD == 0.0) 0.0 else (price - avgD) / avgD * 100
            val up = pnl >= 0
            PositionRow("평단가", avgD.toLong().fmt() + "원")
            PositionRow("수량", qtyL.fmt() + "주")
            PositionRow("평가금액", (price * qtyD).toLong().fmt() + "원")
            PositionRow("평가손익", "${if (up) "+" else ""}${pnl.toLong().fmt()}원", if (up) ChangeUp else ChangeDown)
            PositionRow("수익률", "${if (up) "+" else ""}%.2f%%".format(rate), if (up) ChangeUp else ChangeDown)
        } else if (avgD == null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "평단가·수량을 입력하면 내 수익률을 보여줘요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val target = item.targetPrice
        val stop = item.stopPrice
        if ((target != null || stop != null) && price != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (target != null) {
                UpsideGauge(currentPrice = price, targetPrice = target, avgPrice = item.avgPrice, stopPrice = stop)
            } else if (stop != null) {
                val gap = (stop - price) / price * 100
                PositionRow("손절가", "${stop.toLong().fmt()}원  ${if (price <= stop) "⚠️ 도달" else "(%+.1f%%)".format(gap)}")
            }
        }
    }
}

@Composable
private fun PositionRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = valueColor)
    }
}

// ─── 목표가 상승여력 게이지 ──────────────────────────────

@Composable
private fun UpsideGauge(
    currentPrice: Double,
    targetPrice: Double,
    avgPrice: Double?,
    stopPrice: Double?,
) {
    val upside = (targetPrice - currentPrice) / currentPrice * 100
    val reached = currentPrice >= targetPrice
    val anchor = stopPrice ?: avgPrice ?: minOf(currentPrice * 0.85, targetPrice * 0.75)
    val range = maxOf(targetPrice - anchor, 1.0)
    val progress = ((currentPrice - anchor) / range).coerceIn(0.0, 1.05).toFloat()
    val fillColor = if (reached) Color(0xFF34C759) else OrangeAccent

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("목표가까지", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (reached) "🎯 도달" else "%+.1f%%".format(upside),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (reached) Color(0xFF34C759) else if (upside < 5) OrangeAccent else Color.Unspecified,
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            val trackH = 5.dp.toPx()
            val trackY = (size.height - trackH) / 2f
            val tickH = 14.dp.toPx()
            val tickW = 2.5.dp.toPx()
            val radius = trackH / 2f
            val fillW = (size.width * progress).coerceAtLeast(5f)
            drawRoundRect(
                color = Color.LightGray.copy(alpha = 0.5f),
                topLeft = Offset(0f, trackY),
                size = Size(size.width, trackH),
                cornerRadius = CornerRadius(radius),
            )
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(0f, trackY),
                size = Size(fillW, trackH),
                cornerRadius = CornerRadius(radius),
            )
            val tickX = (fillW - tickW / 2f).coerceAtLeast(0f)
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(tickX, (size.height - tickH) / 2f),
                size = Size(tickW, tickH),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(anchor.toLong().fmt() + "원", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = if (stopPrice != null) "손절" else if (avgPrice != null) "평단" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stopPrice != null) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(targetPrice.toLong().fmt() + "원", style = MaterialTheme.typography.labelSmall)
                Text("목표", style = MaterialTheme.typography.labelSmall, color = ChangeUp)
            }
        }
    }
}

// ─── 숫자 포맷 헬퍼 ──────────────────────────────────────

internal fun Long.fmt(): String = String.format(Locale.US, "%,d", this)
