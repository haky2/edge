package com.haky.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.DartDisclosure
import com.haky.edge.model.EarningsEntry
import com.haky.edge.model.MacroIndicator
import com.haky.edge.model.MarketEvent
import com.haky.edge.model.MoodAccuracyReport
import com.haky.edge.model.MoodLogEntry
import com.haky.edge.model.Quote
import com.haky.edge.model.SectorIndex
import com.haky.edge.model.SpotlightStock
import com.haky.edge.model.StockImpact
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.EdgeTheme
import com.haky.edge.ui.theme.OrangeAccent
import com.haky.edge.ui.theme.PurpleAccent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// 브리핑 탭 — iOS BriefingView 풀 포팅 (Batch D). 두 하위 탭: 내 종목 / 시장.
// 섹션은 Android 관례에 맞춰 라운드 Surface 카드로 렌더(관심종목·내자산 화면과 동일 톤).

private val Teal: Color
    @Composable @ReadOnlyComposable get() = EdgeTheme.colors.teal
private val CardShape = RoundedCornerShape(12.dp)

enum class BriefTab(val label: String) { MyStocks("내 종목"), Market("시장") }

private data class QuoteRow(val item: WatchItem, val quote: Quote)
private data class BriefHoldingRow(val item: WatchItem, val avg: Double, val qty: Double, val price: Double) {
    val invested get() = avg * qty
    val evaluated get() = price * qty
}
private data class SupplyRow(val item: WatchItem, val quote: Quote?, val labels: List<String>)
private data class DartItem(val corpName: String, val reportName: String, val date: String, val url: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BriefingScreen(
    api: EdgeApi,
    watchlistRepo: WatchlistRepository,
    selectedTab: BriefTab,
    onSelectTab: (BriefTab) -> Unit,
    onStockClick: (WatchItem, Quote?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aggressive = AppPrefs.getMode(context) == "aggressive"
    var showRefreshMenu by remember { mutableStateOf(false) }
    val loadedAtState = remember { mutableStateOf(0L) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 내 종목 탭
    var topGainers by remember { mutableStateOf<List<QuoteRow>>(emptyList()) }
    var topLosers by remember { mutableStateOf<List<QuoteRow>>(emptyList()) }
    var holdings by remember { mutableStateOf<List<BriefHoldingRow>>(emptyList()) }
    var supplyLoading by remember { mutableStateOf(false) }
    var supplyRows by remember { mutableStateOf<List<SupplyRow>>(emptyList()) }
    var dartLoading by remember { mutableStateOf(false) }
    var dartItems by remember { mutableStateOf<List<DartItem>>(emptyList()) }
    var catalystBriefLoading by remember { mutableStateOf(false) }
    var catalystBriefSectors by remember { mutableStateOf<List<com.haky.edge.model.SectorCatalystLine>>(emptyList()) }

    // 시장 탭
    var moodLoading by remember { mutableStateOf(false) }
    var moodComment by remember { mutableStateOf("") }
    var moodGeneratedAt by remember { mutableStateOf("") }
    var moodDate by remember { mutableStateOf("") }
    var moodExpanded by remember { mutableStateOf(true) }
    var moodAccuracy by remember { mutableStateOf<MoodAccuracyReport?>(null) }

    var impactLoading by remember { mutableStateOf(false) }
    var impactComment by remember { mutableStateOf("") }
    var impactHoldings by remember { mutableStateOf<List<StockImpact>>(emptyList()) }
    var impactWatch by remember { mutableStateOf<List<StockImpact>>(emptyList()) }
    var impactGeneratedAt by remember { mutableStateOf("") }
    var impactDate by remember { mutableStateOf("") }
    var watchlistIsEmpty by remember { mutableStateOf(false) }

    var macroLoading by remember { mutableStateOf(false) }
    var macroItems by remember { mutableStateOf<List<MacroIndicator>>(emptyList()) }

    var sectorLoading by remember { mutableStateOf(false) }
    var sectorItems by remember { mutableStateOf<List<SectorIndex>>(emptyList()) }

    var sectorBriefingLoading by remember { mutableStateOf(false) }
    var sectorBriefingComment by remember { mutableStateOf("") }
    var sectorSpotlight by remember { mutableStateOf<List<SpotlightStock>>(emptyList()) }
    var sectorBriefingGeneratedAt by remember { mutableStateOf("") }
    var sectorBriefingDate by remember { mutableStateOf("") }

    var eventsLoading by remember { mutableStateOf(false) }
    var eventItems by remember { mutableStateOf<List<MarketEvent>>(emptyList()) }

    var earningsLoading by remember { mutableStateOf(false) }
    var earningsItems by remember { mutableStateOf<List<EarningsEntry>>(emptyList()) }

    // quotes 로드 후 저장 (spotlight 행에서 재사용)
    var allItemsLoaded by remember { mutableStateOf<List<WatchItem>>(emptyList()) }
    var quoteMapLoaded by remember { mutableStateOf<Map<String, Quote>>(emptyMap()) }

    val fresh = remember { computeFreshness() }

    // ── 로드 함수들 (closure로 state 캡처) ──
    suspend fun buildMacro() {
        try {
            runCatching { api.getMacro() }.getOrNull()?.let { macroItems = it }
        } finally { macroLoading = false }
    }
    suspend fun buildMarketMood(force: Boolean = false) {
        moodLoading = true
        try {
            val mode = AppPrefs.getMode(context)
            val r = runCatching { api.getMarketMood(mode = mode, refresh = force) }.getOrNull() ?: return
            moodComment = r.comment; moodGeneratedAt = r.generatedAt; moodDate = r.date
        } finally { moodLoading = false }
    }
    suspend fun loadMoodAccuracy() {
        moodAccuracy = runCatching { api.getMoodAccuracy() }.getOrNull()
    }
    suspend fun buildSectors() {
        try {
            runCatching { api.getSectors() }.getOrNull()?.let { sectorItems = it }
        } finally { sectorLoading = false }
    }
    suspend fun buildEarnings(codes: List<String>) {
        try {
            runCatching { api.getEarnings(codes) }.getOrNull()?.let { earningsItems = it }
        } finally { earningsLoading = false }
    }
    suspend fun buildEvents() {
        try {
            var items = runCatching { api.getEvents(30) }.getOrNull() ?: return
            if (items.isEmpty()) {
                runCatching { api.syncEvents() }
                items = runCatching { api.getEvents(30) }.getOrNull() ?: return
            }
            eventItems = items
        } finally { eventsLoading = false }
    }
    suspend fun buildImpact(allItems: List<WatchItem>, force: Boolean = false) {
        impactLoading = true
        try {
            val mode = AppPrefs.getMode(context)
            val holdingItems = allItems.filter { it.avgPrice != null && it.qty != null }
            val holdingCodes = holdingItems.map { it.code }
            val watchCodes = allItems.filter { it.avgPrice == null || it.qty == null }.map { it.code }
            val positions = holdingItems.mapNotNull { item ->
                val a = item.avgPrice; val q = item.qty
                if (a != null && q != null) item.code to (a to q) else null
            }.toMap()
            val impact = runCatching {
                api.getMacroImpact(holdingCodes, watchCodes, mode, positions, force)
            }.getOrNull() ?: return
            impactComment = impact.comment
            impactHoldings = impact.holdings
            impactWatch = impact.watchlist
            impactGeneratedAt = impact.generatedAt
            impactDate = impact.date
        } finally { impactLoading = false }
    }
    suspend fun buildSectorBriefing(codes: List<String>, force: Boolean = false) {
        sectorBriefingLoading = true
        try {
            val r = runCatching { api.getSectorBriefing(codes, force) }.getOrNull() ?: return
            sectorBriefingComment = r.comment
            sectorSpotlight = r.spotlight
            sectorBriefingGeneratedAt = r.generatedAt
            sectorBriefingDate = r.date
        } finally { sectorBriefingLoading = false }
    }
    suspend fun buildSupply(allItems: List<WatchItem>, quoteMap: Map<String, Quote>) {
        try {
            val codes = allItems.map { it.code }
            val flowMap = runCatching { api.getInvestorBatch(codes, 3) }.getOrNull() ?: return
            val result = mutableListOf<SupplyRow>()
            for (item in allItems) {
                val flows = flowMap[item.code] ?: continue
                if (flows.size < 3) continue
                val labels = mutableListOf<String>()
                if (flows[0].foreign > 0 && flows[1].foreign > 0 && flows[2].foreign > 0) labels.add("외인 3일↑")
                if (flows[0].institution > 0 && flows[1].institution > 0 && flows[2].institution > 0) labels.add("기관 3일↑")
                if (labels.isNotEmpty()) result.add(SupplyRow(item, quoteMap[item.code], labels))
            }
            supplyRows = result.sortedBy { it.item.name }
        } finally { supplyLoading = false }
    }
    suspend fun buildDart(codes: List<String>) {
        try {
            val list = runCatching { api.getDartBatch(codes, 7) }.getOrNull() ?: return
            dartItems = list.take(10).map { DartItem(it.corpName, it.reportName, it.date, it.url) }
        } finally { dartLoading = false }
    }

    suspend fun buildCatalystBrief(codes: List<String>) {
        catalystBriefLoading = true
        try {
            if (codes.isEmpty()) return
            val r = runCatching { api.getCatalystBrief(codes) }.getOrNull() ?: return
            catalystBriefSectors = r.sectors
        } finally { catalystBriefLoading = false }
    }

    suspend fun load() {
        loading = true; supplyLoading = true; dartLoading = true; macroLoading = true
        moodLoading = true; impactLoading = true; earningsLoading = true; eventsLoading = true
        sectorLoading = true; sectorBriefingLoading = true; catalystBriefLoading = true
        error = null
        supplyRows = emptyList(); dartItems = emptyList(); macroItems = emptyList()
        moodComment = ""; moodGeneratedAt = ""
        impactComment = ""; impactHoldings = emptyList(); impactWatch = emptyList(); impactGeneratedAt = ""
        earningsItems = emptyList(); eventItems = emptyList(); sectorItems = emptyList()
        sectorBriefingComment = ""; sectorSpotlight = emptyList(); sectorBriefingGeneratedAt = ""
        catalystBriefSectors = emptyList()

        val allItems = watchlistRepo.all()
        watchlistIsEmpty = allItems.isEmpty()
        val codes = allItems.map { it.code }

        coroutineScope {
            launch { buildMacro() }
            launch { buildMarketMood() }
            launch { loadMoodAccuracy() }
            launch { buildSectors() }
            launch { buildEarnings(codes) }
            launch { buildEvents() }
            launch { buildImpact(allItems) }
            launch { buildSectorBriefing(codes) }
            launch { buildCatalystBrief(codes) }
            launch {
                val quotes = runCatching { api.getQuotes(codes) }.getOrElse { e ->
                    error = "불러오기 실패: ${e.message}\n(백엔드 연결을 확인해 주세요)"
                    loading = false; supplyLoading = false; dartLoading = false
                    return@launch
                }
                val quoteMap = quotes.associateBy { it.code }
                allItemsLoaded = allItems
                quoteMapLoaded = quoteMap
                // 하이라이트 (상위/하위 2)
                val rows = allItems.mapNotNull { item -> quoteMap[item.code]?.let { QuoteRow(item, it) } }
                val sorted = rows.sortedByDescending { it.quote.changeRate }
                topGainers = sorted.take(2).filter { it.quote.changeRate > 0 }
                topLosers = sorted.takeLast(2).reversed().filter { it.quote.changeRate < 0 }
                // 보유현황
                holdings = allItems.mapNotNull { item ->
                    val avg = item.avgPrice ?: return@mapNotNull null
                    val qty = item.qty ?: return@mapNotNull null
                    val price = quoteMap[item.code]?.price?.toDouble() ?: avg
                    BriefHoldingRow(item, avg, qty.toDouble(), price)
                }
                loading = false
                launch { buildSupply(allItems, quoteMap) }
                launch { buildDart(codes) }
            }
        }
        loadedAtState.value = System.currentTimeMillis()
    }

    fun regenAllAI() {
        scope.launch {
            val codes = allItemsLoaded.map { it.code }
            coroutineScope {
                launch { buildMarketMood(force = true) }
                launch { buildImpact(allItemsLoaded, force = true) }
                launch { buildSectorBriefing(codes, force = true) }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && loadedAtState.value > 0) {
                if (System.currentTimeMillis() - loadedAtState.value > 30 * 60_000L) {
                    scope.launch { load() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { load() }

    val RefreshButton: @Composable () -> Unit = {
        Box {
            if (loading) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = { scope.launch { load() } },
                            onLongClick = { showRefreshMenu = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                }
            }
            DropdownMenu(
                expanded = showRefreshMenu,
                onDismissRequest = { showRefreshMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("전체 새로고침") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { showRefreshMenu = false; scope.launch { load() } },
                )
                DropdownMenuItem(
                    text = { Text("AI 코멘트만 재생성") },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { showRefreshMenu = false; regenAllAI() },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            CompactHeader(title = "브리핑") {
                RefreshButton()
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            if (loading && macroItems.isEmpty() && topGainers.isEmpty() && holdings.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let { msg ->
                        item {
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // 탭 선택기 + 데이터 기준일 배너
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            EdgeSegmentedButtonRow(
                                items = BriefTab.entries.toList(),
                                selectedIndex = BriefTab.entries.indexOf(selectedTab),
                                onSelect = { onSelectTab(BriefTab.entries[it]) },
                                label = { it.label },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FreshnessBanner(fresh)
                        }
                    }

                    if (selectedTab == BriefTab.MyStocks) {
                        // ── 하이라이트 ──
                        if (topGainers.isNotEmpty()) {
                            item { HighlightCard("상승 종목", topGainers, onStockClick) }
                        }
                        if (topLosers.isNotEmpty()) {
                            item { HighlightCard("하락 종목", topLosers, onStockClick) }
                        }
                        // ── 보유현황 ──
                        if (holdings.isNotEmpty()) {
                            item { HoldingsSummaryCard(holdings) }
                        }
                        // ── 외인·기관 동향 ──
                        item {
                            CollapsibleCard(
                                title = "외인·기관 동향",
                                trailing = {
                                    if (!watchlistIsEmpty && !supplyLoading && supplyRows.isNotEmpty()) {
                                        BadgeCount(supplyRows.size)
                                    }
                                },
                            ) {
                                if (watchlistIsEmpty) EmptyRow("관심종목을 추가하면 외인·기관 동향을 볼 수 있어요")
                                else if (supplyLoading) {
                                    LoadingRow("확인 중…")
                                } else {
                                    Text("3일 연속 순매수 · 전일 확정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (supplyRows.isEmpty()) {
                                        EmptyRow("해당하는 종목이 없어요")
                                    } else {
                                        supplyRows.forEachIndexed { i, row ->
                                            SupplyRowView(row) { onStockClick(row.item, row.quote) }
                                            if (i < supplyRows.size - 1) HorizontalDivider()
                                        }
                                    }
                                }
                            }
                        }
                        // ── 최근 공시 ──
                        item {
                            CollapsibleCard(title = "최근 공시 (7일)") {
                                if (watchlistIsEmpty) EmptyRow("관심종목을 추가하면 관심종목의 공시를 볼 수 있어요")
                                else if (dartLoading) LoadingRow("확인 중…")
                                else if (dartItems.isEmpty()) EmptyRow("최근 7일간 공시가 없어요")
                                else DartList(dartItems)
                            }
                        }
                        // ── 테마별 재료 동향 ──
                        item {
                            CollapsibleCard(title = "테마별 재료 동향") {
                                if (watchlistIsEmpty) {
                                    EmptyRow("관심종목을 추가하면 테마별 재료 동향을 볼 수 있어요")
                                } else if (catalystBriefLoading) {
                                    LoadingRow("확인 중…")
                                } else if (catalystBriefSectors.isEmpty()) {
                                    EmptyRow("아직 재료 판정이 없어요. 종목 상세에서 뉴스·공시 영향 카드를 열면 채워집니다.")
                                } else {
                                    catalystBriefSectors.forEachIndexed { i, sector ->
                                        CatalystBriefRow(sector)
                                        if (i < catalystBriefSectors.size - 1) HorizontalDivider()
                                    }
                                }
                            }
                        }
                    } else {
                        // ── 오늘 시장 분위기 (AI) ──
                        item {
                            AIBriefingCard(
                                title = "오늘 시장 분위기",
                                aggressive = aggressive,
                                loading = moodLoading,
                                comment = moodComment,
                                dateLabel = mdLabel(moodDate),
                                generatedAt = moodGeneratedAt,
                                weekendReuse = fresh.isSundayReuse,
                                expanded = moodExpanded,
                                onToggle = { moodExpanded = !moodExpanded },
                                onRegen = { scope.launch { buildMarketMood(force = true) } },
                            )
                        }
                        // ── 코스피 방향 선행 신호 ──
                        item { MoodSignalCard(moodAccuracy) }
                        // ── 내 종목 영향 ──
                        item {
                            ImpactCard(
                                aggressive = aggressive,
                                loading = impactLoading,
                                noWatchlist = watchlistIsEmpty,
                                comment = if (watchlistIsEmpty) "" else impactComment,
                                dateLabel = mdLabel(impactDate),
                                generatedAt = impactGeneratedAt,
                                weekendReuse = fresh.isSundayReuse,
                                holdings = impactHoldings,
                                watch = impactWatch,
                                onRegen = { scope.launch { buildImpact(allItemsLoaded, force = true) } },
                            )
                        }
                        // ── 시장 지표 ──
                        item {
                            CollapsibleCard(title = "시장 지표") {
                                Text("전일 대비", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (macroLoading) LoadingRow("확인 중…")
                                else if (macroItems.isEmpty()) EmptyRow("불러오기 실패")
                                else macroItems.forEachIndexed { i, m ->
                                    MacroRowView(m)
                                    if (i < macroItems.size - 1) HorizontalDivider()
                                }
                            }
                        }
                        // ── 섹터 동향 (내 종목 관련) ──
                        item {
                            val userSectors = if (!watchlistIsEmpty) userSectorLabels(impactHoldings, impactWatch) else emptySet()
                            val filtered = if (!watchlistIsEmpty) sectorItems.filter { isRelevant(it, userSectors) } else emptyList()
                            CollapsibleCard(title = "섹터 동향 (내 종목 관련)") {
                                if (watchlistIsEmpty) EmptyRow("관심종목을 추가하면 내 종목 관련 섹터를 볼 수 있어요")
                                else if (sectorLoading) LoadingRow("확인 중…")
                                else if (filtered.isEmpty()) EmptyRow("관련 섹터가 없어요")
                                else filtered.forEachIndexed { i, s ->
                                    SectorRowView(s)
                                    if (i < filtered.size - 1) HorizontalDivider()
                                }
                            }
                        }
                        // ── 섹터 분석 (AI) + 주목 종목 ──
                        item {
                            AIBriefingCard(
                                title = "섹터 분석",
                                aggressive = false,
                                loading = sectorBriefingLoading,
                                comment = if (watchlistIsEmpty) "" else sectorBriefingComment,
                                dateLabel = mdLabel(sectorBriefingDate),
                                generatedAt = sectorBriefingGeneratedAt,
                                weekendReuse = fresh.isSundayReuse,
                                expanded = sbExpandedState.value,
                                onToggle = { sbExpandedState.value = !sbExpandedState.value },
                                onRegen = {
                                    scope.launch { buildSectorBriefing(allItemsLoaded.map { it.code }, force = true) }
                                },
                                emptyText = if (watchlistIsEmpty) "관심종목을 추가하면 섹터 분석을 볼 수 있어요" else "장 시작 전이거나 섹터 데이터가 없어요",
                            ) {
                                if (sectorSpotlight.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("오늘 주목 종목", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    sectorSpotlight.forEach { s ->
                                        val item = allItemsLoaded.firstOrNull { it.code == s.code }
                                        val quote = quoteMapLoaded[s.code]
                                        SpotlightRowView(s, quote) { item?.let { onStockClick(it, quote) } }
                                    }
                                }
                            }
                        }
                        // ── 이벤트 캘린더 (30일) ──
                        item {
                            CollapsibleCard(title = "이벤트 캘린더 (30일)") {
                                if (eventsLoading) LoadingRow("이벤트 일정 수집 중…")
                                else if (eventItems.isEmpty()) EmptyRow("이번 달 주요 이벤트 일정이 없어요.")
                                else {
                                    eventItems.forEach { e ->
                                        EventRowView(e)
                                        HorizontalDivider()
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    EventLegend()
                                }
                            }
                        }
                        // ── 실적 일정 (D-90 이내) ──
                        item {
                            CollapsibleCard(title = "실적 일정 (D-90 이내)") {
                                if (watchlistIsEmpty) EmptyRow("관심종목을 추가하면 실적 일정을 볼 수 있어요")
                                else if (earningsLoading) LoadingRow("확인 중…")
                                else if (earningsItems.isEmpty()) EmptyRow("90일 이내 예정된 실적이 없어요")
                                else earningsItems.forEachIndexed { i, e ->
                                    EarningsRowView(e)
                                    if (i < earningsItems.size - 1) HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// sectorBriefing AI 카드 펼침 상태 — composable 밖 보관 불가라 파일 레벨 remember 대용으로
// 별도 mutableState 사용(브리핑 1개라 충돌 없음).
private val sbExpandedState = androidx.compose.runtime.mutableStateOf(false)

// ──────────────────────────────────────────────────────────────────
// 카드 / 행 컴포저블
// ──────────────────────────────────────────────────────────────────

@Composable
private fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BadgeCount(n: Int) {
    Text(
        n.toString(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
        color = OrangeAccent,
        modifier = Modifier.background(OrangeAccent.copy(alpha = 0.15f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// 상세 화면으로 이동 가능함을 알리는 우측 disclosure 화살표(iOS NavigationLink chevron 대응).
@Composable
private fun NavChevron() {
    Text(
        "›",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ── 하이라이트 카드 ──
@Composable
private fun HighlightCard(title: String, rows: List<QuoteRow>, onClick: (WatchItem, Quote?) -> Unit) {
    PlainCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        rows.forEachIndexed { idx, row ->
            QuoteRowView(row) { onClick(row.item, row.quote) }
            if (idx < rows.size - 1) HorizontalDivider()
        }
    }
}

@Composable
private fun QuoteRowView(row: QuoteRow, onClick: () -> Unit) {
    val r = row.quote.changeRate
    val color = if (r > 0) ChangeUp else if (r < 0) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant
    val sym = if (r > 0) "▲" else if (r < 0) "▼" else "—"
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.item.name, style = MaterialTheme.typography.bodyMedium)
            Text(row.item.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${row.quote.price.fmt()}원", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text("$sym ${"%.2f".format(abs(r))}%", style = MaterialTheme.typography.labelMedium, color = color)
        }
        NavChevron()
    }
}

// ── 보유현황 요약 ──
@Composable
private fun HoldingsSummaryCard(holdings: List<BriefHoldingRow>) {
    val invested = holdings.sumOf { it.invested }
    val evaluated = holdings.sumOf { it.evaluated }
    val pnl = evaluated - invested
    val rate = if (invested == 0.0) 0.0 else pnl / invested * 100
    val up = pnl >= 0
    val color = if (up) ChangeUp else ChangeDown
    PlainCard {
        Text("보유현황", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${holdings.size}개 종목", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${evaluated.toLong().fmt()}원", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${if (up) "+" else ""}${pnl.toLong().fmt()}원", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
                Text("${if (up) "+" else ""}${"%.2f".format(rate)}%", style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        Text("종목별 상세는 '내 자산' 탭에서 볼 수 있어요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── 외인·기관 동향 행 ──
@Composable
private fun SupplyRowView(row: SupplyRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.item.name, style = MaterialTheme.typography.bodyMedium)
            Text(row.item.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            row.labels.forEach { Pill(it, OrangeAccent) }
        }
        NavChevron()
    }
}

// ── 공시 목록 ──
@Composable
private fun DartList(items: List<DartItem>) {
    val uri = LocalUriHandler.current
    items.forEachIndexed { idx, item ->
        Column(
            modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(item.url) } }.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(item.reportName, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.corpName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDartDate(item.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (idx < items.size - 1) HorizontalDivider()
    }
}

// ── 테마별 재료 동향 행 ──
@Composable
private fun CatalystBriefRow(item: com.haky.edge.model.SectorCatalystLine) {
    val (biasLabel, biasColor) = when (item.bias) {
        "호재우위" -> "▲ 호재" to ChangeUp
        "악재우위" -> "▼ 악재" to ChangeDown
        else -> "━ 혼조" to androidx.compose.ui.graphics.Color(0xFFE69A28)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(64.dp)) {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp,
            ) {
                Text(
                    item.sector,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(biasLabel, style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = biasColor)
        }
        Text(item.line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

// ── 시장 지표 행 ──
@Composable
private fun MacroRowView(m: MacroIndicator) {
    val flat = m.changeRate == 0.0
    val up = m.changeRate > 0
    val color = if (flat) MaterialTheme.colorScheme.onSurfaceVariant else if (up) ChangeUp else ChangeDown
    val arrow = if (flat) "–" else if (up) "▲" else "▼"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.label, style = MaterialTheme.typography.bodyMedium)
            if (m.tag.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Pill(m.tag, tagColor(m.tag))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatMacroValue(m), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("$arrow ${"%.2f".format(abs(m.changeRate))}%", style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        macroDescription(m.key)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── 섹터 동향 행 ──
@Composable
private fun SectorRowView(s: SectorIndex) {
    val flat = s.changeRate == 0.0
    val up = s.changeRate > 0
    val color = if (flat) MaterialTheme.colorScheme.onSurfaceVariant else if (up) ChangeUp else ChangeDown
    val arrow = if (flat) "–" else if (up) "▲" else "▼"
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(decimalFmt.format(s.value), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text("$arrow ${"%.2f".format(abs(s.changeRate))}%", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

// ── 주목 종목 행 ──
@Composable
private fun SpotlightRowView(s: SpotlightStock, quote: Quote?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium)
            Text(s.sectorLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        quote?.let { q ->
            val color = if (q.changeRate > 0) ChangeUp else if (q.changeRate < 0) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant
            val sym = if (q.changeRate > 0) "▲" else if (q.changeRate < 0) "▼" else "—"
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${q.price.fmt()}원", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("$sym ${"%.2f".format(abs(q.changeRate))}%", style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        NavChevron()
    }
}

// ── 실적 일정 행 ──
@Composable
private fun EarningsRowView(e: EarningsEntry) {
    val days = e.daysUntil
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.corpName, style = MaterialTheme.typography.bodyMedium)
            Text(e.reportName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Pill(ddayLabel(days), ddayColor(days))
    }
}

// ── 이벤트 캘린더 행 ──
@Composable
private fun EventRowView(e: MarketEvent) {
    val (accent, badge) = when (e.category) {
        "호재" -> Teal to "호재"
        "주의" -> OrangeAccent to "주의"
        else -> EdgeTheme.colors.neutral to "중립"
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(48.dp)) {
            Text(eventDateLabel(e.date), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Pill(badge, accent)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.title, style = MaterialTheme.typography.bodyMedium)
                if (e.confirmed) Text("✓", style = MaterialTheme.typography.labelSmall, color = Teal)
            }
            Text(e.impact, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EventLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        EventLegendRow("호재", Teal, "결과에 따라 주가 상승 기대")
        EventLegendRow("주의", OrangeAccent, "결과에 따라 주가 크게 흔들릴 수 있음")
        EventLegendRow("중립", EdgeTheme.colors.neutral, "시장 방향과 무관")
    }
}

@Composable
private fun EventLegendRow(label: String, color: Color, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Pill(label, color)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ──────────────────────────────────────────────────────────────────
// 내 종목 영향 카드
// ──────────────────────────────────────────────────────────────────

@Composable
private fun ImpactCard(
    aggressive: Boolean,
    loading: Boolean,
    noWatchlist: Boolean,
    comment: String,
    dateLabel: String,
    generatedAt: String,
    weekendReuse: Boolean,
    holdings: List<StockImpact>,
    watch: List<StockImpact>,
    onRegen: () -> Unit,
) {
    CollapsibleCard(
        title = "내 종목 영향",
        trailing = { if (aggressive) Pill("⚔️ 공격적 모드", OrangeAccent) },
    ) {
        if (loading) {
            LoadingRow("AI가 해석 중…")
        } else if (noWatchlist) {
            EmptyRow("관심종목을 추가하면 내 종목 기준 영향 분석을 볼 수 있어요")
        } else if (comment.isEmpty() && holdings.isEmpty() && watch.isEmpty()) {
            EmptyRow("불러오지 못했어요")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (holdings.isNotEmpty()) {
                    Text("보유 종목", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    holdings.forEachIndexed { i, s ->
                        ImpactRowView(s)
                        if (i < holdings.size - 1) HorizontalDivider()
                    }
                }
                if (watch.isNotEmpty()) {
                    if (holdings.isNotEmpty()) HorizontalDivider()
                    var watchExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { watchExpanded = !watchExpanded }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("관심 종목 ${watch.size}개", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(if (watchExpanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (watchExpanded) watch.forEachIndexed { i, s ->
                        ImpactRowView(s)
                        if (i < watch.size - 1) HorizontalDivider()
                    }
                }
                if (comment.isNotEmpty()) {
                    if (holdings.isNotEmpty() || watch.isNotEmpty()) HorizontalDivider()
                    var expanded by remember { mutableStateOf(false) }
                    AICommentBlock(
                        title = "AI 코멘트",
                        compact = true,
                        aggressive = false,
                        comment = comment,
                        dateLabel = dateLabel,
                        generatedAt = generatedAt,
                        weekendReuse = weekendReuse,
                        loading = loading,
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        onRegen = onRegen,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImpactRowView(s: StockImpact) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                Text(s.sectorLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val netColor = when (s.net) {
                "우호적" -> ChangeUp
                "부담" -> ChangeDown
                else -> EdgeTheme.colors.neutral
            }
            Pill(s.net, netColor)
        }
        if (s.signals.isEmpty()) {
            Text("영향 매핑 준비 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            s.signals.forEach { sig ->
                val d = sig.direction
                val c = if (d > 0) ChangeUp else if (d < 0) ChangeDown else EdgeTheme.colors.neutral
                val lbl = if (d > 0) "우호" else if (d < 0) "부담" else "중립"
                val signed = (if (sig.changeRate >= 0) "+" else "") + "%.2f".format(sig.changeRate)
                Text("${sig.indicator} $signed% → $lbl", style = MaterialTheme.typography.labelSmall, color = c)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// AI 코멘트 카드 (시장 분위기 · 섹터 분석)
// ──────────────────────────────────────────────────────────────────

@Composable
private fun AIBriefingCard(
    title: String,
    aggressive: Boolean,
    loading: Boolean,
    comment: String,
    dateLabel: String,
    generatedAt: String,
    weekendReuse: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRegen: () -> Unit,
    emptyText: String = "불러오지 못했어요",
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            AITitleRow(title, aggressive)
            LoadingRow("AI가 분석 중…")
        } else if (comment.isEmpty()) {
            AITitleRow(title, aggressive)
            EmptyRow(emptyText)
        } else {
            AICommentBlock(
                title = title,
                compact = false,
                aggressive = aggressive,
                comment = comment,
                dateLabel = dateLabel,
                generatedAt = generatedAt,
                weekendReuse = weekendReuse,
                loading = loading,
                expanded = expanded,
                onToggle = onToggle,
                onRegen = onRegen,
            )
            if (expanded) extraContent()
        }
    }
}

@Composable
private fun AITitleRow(title: String, aggressive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = PurpleAccent)
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (aggressive) Pill("⚔️ 공격적 모드", OrangeAccent)
    }
}

// 제목 행(탭=접기) + 메타 행(생성시각 + 재생성) + 펼침 시 프로즈.
// compact=true: 다른 섹션 하위에 들어가는 작은 라벨.
@Composable
private fun AICommentBlock(
    title: String,
    compact: Boolean,
    aggressive: Boolean,
    comment: String,
    dateLabel: String,
    generatedAt: String,
    weekendReuse: Boolean,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRegen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(if (compact) 14.dp else 18.dp), tint = PurpleAccent)
            Text(title, style = if (compact) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.titleSmall)
            if (aggressive) Pill("⚔️ 공격적 모드", OrangeAccent)
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 메타 행
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (weekendReuse) {
                Text("주말 휴장이라 직전 영업일 기준 분석이에요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val meta = if (generatedAt.isNotEmpty()) {
                    if (dateLabel.isEmpty()) "오늘 $generatedAt 생성" else "$dateLabel $generatedAt 생성"
                } else ""
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (!loading) {
                    Text("↻ 재생성", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = PurpleAccent, modifier = Modifier.clickable { onRegen() })
                }
            }
        }
        if (expanded) AIProseBlock(comment)
    }
}

// 빈 줄(\n\n) 문단 분리 + **굵게** 렌더 + 왼쪽 보라 액센트 바.
@Composable
private fun AIProseBlock(text: String) {
    val paras = remember(text) { text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(PurpleAccent.copy(alpha = 0.35f), RoundedCornerShape(2.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            paras.forEach { p ->
                Text(parseMarkdownBold(p), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// 코스피 방향 선행 신호 카드
// ──────────────────────────────────────────────────────────────────

@Composable
private fun MoodSignalCard(report: MoodAccuracyReport?) {
    CollapsibleCard(title = "코스피 방향 선행 신호") {
        if (report == null) {
            LoadingRow("불러오는 중…")
            return@CollapsibleCard
        }
        val today = report.recentEntries.firstOrNull()
        val todayPending = today != null && today.isCorrect == null
        if (report.recentEntries.isEmpty()) {
            Text("매일 오전 5시·오후 3시 35분에\n자동으로 기록돼요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (todayPending) MoodTodayCard(today.direction)
                if (report.total > 0) {
                    val rate = (report.correct.toDouble() / report.total * 100).toInt()
                    val color = if (rate >= 60) ChangeUp else if (rate >= 40) OrangeAccent else ChangeDown
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${report.correct}/${report.total}회 적중 · $rate%", style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
                        if (report.pending > 0) Text("대기 ${report.pending}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val history = if (todayPending) report.recentEntries.drop(1).take(5) else report.recentEntries.take(5)
                history.forEachIndexed { i, e ->
                    MoodHistoryRow(e)
                    if (i < history.size - 1) HorizontalDivider()
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("미장 마감(오전 5시) 이후 확인 적기. 장 마감(오후 3:30) 후 자동 채점돼요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MoodTodayCard(direction: String) {
    val (label, color) = when (direction) {
        "BULLISH" -> "강세 예상 ↑" to ChangeUp
        "BEARISH" -> "약세 예상 ↓" to ChangeDown
        else -> "보합 예상" to EdgeTheme.colors.neutral
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f), CardShape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("오늘 코스피", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.titleSmall, color = color)
        }
        Text("미국 지수·달러 기반", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MoodHistoryRow(entry: MoodLogEntry) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Text(entry.date.takeLast(5).replace("-", "/"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
        Text("예측", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoodBadge(entry.direction, actual = false)
        Text("→", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("실제", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val actual = entry.actualDirection
        if (actual != null) MoodBadge(actual, actual = true)
        else Text("대기", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        entry.isCorrect?.let { ok ->
            Text(if (ok) "✓" else "✗", style = MaterialTheme.typography.bodyMedium, color = if (ok) EdgeTheme.colors.success else ChangeUp)
        }
        entry.kospiChange?.let { v ->
            Text("${if (v >= 0) "+" else ""}${"%.1f".format(v)}%", style = MaterialTheme.typography.labelSmall, color = if (v >= 0) ChangeUp else ChangeDown)
        }
    }
}

@Composable
private fun MoodBadge(direction: String, actual: Boolean) {
    val (label, color) = when (direction) {
        "BULLISH" -> "강세↑" to ChangeUp
        "BEARISH" -> "약세↓" to ChangeDown
        else -> "보합" to EdgeTheme.colors.neutral
    }
    val fg = if (actual) MaterialTheme.colorScheme.onSurface else color
    val bg = if (actual) EdgeTheme.colors.neutral.copy(alpha = 0.12f) else color.copy(alpha = 0.12f)
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

// ──────────────────────────────────────────────────────────────────
// 데이터 신선도 배너
// ──────────────────────────────────────────────────────────────────

private data class Freshness(val isLive: Boolean, val isSundayReuse: Boolean, val bannerText: String)

@Composable
private fun FreshnessBanner(f: Freshness) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(if (f.isLive) Icons.Filled.Sensors else Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(f.bannerText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun computeFreshness(now: Date = Date()): Freshness {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
    cal.time = now
    val wd = cal.get(Calendar.DAY_OF_WEEK)  // 1=일 … 7=토
    val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val isWeekend = wd == Calendar.SUNDAY || wd == Calendar.SATURDAY
    val open = 540; val close = 930
    val isLive = !isWeekend && mins in open until close
    val afterClose = !isWeekend && mins >= close

    val lastTD = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply { time = now }
    if (!afterClose) {
        lastTD.add(Calendar.DAY_OF_MONTH, -1)
        while (lastTD.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SUNDAY || it == Calendar.SATURDAY }) {
            lastTD.add(Calendar.DAY_OF_MONTH, -1)
        }
    }
    val banner = if (isLive) {
        "실시간 시세 · 수초 지연"
    } else {
        val f = SimpleDateFormat("M/d(E)", Locale.KOREAN).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
        val suffix = if (isWeekend) " · 주말 휴장" else if (mins < open) " · 개장 전" else ""
        "${f.format(lastTD.time)} 종가 기준$suffix"
    }
    return Freshness(isLive = isLive, isSundayReuse = wd == Calendar.SUNDAY, bannerText = banner)
}

// ──────────────────────────────────────────────────────────────────
// 포맷터 / 헬퍼
// ──────────────────────────────────────────────────────────────────

private val decimalFmt = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

private fun formatMacroValue(m: MacroIndicator): String = when (m.key) {
    "fear_greed" -> "%.1f".format(m.value)
    "tnx" -> "%.2f".format(m.value) + "%"
    else -> decimalFmt.format(m.value)
}

@Composable
private fun tagColor(tag: String): Color = when {
    tag.contains("공포") -> ChangeDown
    tag.contains("탐욕") -> ChangeUp
    else -> EdgeTheme.colors.neutral
}

private fun macroDescription(key: String): String? = when (key) {
    "kodex200_ot" -> "코스피200 추종 ETF의 시간외 단일가예요. 장 전(8-9시)엔 오늘 코스피 출발 방향을, 장 후(16-18시)엔 연장 거래 방향을 보여줘요."
    "kospi" -> "국내 대형주 중심 종합주가지수예요. 코스피 방향이 국내 주식 전반의 흐름을 결정해요."
    "kosdaq" -> "중소형·기술주 중심 지수예요. 코스피보다 변동성이 크고 성장주 비중이 높아요."
    "usdkrw" -> "원화 대비 달러 환율이에요. 오를수록 원화 약세로, 외국인 매도 압력이 생기는 경향이 있어요."
    "ewy" -> "미국에 상장된 한국 주식 ETF예요. 미국 장중(한국 기준 밤)에 외국인이 한국 주식을 어떻게 평가하는지 보여줘요."
    "nasdaq" -> "미국 기술주 중심 지수예요. AI·반도체·플랫폼 등 성장주 방향을 가장 잘 나타내요."
    "sox" -> "미국 필라델피아 반도체 지수예요. 삼성전자·SK하이닉스 등 반도체 종목 흐름의 선행 지표예요."
    "sp500" -> "미국 대형주 500개 평균이에요. 미국 증시 전반의 건강을 가장 균형 있게 보여줘요."
    "dow" -> "미국 블루칩 30개 산업주 평균이에요. 역사가 긴 지수지만 기술주 비중이 낮아요."
    "rut" -> "미국 소형주 2000개 지수예요. 대형주보다 경기 민감도가 높아 위험 선호 심리를 가늠해요."
    "tnx" -> "미국 국채 10년물 금리예요. 금리 상승은 주식 밸류에이션에 부담을, 하락은 유동성 개선 신호예요."
    "dxy" -> "달러의 상대적 강세를 나타내는 지수예요. 오를수록 신흥국 자금이 미국으로 이동하는 경향이 있어요."
    "rate3y" -> "한국 국고채 3년물 금리예요. 국내 시장금리 기준으로, 상승 시 성장주 밸류에이션 부담이 생겨요."
    "crude" -> "국제 원유 기준 가격이에요. 에너지·운송 비용과 인플레이션 압력에 영향을 줘요."
    "copper" -> "구리는 글로벌 경기 선행 지표예요. 오르면 경기 회복, 내리면 경기 둔화 우려를 반영해요."
    "nikkei" -> "일본 대표 주가지수예요. 엔화 흐름·일본 경기와 연동되며, 아시아 장 개장 시 국내 증시 방향에 선행 영향을 줘요."
    "usdjpy" -> "달러 대비 엔화 환율이에요. 오를수록 엔 약세로, 일본 수출주에 유리하고 글로벌 위험 회피 심리와 역상관 경향이 있어요."
    "vix" -> "S&P500 옵션에서 뽑은 미국 증시 30일 변동성 지수예요. 20 이상은 불안, 30 이상은 공포 구간으로 봐요."
    "fear_greed" -> "CNN이 매일 산출하는 시장 심리 지수예요. 0에 가까울수록 공포, 100에 가까울수록 탐욕이에요."
    "nqfut" -> "나스닥100 선물이에요. 미국 종가 이후 한국 새벽까지 움직여, 오늘 코스피·성장주 출발 방향을 가장 먼저 보여주는 야간 선행지표예요."
    "esfut" -> "S&P500 선물이에요. 미국 장 마감 뒤 야간 흐름까지 반영해, 오늘 시장 전반의 출발 분위기를 미리 가늠해요."
    "ymfut" -> "다우 선물이에요. 미국 블루칩의 야간 흐름을 보여주며, 종가 이후 분위기 변화를 읽는 데 써요."
    else -> null
}

// KRX 업종 레이블 → 우리 세부 섹터 레이블 (백엔드 Sector.label과 일치).
private val krxToCustom: Map<String, List<String>> = mapOf(
    "전기전자" to listOf("메모리반도체", "파운드리·장비", "AI반도체", "가전", "디스플레이", "전자부품"),
    "기계" to listOf("조선", "방산·항공우주"),
    "운수장비" to listOf("완성차", "자동차부품", "2차전지", "자율주행"),
    "전기가스업" to listOf("전력기기", "신재생에너지"),
    "서비스업" to listOf("AI·클라우드", "IT서비스·SI", "인터넷플랫폼", "로봇·자동화"),
    "철강금속" to listOf("전력기기", "전선", "조선"),
)

private fun userSectorLabels(holdings: List<StockImpact>, watch: List<StockImpact>): Set<String> =
    (holdings + watch)
        .flatMap { it.sectorLabel.split("·") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

private fun isRelevant(s: SectorIndex, userSectors: Set<String>): Boolean {
    if (userSectors.isEmpty()) return true
    return krxToCustom[s.label]?.any { userSectors.contains(it) } ?: false
}

private fun ddayLabel(days: Int): String = when {
    days == 0 -> "D-day"
    days > 0 -> "D-$days"
    else -> "D+${abs(days)}"
}

@Composable
private fun ddayColor(days: Int): Color = when {
    days < 0 -> ChangeUp
    days < 14 -> ChangeUp
    days < 30 -> OrangeAccent
    else -> EdgeTheme.colors.neutral
}

private fun eventDateLabel(dateStr: String): String {
    val parts = dateStr.split("-")
    return if (parts.size == 3) "${parts[1]}/${parts[2]}" else dateStr
}

// "20250601" → "25.06.01"
private fun formatDartDate(date: String): String {
    if (date.length != 8) return date
    return "${date.substring(2, 4)}.${date.substring(4, 6)}.${date.substring(6, 8)}"
}

// "YYYY-MM-DD" → "M/d(요일)"
private fun mdLabel(ymd: String): String {
    if (ymd.isBlank()) return ""
    return runCatching {
        val inF = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
        val d = inF.parse(ymd) ?: return ""
        SimpleDateFormat("M/d(E)", Locale.KOREAN).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(d)
    }.getOrDefault("")
}
