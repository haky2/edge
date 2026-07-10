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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import com.haky.edge.model.ActionLogEntry
import java.text.SimpleDateFormat
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.AccountRepository
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.HoldingRepository
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.AskTurn
import com.haky.edge.model.Quote
import com.haky.edge.model.WatchItem
import androidx.compose.runtime.ReadOnlyComposable
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.EdgeTheme
import com.haky.edge.ui.theme.OrangeAccent
import com.haky.edge.ui.theme.PurpleAccent
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    item: WatchItem,
    initialQuote: Quote?,
    watchlistRepo: WatchlistRepository,
    holdingRepo: HoldingRepository,
    accountRepo: AccountRepository,
    actionLogRepo: ActionLogRepository,
    api: EdgeApi,
    onBack: () -> Unit,
    onCompare: ((WatchItem) -> Unit)? = null,
) {
    // 관심종목 탭 경로의 item은 watchlist 기반이라 포지션 필드가 비어 있다(G1 이후 holding이 정본)
    // → holding을 얹어서 내 포지션 카드·차트 기준선·게이지가 어느 경로로 들어와도 보이게.
    var watchItem by remember { mutableStateOf(holdingRepo.hydrate(item)) }
    var quote by remember { mutableStateOf(initialQuote) }
    var warnings by remember { mutableStateOf<List<com.haky.edge.model.StockWarning>>(emptyList()) }
    var priceLimits by remember { mutableStateOf<com.haky.edge.model.PriceLimits?>(null) }
    var dailyBars by remember { mutableStateOf<List<com.haky.edge.model.DailyBar>>(emptyList()) }
    var flows by remember { mutableStateOf<List<com.haky.edge.model.InvestorFlow>>(emptyList()) }
    var shortSelling by remember { mutableStateOf<com.haky.edge.model.ShortSellingSummary?>(null) }
    var valuationBand by remember { mutableStateOf<com.haky.edge.model.ValuationBand?>(null) }
    var peerValuation by remember { mutableStateOf<com.haky.edge.model.PeerValuation?>(null) }
    var backtest by remember { mutableStateOf<com.haky.edge.model.Backtest?>(null) }
    var analog by remember { mutableStateOf<com.haky.edge.model.AnalogReport?>(null) }
    var premortem by remember { mutableStateOf<com.haky.edge.model.Premortem?>(null) }
    var tradeReview by remember { mutableStateOf<com.haky.edge.model.TradeReview?>(null) }
    var deepResearch by remember { mutableStateOf<com.haky.edge.model.DeepResearch?>(null) }
    var deepResearchLoading by remember { mutableStateOf(false) }
    var flowSensitivity by remember { mutableStateOf<com.haky.edge.model.FlowSensitivity?>(null) }
    var earnings by remember { mutableStateOf<com.haky.edge.model.EarningsEntry?>(null) }
    var stockSignal by remember { mutableStateOf<com.haky.edge.model.StockImpact?>(null) }
    var targetPrice by remember { mutableStateOf<com.haky.edge.model.TargetPriceInfo?>(null) }
    var analysis by remember { mutableStateOf<com.haky.edge.model.Analysis?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var catalysts by remember { mutableStateOf<com.haky.edge.model.CatalystReport?>(null) }
    var catalystImpact by remember { mutableStateOf<com.haky.edge.model.CatalystImpact?>(null) }
    var catalystsLoading by remember { mutableStateOf(false) }
    var catalystAttempted by remember { mutableStateOf(false) }
    var chartPeriod by remember { mutableStateOf(ChartPeriod.M3) }
    var trendHelpExpanded by remember { mutableStateOf(false) }
    var indicatorHelpExpanded by remember { mutableStateOf(false) }
    var logEntries by remember { mutableStateOf<List<ActionLogEntry>>(emptyList()) }
    var showLogSheet by remember { mutableStateOf(false) }
    var showAskSheet by remember { mutableStateOf(false) }
    val technical = remember(dailyBars) {
        if (dailyBars.isNotEmpty()) com.haky.edge.analysis.TechnicalIndicators.calculate(dailyBars) else null
    }
    var loading by remember { mutableStateOf(false) }
    var showPositionSheet by remember { mutableStateOf(false) }
    var showComparePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            try { quote = api.getQuote(watchItem.code) } catch (_: Exception) {}
            loading = false
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    fun loadAnalysis(force: Boolean) {
        scope.launch {
            analyzing = true
            if (force) analysis = null
            val avg = watchItem.avgPrice
            val qty = watchItem.qty
            val mode = AppPrefs.getMode(context)
            try {
                analysis = if (avg != null && qty != null) {
                    api.getAnalysisPersonalized(
                        watchItem.code, avg, qty,
                        watchItem.targetPrice ?: 0.0, watchItem.stopPrice ?: 0.0,
                        mode = mode, refresh = force, thesis = watchItem.thesis,
                    )
                } else {
                    api.getAnalysis(watchItem.code, mode = mode, refresh = force, thesis = watchItem.thesis)
                }
            } catch (_: Exception) {}
            analyzing = false
        }
    }

    fun reloadLogs() { logEntries = actionLogRepo.getByCode(watchItem.code, 10) }

    fun loadCatalysts(force: Boolean) {
        scope.launch {
            catalystsLoading = true
            if (force) catalysts = null
            try { catalysts = api.getCatalysts(watchItem.code, days = 7, refresh = force) } catch (_: Exception) {}
            catalystsLoading = false
            catalystAttempted = true
            // F2 임팩트 통계 — 로딩 상태 별도 없음(데이터 있으면 표시, 없으면 숨김)
            try { catalystImpact = api.getCatalystImpact(watchItem.code) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(watchItem.code) { reloadLogs() }
    LaunchedEffect(watchItem.code) { loadAnalysis(false) }
    LaunchedEffect(watchItem.code) { loadCatalysts(false) }
    LaunchedEffect(watchItem.code) {
        try { dailyBars = api.getDaily(watchItem.code, bars = 160) } catch (_: Exception) {}
    }
    LaunchedEffect(watchItem.code) {
        try { flows = api.getInvestorFlow(watchItem.code, days = 5) } catch (_: Exception) {}
    }
    LaunchedEffect(watchItem.code) {
        warnings = try { api.getWarnings(watchItem.code) } catch (_: Exception) { emptyList() }
    }
    LaunchedEffect(watchItem.code) {
        priceLimits = try { api.getPriceLimits(watchItem.code) } catch (_: Exception) { null }
    }
    LaunchedEffect(watchItem.code) {
        val code = watchItem.code
        try { shortSelling = api.getShortSelling(code) } catch (_: Exception) {}
        try { valuationBand = api.getValuationBand(code) } catch (_: Exception) {}
        try { peerValuation = api.getPeerValuation(code) } catch (_: Exception) {}
        try { backtest = api.getBacktest(code) } catch (_: Exception) {}
        try { analog = api.getAnalog(code) } catch (_: Exception) {}
        try { flowSensitivity = api.getFlowSensitivity(code) } catch (_: Exception) {}
        try { earnings = api.getEarnings(listOf(code)).firstOrNull() } catch (_: Exception) {}
        try { stockSignal = api.getStockSignals(code) } catch (_: Exception) {}
        try { targetPrice = api.getTargetPrice(code) } catch (_: Exception) {}
        try { premortem = api.getPremortem(code) } catch (_: Exception) {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(watchItem.name) },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (onCompare != null) {
                        IconButton(onClick = { showComparePicker = true }) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = "종목 비교")
                        }
                    }
                    IconButton(
                        onClick = {
                            if (!deepResearchLoading) {
                                scope.launch {
                                    deepResearchLoading = true
                                    deepResearch = runCatching { api.getDeepResearch(watchItem.code) }.getOrNull()
                                    deepResearchLoading = false
                                }
                            }
                        },
                    ) {
                        if (deepResearchLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = "딥리서치")
                        }
                    }
                    IconButton(onClick = { showAskSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "질문하기")
                    }
                    IconButton(onClick = { showLogSheet = true }) {
                        Icon(Icons.Filled.Flag, contentDescription = "매매 기록")
                    }
                    IconButton(onClick = { showPositionSheet = true }) {
                        Icon(Icons.Filled.EditNote, contentDescription = "포지션 입력")
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
            WarningChips(warnings)
            quote?.let { q -> priceLimits?.let { PriceLimitsLine(it, q.price) } }
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
            AICommentCard(
                analysis = analysis,
                analyzing = analyzing,
                aggressive = AppPrefs.getMode(context) == "aggressive",
                onRegenerate = { loadAnalysis(true) },
            )
            val q = quote
            if (technical != null && q != null) {
                TechnicalCard(
                    r = technical,
                    price = q.price.toDouble(),
                    helpExpanded = indicatorHelpExpanded,
                    onHelpToggle = { indicatorHelpExpanded = !indicatorHelpExpanded },
                )
            }
            if (flows.isNotEmpty()) FlowCard(flows)
            // 뉴스·공시는 판정 카드 하나로 일원화(원문 뉴스/공시 섹션 제거). 링크는 카드 안에서 원문으로.
            CatalystCard(
                report = catalysts,
                loading = catalystsLoading,
                attempted = catalystAttempted,
                impact = catalystImpact,
                onRetry = { loadCatalysts(true) },
            )
            quote?.let { InterpretationCard(it, flows, targetPrice) }
            valuationBand?.let { ValuationBandCard(it) }
            peerValuation?.let { PeerValuationCard(it) }
            backtest?.let { BacktestCard(it) }
            analog?.let { AnalogCard(it) }
            flowSensitivity?.let { FlowSensitivityCard(it) }
            shortSelling?.let { ShortSellingCard(it) }
            earnings?.let { EarningsCard(it) }
            stockSignal?.let { MacroSignalCard(it) }
            premortem?.let { PremortemCard(it) }
            if (logEntries.isNotEmpty()) LogCard(
                entries = logEntries,
                onDelete = { id ->
                    actionLogRepo.delete(id)
                    logEntries = logEntries.filter { it.id != id }
                },
            )
            tradeReview?.let { TradeReviewCard(it) }
            deepResearch?.let { DeepResearchCard(it) }
            if (deepResearchLoading && deepResearch == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "딥리서치 생성 중… (수십 초 소요)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAskSheet) {
        StockAskSheet(
            item = watchItem,
            api = api,
            mode = AppPrefs.getMode(context),
            onDismiss = { showAskSheet = false },
        )
    }

    if (showLogSheet) {
        ActionLogSheet(
            code = watchItem.code,
            name = watchItem.name,
            currentPrice = quote?.price ?: 0L,
            logRepo = actionLogRepo,
            onDismiss = { showLogSheet = false },
            onSaved = {
                showLogSheet = false
                reloadLogs()
            },
            premortemEnabled = true,
            onBuyWithPremortem = { reason ->
                // 부모 스코프는 시트가 닫혀도 살아있음 → Claude 생성이 끝나면 카드에 바로 반영.
                scope.launch {
                    runCatching {
                        api.createPremortem(watchItem.code, reason, watchItem.avgPrice, watchItem.qty, watchItem.stopPrice)
                    }.getOrNull()?.let { premortem = it }
                }
            },
            onSellWithTradeReview = { sellReason ->
                scope.launch {
                    val logs = actionLogRepo.getByCode(watchItem.code, 10)
                    val buyLog = logs.filter { it.action == "buy" && it.price != null }.firstOrNull()
                    if (buyLog != null && buyLog.price != null && (quote?.price ?: 0L) > 0L) {
                        val buyDate = epochToISO(buyLog.createdAt)
                        val sellDate = epochToISO(System.currentTimeMillis())
                        runCatching {
                            api.postTradeReview(
                                code = watchItem.code,
                                buyDate = buyDate,
                                buyPrice = buyLog.price!!.toLong().toDouble(),
                                sellDate = sellDate,
                                sellPrice = (quote?.price ?: 0L).toDouble(),
                                qty = null,
                                buyReason = buyLog.reason,
                                sellReason = sellReason.ifBlank { null },
                                thesis = watchItem.thesis,
                            )
                        }.getOrNull()?.let { tradeReview = it }
                    }
                }
            },
        )
    }

    if (showPositionSheet) {
        PositionInputSheet(
            item = watchItem,
            holdingRepo = holdingRepo,
            accountRepo = accountRepo,
            watchlistRepo = watchlistRepo,
            onDismiss = { showPositionSheet = false },
            onSave = { updated ->
                watchItem = updated
                showPositionSheet = false
            },
        )
    }

    if (showComparePicker && onCompare != null) {
        ComparePickerSheet(
            currentCode = watchItem.code,
            watchlist = watchlistRepo.all(),
            onSelect = { selected ->
                showComparePicker = false
                onCompare(selected)
            },
            onDismiss = { showComparePicker = false },
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
            val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
            val dotColor = if (priceUp) ChangeUp else ChangeDown
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Canvas(modifier = Modifier.fillMaxWidth().height(9.dp)) {
                    val trackH = 5.dp.toPx()
                    drawRoundRect(
                        color = trackColor.copy(alpha = 0.3f),
                        topLeft = Offset(0f, (size.height - trackH) / 2f),
                        size = Size(size.width, trackH),
                        cornerRadius = CornerRadius(trackH / 2f),
                    )
                    val dotR = 4.5.dp.toPx()
                    val cx = (size.width - dotR * 2) * pos.coerceIn(0f, 1f) + dotR
                    drawCircle(dotColor, dotR, Offset(cx, size.height / 2f))
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
            Text(
                if (item.avgPrice == null) "입력" else "수정",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = PurpleAccent,
                modifier = Modifier.clickable { onEditClick() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
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

        if (!item.thesis.isNullOrEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "투자 논지",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(item.thesis!!, style = MaterialTheme.typography.bodySmall)
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
    val progress = ((currentPrice - anchor) / range).coerceIn(0.0, 1.0).toFloat()
    val fillColor = if (reached) EdgeTheme.colors.success else OrangeAccent
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("목표가까지", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (reached) "🎯 도달" else "%+.1f%%".format(upside),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (reached) EdgeTheme.colors.success else if (upside < 5) OrangeAccent else Color.Unspecified,
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
                color = trackColor.copy(alpha = 0.35f),
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
            val tickX = (fillW - tickW / 2f).coerceIn(0f, size.width - tickW)
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

// ─── 기술적 지표 카드 ────────────────────────────────────

@Composable
private fun TechnicalCard(
    r: com.haky.edge.analysis.TechnicalResult,
    price: Double,
    helpExpanded: Boolean,
    onHelpToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("기술적 지표", style = MaterialTheme.typography.titleSmall)

        // 추세 신호등 + RSI 게이지
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TrendSignal(r, price)
            if (r.rsi14 != null) {
                Box(modifier = Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                RsiGauge(r.rsi14!!, modifier = Modifier.weight(1f))
            }
        }

        r.volumeRatio?.let { VolumeBadge(it) }

        // 접이식 설명
        Column {
            Row(
                modifier = Modifier.clickable { onHelpToggle() }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (helpExpanded) "ⓘ 설명 접기 ▲" else "ⓘ 이게 무슨 뜻이죠? ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (helpExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpItem("추세 신호등", "최근 5·20·60일 평균값보다 지금 주가가 위에 있으면 빨강↑(오름세), 아래면 파랑↓(내림세)예요. 셋 다 빨강이면 단기·중기·장기 모두 상승 흐름.")
                    r.rsi14?.let { v ->
                        HelpItem("RSI ${"%.0f".format(v)}", "주가가 얼마나 달아올랐는지 0~100으로 보는 막대예요. 70 넘으면 좀 과열(🔴), 30 밑이면 너무 식음(🔵). 지금은 ${rsiPlainLabel(v)}.")
                    }
                    r.volumeRatio?.let { v ->
                        HelpItem("거래량 ${"%.1f".format(v)}배", "최근 거래일 거래량을 최근 20일 평균과 비교한 거예요. 2배 넘으면 평소보다 사람이 확 몰린 것 — 큰 뉴스나 수급 변화 신호일 수 있어요.")
                    }
                    if (r.ma5 != null || r.ma20 != null || r.ma60 != null) {
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            r.ma5?.let { MaValueChip("5일", it) }
                            r.ma20?.let { MaValueChip("20일", it) }
                            r.ma60?.let { MaValueChip("60일", it) }
                        }
                    }
                }
            }
        }
    }
}

// 추세 신호등: MA5/20/60 각각 현재가가 위면 빨강↑·아래면 파랑↓ 점.
@Composable
private fun TrendSignal(r: com.haky.edge.analysis.TechnicalResult, price: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("추세", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrendDot("MA5", r.ma5, price)
            TrendDot("MA20", r.ma20, price)
            TrendDot("MA60", r.ma60, price)
        }
    }
}

@Composable
private fun TrendDot(label: String, ma: Double?, price: Double) {
    val above = ma?.let { price >= it }
    val circleColor = when (above) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        true -> ChangeUp
        false -> ChangeDown
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 원 + 흰 삼각형(상승↑/하락↓)을 Canvas로 직접 그려 선명하게.
        Canvas(modifier = Modifier.size(18.dp)) {
            drawCircle(circleColor)
            if (above != null) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val w = size.width * 0.30f
                val h = size.height * 0.26f
                val tri = androidx.compose.ui.graphics.Path().apply {
                    if (above) { moveTo(cx, cy - h); lineTo(cx - w, cy + h); lineTo(cx + w, cy + h) }
                    else { moveTo(cx, cy + h); lineTo(cx - w, cy - h); lineTo(cx + w, cy - h) }
                    close()
                }
                drawPath(tri, Color.White)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// RSI 게이지: 0~100 바 + 30/70 구간 + 현재 위치 마커.
@Composable
private fun RsiGauge(v: Double, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val downColor = ChangeDown
    val upColor = ChangeUp
    val midBand = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = if (v >= 70 || v <= 30) rsiColor(v) else onSurface
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("RSI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.0f".format(v), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = rsiColor(v))
            if (rsiLabel(v).isNotEmpty()) {
                Text(rsiLabel(v), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = rsiColor(v))
            }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            val h = 6.dp.toPx()
            val top = (size.height - h) / 2f
            val w = size.width
            val radius = CornerRadius(h / 2f)
            // 3구간 배경 (clip해서 캡슐 모양)
            drawRoundRect(downColor.copy(alpha = 0.18f), Offset(0f, top), Size(w * 0.3f, h), radius)
            drawRect(midBand.copy(alpha = 0.25f), Offset(w * 0.3f, top), Size(w * 0.4f, h))
            drawRoundRect(upColor.copy(alpha = 0.18f), Offset(w * 0.7f, top), Size(w * 0.3f, h), radius)
            // 현재 위치 마커
            val markR = 5.dp.toPx()
            val cx = (w * (v / 100.0).toFloat()).coerceIn(markR, w - markR)
            drawCircle(bg, markR + 1.5.dp.toPx(), Offset(cx, size.height / 2f))
            drawCircle(markerColor, markR, Offset(cx, size.height / 2f))
        }
    }
}

// 거래량 배지: 평소 대비 배수. 2배↑ 주황 강조.
@Composable
private fun VolumeBadge(v: Double) {
    val hot = v >= 2.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (hot) "🔥" else "📊", style = MaterialTheme.typography.labelSmall)
        Text(
            "거래량 평소의 %.1f배".format(v),
            style = MaterialTheme.typography.bodySmall,
            color = if (hot) OrangeAccent else MaterialTheme.colorScheme.onSurface,
        )
        if (hot) Text("거래 급증", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = OrangeAccent)
    }
}

@Composable
private fun HelpItem(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
        Text(body, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MaValueChip(label: String, v: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v.toLong().fmt(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
    }
}

private fun rsiPlainLabel(v: Double): String = when {
    v >= 70 -> "좀 달아오른 편이에요"
    v <= 30 -> "많이 식은 편이에요"
    else -> "적당한 편이에요"
}

@Composable
private fun rsiColor(v: Double): Color = when {
    v >= 70 -> ChangeUp
    v <= 30 -> ChangeDown
    else -> Color.Unspecified
}

private fun rsiLabel(v: Double): String = when {
    v >= 70 -> "과매수권"
    v <= 30 -> "과매도권"
    else -> ""
}

// ─── 수급 카드 ───────────────────────────────────────────

private val FlowForeign: Color // 외인 주황
    @Composable @ReadOnlyComposable get() = EdgeTheme.colors.orange
private val FlowInstitution: Color // 기관 청록
    @Composable @ReadOnlyComposable get() = EdgeTheme.colors.teal

@Composable
private fun FlowCard(flows: List<com.haky.edge.model.InvestorFlow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("수급 · 순매수", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "전일 확정",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // 범례
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowLegendDot("외인", FlowForeign)
            FlowLegendDot("기관", FlowInstitution)
        }
        FlowBars(flows, modifier = Modifier.fillMaxWidth().height(110.dp))
        HorizontalDivider()
        // 정확 수치표
        FlowTableHeader()
        flows.forEach { f ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mmdd(f.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
                FlowCell(f.foreign, Modifier.weight(1f))
                FlowCell(f.institution, Modifier.weight(1f))
                FlowCell(f.individual, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FlowLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FlowTableHeader() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("날짜", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
        listOf("외국인", "기관", "개인").forEach {
            Text(
                it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FlowCell(n: Long, modifier: Modifier = Modifier) {
    Text(
        flowText(n),
        style = MaterialTheme.typography.labelMedium,
        color = if (n > 0) ChangeUp else if (n < 0) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
        modifier = modifier,
    )
}

// 외인/기관 그룹 막대(0선 기준 상승=위·하락=아래). iOS BarMark position(by:) 대응.
@Composable
private fun FlowBars(flows: List<com.haky.edge.model.InvestorFlow>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val foreignColor = FlowForeign
    val institutionColor = FlowInstitution
    val n = flows.size
    if (n == 0) return
    val maxAbs = flows.flatMap { listOf(abs(it.foreign), abs(it.institution)) }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = secondary)

    Canvas(modifier = modifier) {
        val labelH = 14.dp.toPx()
        val plotH = size.height - labelH
        val zeroY = plotH / 2f
        val slot = size.width / n
        val barW = slot * 0.28f
        val gap = slot * 0.06f
        flows.forEachIndexed { i, f ->
            val center = slot * i + slot / 2f
            fun bar(value: Long, color: Color, xCenter: Float) {
                val h = (zeroY - 2f) * (abs(value).toFloat() / maxAbs)
                if (value >= 0) {
                    drawRect(color.copy(alpha = 0.85f), Offset(xCenter - barW / 2f, zeroY - h), Size(barW, h))
                } else {
                    drawRect(color.copy(alpha = 0.85f), Offset(xCenter - barW / 2f, zeroY), Size(barW, h))
                }
            }
            bar(f.foreign, foreignColor, center - (barW + gap) / 2f)
            bar(f.institution, institutionColor, center + (barW + gap) / 2f)
            // 날짜 라벨
            val txt = measurer.measure(mmdd(f.date), labelStyle)
            drawText(txt, topLeft = Offset(center - txt.size.width / 2f, plotH + (labelH - txt.size.height) / 2f))
        }
        // 0선
        drawLine(secondary.copy(alpha = 0.4f), Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 0.8f)
    }
}

// ─── 행동 기록 카드 ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogCard(
    entries: List<ActionLogEntry>,
    onDelete: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("행동 기록", style = MaterialTheme.typography.titleSmall)
        entries.forEachIndexed { index, entry ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        onDelete(entry.id)
                        true
                    } else false
                },
            )
            SwipeToDismissBox(
                state = dismissState,
                enableDismissFromStartToEnd = false,
                backgroundContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ChangeUp.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(end = 12.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "삭제",
                            tint = ChangeUp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionBadge(entry.action)
                    Spacer(modifier = Modifier.width(8.dp))
                    val entryReason = entry.reason
                    if (entryReason != null) {
                        Text(
                            entryReason,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    val entryPrice = entry.price
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            shortTs(entry.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (entryPrice != null && entryPrice > 0) {
                            Text(
                                "${entryPrice.fmt()}원",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (index < entries.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ActionBadge(action: String) {
    val (label, color) = when (action) {
        "buy" -> "매수" to ChangeUp
        "sell" -> "매도" to EdgeTheme.colors.sell
        else -> "관심" to OrangeAccent
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun shortTs(millis: Long): String =
    SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

private fun epochToISO(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul") }
        .format(java.util.Date(millis))

// ─── 행동 기록 입력 시트 ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionLogSheet(
    code: String,
    name: String?,
    currentPrice: Long,
    logRepo: ActionLogRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    premortemEnabled: Boolean = false,          // F5/B2 토글 노출 여부(api 있을 때만)
    onBuyWithPremortem: (reason: String) -> Unit = {},  // 매수+토글 on 시 부모가 프리모템 생성
    onSellWithTradeReview: (reason: String) -> Unit = {},  // 매도+토글 on 시 부모가 복기 생성(B2)
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedAction by remember { mutableStateOf("interest") }
    var reason by remember { mutableStateOf("") }
    var makePremortem by remember { mutableStateOf(true) }    // 매수 시 무효화 조건 생성(F5)
    var makeTradeReview by remember { mutableStateOf(true) }  // 매도 시 매매 복기 생성(B2)
    val actions = listOf("interest" to "관심", "buy" to "매수", "sell" to "매도")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("행동 기록", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { (id, label) ->
                    val selected = selectedAction == id
                    val bgColor = if (selected) when (id) {
                        "buy" -> ChangeUp
                        "sell" -> EdgeTheme.colors.sell
                        else -> OrangeAccent
                    } else MaterialTheme.colorScheme.surfaceVariant
                    val textColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                    Text(
                        label,
                        modifier = Modifier
                            .background(bgColor, RoundedCornerShape(8.dp))
                            .clickable { selectedAction = id }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("사유 (선택)") },
                placeholder = { Text("왜 관심/매수/매도 하려는지 한 줄로") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (selectedAction == "buy" && premortemEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Switch(checked = makePremortem, onCheckedChange = { makePremortem = it })
                    Text("무효화 조건 만들기", style = MaterialTheme.typography.bodyMedium)
                }
                if (makePremortem) {
                    Text(
                        "AI가 이 매수 논리가 깨지는 조건(지지 이탈·수급 이탈 등)을 만들어 두고, 발동하면 알려줘요. 사유가 구체적일수록 조건이 정확해져요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selectedAction == "sell" && premortemEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Switch(checked = makeTradeReview, onCheckedChange = { makeTradeReview = it })
                    Text("매매 복기 만들기", style = MaterialTheme.typography.bodyMedium)
                }
                if (makeTradeReview) {
                    Text(
                        "AI가 이 매매를 과정/결과 2축으로 복기해줘요. 매수 기록에 사유가 있을수록 정확해져요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("취소") }
                Button(onClick = {
                    logRepo.insert(
                        code = code,
                        name = name,
                        action = selectedAction,
                        reason = reason.ifBlank { null },
                        price = currentPrice,
                    )
                    // F5: 매수 + 토글 on → 부모(생존 스코프)가 프리모템 생성·상태 갱신.
                    if (selectedAction == "buy" && makePremortem && premortemEnabled) onBuyWithPremortem(reason)
                    // B2: 매도 + 토글 on → 부모(생존 스코프)가 복기 생성·상태 갱신.
                    if (selectedAction == "sell" && makeTradeReview && premortemEnabled) onSellWithTradeReview(reason)
                    onSaved()
                }) { Text("저장") }
            }
        }
    }
}

// ─── 종목 Q&A 시트 ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockAskSheet(
    item: WatchItem,
    api: EdgeApi,
    mode: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var turns by remember { mutableStateOf<List<AskTurn>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(turns.size, sending) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    fun sendQuestion() {
        val q = inputText.trim()
        if (q.isEmpty()) return
        errorMsg = null
        sending = true
        val savedInput = q
        inputText = ""
        scope.launch {
            try {
                val ans = api.ask(
                    code = item.code,
                    question = q,
                    avgPrice = item.avgPrice,
                    qty = item.qty,
                    targetPrice = item.targetPrice ?: 0.0,
                    stopPrice = item.stopPrice ?: 0.0,
                    mode = mode,
                    history = turns,
                    thesis = item.thesis,
                )
                turns = turns + AskTurn(question = q, answer = ans.answer)
            } catch (_: Exception) {
                errorMsg = "답변을 불러오지 못했어요. 다시 시도해 주세요."
                inputText = savedInput
            }
            sending = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 헤더
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${item.name} Q&A",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (mode == "aggressive") {
                    Text(
                        "⚔️ 공격적",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = OrangeAccent,
                        modifier = Modifier
                            .background(OrangeAccent.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            HorizontalDivider()

            // 대화 목록
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                if (turns.isEmpty() && !sending) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = PurpleAccent.copy(alpha = 0.4f),
                            )
                            Text(
                                "${item.name}에 대해 무엇이든 물어보세요",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "뉴스·수급·PER·밸류 등 현재 데이터를 기반으로 답변해요",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                items(turns) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 질문 (오른쪽 정렬)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                turn.question,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .background(PurpleAccent.copy(alpha = 0.1f), RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        // 답변 (왼쪽 정렬)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text("✦", color = PurpleAccent, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp))
                            Text(
                                turn.answer,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (sending) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PurpleAccent)
                            Text("답변 생성 중…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                errorMsg?.let { msg ->
                    item {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 입력창
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { if (it.length <= 300) inputText = it },
                    placeholder = { Text("질문 입력 (최대 300자)", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    enabled = !sending,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                )
                IconButton(
                    onClick = { sendQuestion() },
                    enabled = inputText.trim().isNotEmpty() && !sending,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "전송",
                        tint = if (inputText.trim().isNotEmpty() && !sending) PurpleAccent
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}

// 순매수 수량 축약: +1.2억 / +14만 / +234. 부호 포함. iOS flowText 대응.
private fun flowText(n: Long): String {
    if (n == 0L) return "0"
    val sign = if (n > 0) "+" else "-"
    val a = abs(n).toDouble()
    return when {
        a >= 1e8 -> sign + "%.1f억".format(a / 1e8)
        a >= 1e4 -> sign + "%.0f만".format(a / 1e4)
        else -> sign + abs(n).fmt()
    }
}

// "20260602" → "06/02"
private fun mmdd(d: String): String {
    if (d.length != 8) return d
    return "${d.substring(4, 6)}/${d.substring(6, 8)}"
}

// ─── 숫자 포맷 헬퍼 ──────────────────────────────────────

internal fun Long.fmt(): String = String.format(Locale.US, "%,d", this)
