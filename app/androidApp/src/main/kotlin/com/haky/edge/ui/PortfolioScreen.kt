package com.haky.edge.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.AccountRepository
import com.haky.edge.db.HoldingRepository
import com.haky.edge.model.AccountInfo
import com.haky.edge.model.DriftEntry
import com.haky.edge.model.PortfolioReview
import com.haky.edge.model.Quote
import com.haky.edge.model.RebalanceCheck
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.PurpleAccent
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// ── 도넛 팔레트 (인덱스 고정) ───────────────────────────────────────────────
private val sliceColors = listOf(
    Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF9C27B0),
    Color(0xFFE91E63), Color(0xFF009688), Color(0xFF3F51B5), Color(0xFF00BCD4),
    Color(0xFF00ACC1), Color(0xFFFFEB3B),
)
private fun sliceColor(i: Int) = sliceColors[i % sliceColors.size]

// 섹터 → 색상
private val sectorColorMap = mapOf(
    "메모리반도체" to Color(0xFF2196F3), "파운드리·장비" to Color(0xFF00BCD4),
    "AI반도체" to Color(0xFF3F51B5), "AI·클라우드" to Color(0xFF9C27B0),
    "IT서비스·SI" to Color(0xFF9C27B0), "인터넷플랫폼" to Color(0xFF00BFA5),
    "로봇·자동화" to Color(0xFF009688), "자율주행" to Color(0xFF009688),
    "완성차" to Color(0xFFFF9800), "자동차부품" to Color(0xFFFF9800),
    "2차전지" to Color(0xFFFFEB3B), "조선" to Color(0xFF009688),
    "방산·항공우주" to Color(0xFFF44336), "전력기기" to Color(0xFFFF9800),
    "전선" to Color(0xFFFF9800), "신재생에너지" to Color(0xFF4CAF50),
    "가전" to Color(0xFF4CAF50), "디스플레이" to Color(0xFF00BCD4),
    "전자부품" to Color(0xFF4CAF50),
)
private fun sectorColor(label: String) = sectorColorMap[label] ?: Color(0xFF8E8E93)

// ── HoldingRow 계산 모델 ─────────────────────────────────────────────────────

private data class HoldingRow(
    val item: WatchItem,
    val quote: Quote?,
    val avg: Double,
    val qty: Double,
    val price: Double,
    val accountId: Long = 0L,  // G3: 계좌 필터링용
) {
    val invested: Double  get() = avg * qty
    val evaluated: Double get() = price * qty
    val pnl: Double       get() = (price - avg) * qty
    val pnlRate: Double   get() = if (avg == 0.0) 0.0 else (price - avg) / avg * 100
}

// ── PortfolioScreen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    holdingRepo: HoldingRepository,
    accountRepo: AccountRepository,
    api: EdgeApi,
) {
    var rows by remember { mutableStateOf<List<HoldingRow>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<AccountInfo>>(emptyList()) }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var sectorMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var portfolioReview by remember { mutableStateOf<PortfolioReview?>(null) }
    var rebalanceCheck by remember { mutableStateOf<RebalanceCheck?>(null) }
    var reviewLoading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var showAccountMgmt by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        // G1: holding 테이블이 보유 포지션의 단일 정본
        val allHoldings = holdingRepo.all().filter { it.avgPrice != null && it.qty != null }
        // G3: 계좌 목록 로드 (세그먼트 컨트롤 노출 여부 판단)
        accounts = accountRepo.all()
        if (allHoldings.isEmpty()) {
            rows = emptyList()
            loading = false
            return
        }
        val codes = allHoldings.map { it.code }
        val quotes = runCatching { api.getQuotes(codes) }.getOrDefault(emptyList())
        val quoteMap = quotes.associateBy { it.code }
        rows = allHoldings.mapNotNull { h ->
            val avg = h.avgPrice ?: return@mapNotNull null
            val qty = (h.qty ?: return@mapNotNull null).toDouble()
            val quote = quoteMap[h.code]
            val price = quote?.let { it.price.toDouble() } ?: avg
            // StockDetailScreen 연결용 WatchItem — holding 포지션 데이터를 담아 전달
            val item = WatchItem(code = h.code, name = h.name,
                                 avgPrice = h.avgPrice, qty = h.qty,
                                 targetPrice = h.targetPrice, stopPrice = h.stopPrice)
            HoldingRow(item, quote, avg, qty, price, h.accountId)
        }
        loading = false
        // 섹터 분류 + 포트폴리오 진단은 화면 표시 차단 없이 별도 로드
        runCatching { api.getSectorClassify(codes) }.getOrNull()?.let { entries ->
            sectorMap = entries.associate { it.code to it.sectorLabel }
        }
        loadPortfolioReview(rows, api, AppPrefs.getMode(context), false) { portfolioReview = it; reviewLoading = false }
        rebalanceCheck = runCatching { api.getRebalanceCheck() }.getOrNull()
    }

    suspend fun loadReview(force: Boolean) {
        reviewLoading = true
        if (force) portfolioReview = null
        loadPortfolioReview(rows, api, AppPrefs.getMode(context), force) { portfolioReview = it; reviewLoading = false }
    }

    LaunchedEffect(Unit) { load() }

    if (showAccountMgmt) {
        AccountManagementSheet(
            accountRepo = accountRepo,
            holdingRepo = holdingRepo,
            onDismiss = { showAccountMgmt = false },
        )
    }

    Scaffold(
        topBar = {
            CompactHeader(
                title = "내 자산",
                actions = {
                    IconButton(onClick = { showAccountMgmt = true }) {
                        Icon(Icons.Filled.CreditCard, contentDescription = "계좌 관리")
                    }
                    if (loading) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = { scope.launch { load() } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when {
                loading && rows.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                rows.isEmpty() -> EmptyPortfolio()
                else -> {
                    // G3: 선택 계좌 기준 필터링 (null = 전체)
                    val displayRows = if (selectedAccountId == null) rows
                                      else rows.filter { it.accountId == selectedAccountId }
                    HoldingsList(
                        rows = displayRows,
                        accounts = accounts,
                        selectedAccountId = selectedAccountId,
                        onAccountSelect = { selectedAccountId = it },
                        sectorRows = run {
                            val map = mutableMapOf<String, Double>()
                            for (row in displayRows) map[sectorMap[row.item.code] ?: "기타"] =
                                (map[sectorMap[row.item.code] ?: "기타"] ?: 0.0) + row.evaluated
                            map.entries.sortedByDescending { it.value }.map { it.key to it.value }
                        },
                        review = portfolioReview,
                        rebalanceCheck = rebalanceCheck,
                        reviewLoading = reviewLoading,
                        onReviewRefresh = { scope.launch { loadReview(true) } },
                        onResetBaseline = {
                            scope.launch {
                                runCatching { api.postRebalanceBaseline() }
                                rebalanceCheck = runCatching { api.getRebalanceCheck() }.getOrNull()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ── 빈 화면 ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyPortfolio() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "평단가를 입력한 종목이 없어요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "관심종목 상세에서 평단가·수량을 입력하면\n여기서 수익률을 집계해 줍니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── 보유 종목 리스트 ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingsList(
    rows: List<HoldingRow>,
    accounts: List<AccountInfo>,
    selectedAccountId: Long?,
    onAccountSelect: (Long?) -> Unit,
    sectorRows: List<Pair<String, Double>>,
    review: PortfolioReview?,
    rebalanceCheck: RebalanceCheck?,
    reviewLoading: Boolean,
    onReviewRefresh: () -> Unit,
    onResetBaseline: () -> Unit,
) {
    val customAccounts = accounts.filter { it.isDefault == 0L }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // G3: 커스텀 계좌가 있을 때만 세그먼트 컨트롤 표시
        if (customAccounts.isNotEmpty()) {
            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val options = listOf<Long?>(null) + accounts.map { it.id }
                    options.forEachIndexed { index, id ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            onClick = { onAccountSelect(id) },
                            selected = selectedAccountId == id,
                            label = {
                                Text(
                                    if (id == null) "전체"
                                    else accounts.find { it.id == id }?.name ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            SummaryCard(rows = rows, sectorRows = sectorRows)
            Spacer(Modifier.height(8.dp))
        }
        if (review != null || reviewLoading) {
            item {
                PortfolioReviewCard(
                    review = review,
                    rebalanceCheck = rebalanceCheck,
                    loading = reviewLoading,
                    onRefresh = onReviewRefresh,
                    onResetBaseline = onResetBaseline,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    Text(
                        "보유 종목 ${rows.size}개",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    rows.forEachIndexed { i, row ->
                        HoldingRowItem(row)
                        if (i < rows.size - 1) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── 집계 카드 ────────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(rows: List<HoldingRow>, sectorRows: List<Pair<String, Double>>) {
    val invested = rows.sumOf { it.invested }
    val evaluated = rows.sumOf { it.evaluated }
    val totalPnl = evaluated - invested
    val totalRate = if (invested == 0.0) 0.0 else totalPnl / invested * 100
    val pnlColor = when {
        totalPnl > 0 -> ChangeUp
        totalPnl < 0 -> ChangeDown
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)

    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            // 숫자 요약
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("총 평가금액", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${fmt.format(evaluated.toLong())}원",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("총 손익", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pnlSign = if (totalPnl > 0) "+" else ""
                    Text("$pnlSign${fmt.format(totalPnl.toLong())}원",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = pnlColor)
                    Text("$pnlSign${String.format("%.2f", totalRate)}%",
                        style = MaterialTheme.typography.bodySmall, color = pnlColor)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("총 투자금", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${fmt.format(invested.toLong())}원", style = MaterialTheme.typography.labelSmall)
            }

            // 도넛 + 레전드
            if (rows.size > 1) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
                val colorByName = rows.mapIndexed { i, row -> row.item.name to sliceColor(i) }.toMap()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    DonutChart(rows = rows, modifier = Modifier.size(100.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        rows.sortedByDescending { it.evaluated }.forEach { row ->
                            val pct = if (evaluated == 0.0) 0.0 else row.evaluated / evaluated * 100
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val color = colorByName[row.item.name] ?: Color.Gray
                                Canvas(Modifier.size(8.dp)) {
                                    drawCircle(color)
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(row.item.name, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(4.dp))
                                Text("${String.format("%.1f", pct)}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 손익 기여도 발산 막대
            val hasPnl = rows.any { it.pnl != 0.0 }
            if (rows.isNotEmpty() && hasPnl) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
                PnlContributionView(rows)
            }

            // 섹터 비중 + 집중도 경고
            if (sectorRows.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
                SectorWeightView(sectorRows)
            }
        }
    }
}

// ── 도넛 차트 Canvas ─────────────────────────────────────────────────────────

@Composable
private fun DonutChart(rows: List<HoldingRow>, modifier: Modifier = Modifier) {
    val total = rows.sumOf { it.evaluated }.coerceAtLeast(1.0)
    Canvas(modifier = modifier) {
        val strokeW = size.minDimension * 0.22f
        var startAngle = -90f
        rows.forEachIndexed { i, row ->
            val sweep = (row.evaluated / total * 360.0).toFloat()
            val gap = if (rows.size > 1) 2f else 0f
            drawArc(
                color = sliceColor(i),
                startAngle = startAngle + gap / 2,
                sweepAngle = (sweep - gap).coerceAtLeast(0f),
                useCenter = false,
                style = Stroke(width = strokeW, cap = StrokeCap.Butt),
            )
            startAngle += sweep
        }
    }
}

// ── 손익 기여도 발산 막대 ─────────────────────────────────────────────────────

@Composable
private fun PnlContributionView(rows: List<HoldingRow>) {
    val sorted = rows.sortedByDescending { it.pnl }
    val maxAbs = sorted.maxOfOrNull { abs(it.pnl) }?.coerceAtLeast(1.0) ?: 1.0
    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("손익 기여도", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        sorted.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // 이름 + 금액: 풀 폭으로 — 이름이 잘리지 않음
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val sign = if (row.pnl >= 0) "+" else ""
                    Text(row.item.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 6.dp))
                    Text("$sign${fmt.format(row.pnl.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.pnl >= 0) ChangeUp else ChangeDown,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                // 발산 막대: 전폭 → 모든 행 중심선이 동일 x에 정렬됨
                DivergingBar(
                    pnl = row.pnl,
                    maxAbs = maxAbs,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun DivergingBar(pnl: Double, maxAbs: Double, modifier: Modifier = Modifier) {
    val upColor = ChangeUp
    val downColor = ChangeDown
    val centerLine = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val half = size.width / 2f
        val ratio = (abs(pnl) / maxAbs).coerceAtMost(1.0).toFloat()
        val fillW = max(2f, half * ratio)
        // 중심선
        drawLine(
            color = centerLine,
            start = Offset(half, 0f),
            end = Offset(half, size.height),
            strokeWidth = 1f,
        )
        if (pnl >= 0) {
            drawRoundRect(
                color = upColor.copy(alpha = 0.65f),
                topLeft = Offset(half, 1f),
                size = Size(fillW, size.height - 2f),
                cornerRadius = CornerRadius(2f),
            )
        } else {
            drawRoundRect(
                color = downColor.copy(alpha = 0.65f),
                topLeft = Offset(half - fillW, 1f),
                size = Size(fillW, size.height - 2f),
                cornerRadius = CornerRadius(2f),
            )
        }
    }
}

// ── 섹터 비중 ────────────────────────────────────────────────────────────────

@Composable
private fun SectorWeightView(sectorRows: List<Pair<String, Double>>) {
    val total = sectorRows.sumOf { it.second }.coerceAtLeast(1.0)
    val concentrated = sectorRows.filter { it.second / total > 0.4 }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("섹터 비중", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (concentrated.isNotEmpty()) {
                Icon(Icons.Filled.Warning, contentDescription = null,
                    modifier = Modifier.size(12.dp), tint = OrangeAccent)
                Text(
                    "${concentrated.joinToString("·") { it.first }} 집중",
                    style = MaterialTheme.typography.labelSmall, color = OrangeAccent,
                )
            }
        }
        sectorRows.forEach { (sector, amount) ->
            val pct = amount / total
            val isConc = pct > 0.4
            val barColor = if (isConc) OrangeAccent else sectorColor(sector)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(sector,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isConc) OrangeAccent else barColor,
                    modifier = Modifier.width(80.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Canvas(modifier = Modifier.weight(1f).height(10.dp)) {
                    val barW = max(4f, size.width * pct.toFloat())
                    drawRoundRect(
                        color = barColor.copy(alpha = 0.75f),
                        size = Size(barW, size.height),
                        cornerRadius = CornerRadius(3f),
                    )
                }
                Text("${String.format("%.0f", pct * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isConc) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(30.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                if (isConc) {
                    Icon(Icons.Filled.Warning, contentDescription = null,
                        modifier = Modifier.size(12.dp), tint = OrangeAccent)
                } else {
                    Spacer(Modifier.size(12.dp))
                }
            }
        }
        if (concentrated.isNotEmpty()) {
            Text(
                "한 섹터 비중이 40%를 초과하면 특정 업황·지표에 포트폴리오 전체가 흔들릴 수 있어요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 보유 종목 행 ─────────────────────────────────────────────────────────────

@Composable
private fun HoldingRowItem(row: HoldingRow) {
    val pnlColor = when {
        row.pnl > 0 -> ChangeUp
        row.pnl < 0 -> ChangeDown
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.item.name, style = MaterialTheme.typography.bodyMedium)
            Text(row.item.code, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val q = row.quote
            if (q != null) {
                val qColor = when {
                    q.changeRate > 0 -> ChangeUp
                    q.changeRate < 0 -> ChangeDown
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val symbol = when {
                    q.changeRate > 0 -> "▲"
                    q.changeRate < 0 -> "▼"
                    else -> "—"
                }
                Text("${fmt.format(q.price)}원",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("$symbol ${String.format("%.2f", abs(q.changeRate))}%",
                    style = MaterialTheme.typography.labelSmall, color = qColor)
            }
            val sign = if (row.pnl >= 0) "+" else ""
            Text(
                "$sign${fmt.format(row.pnl.toLong())}원 ($sign${String.format("%.1f", row.pnlRate)}%)",
                style = MaterialTheme.typography.labelSmall,
                color = pnlColor,
            )
        }
    }
}

// ── 포트폴리오 종합 진단 카드 ────────��────────────────────────────────────────

@Composable
private fun PortfolioReviewCard(
    review: PortfolioReview?,
    rebalanceCheck: RebalanceCheck?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onResetBaseline: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var commentExpanded by remember { mutableStateOf(false) }
    var baselineResetting by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("✦", color = PurpleAccent, style = MaterialTheme.typography.bodySmall)
                    Text("포트폴리오 종합 진단", style = MaterialTheme.typography.titleSmall)
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PurpleAccent)
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable { expanded = !expanded },
                )
            }

            if (expanded && review != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 핵심 요약
                review.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PurpleAccent.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📌", style = MaterialTheme.typography.labelSmall)
                            Text("핵심 요약", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = PurpleAccent)
                        }
                        Text(parseMarkdownBold(summary), style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 코멘트
                val blocks = review.comment.split("\n\n")
                    .map { it.trim() }.filter { it.isNotEmpty() && it != "---" }
                val visible = if (commentExpanded) blocks else blocks.take(2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(if (visible.size > 1) 100.dp else 40.dp)
                            .background(PurpleAccent.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        visible.forEach { block ->
                            Text(parseMarkdownBold(block), style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                        }
                    }
                }
                if (blocks.size > 2) {
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { commentExpanded = !commentExpanded },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            if (commentExpanded) "접기" else "더보기",
                            style = MaterialTheme.typography.labelSmall,
                            color = PurpleAccent,
                        )
                    }
                }

                // 주요 매크로 노출
                val topExposures = review.exposures.filter { it.favorablePct > 0 || it.adversePct > 0 }.take(2)
                if (topExposures.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("주요 매크로 노출", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    topExposures.forEach { ex ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(ex.label, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ex.favorablePct > 0)
                                    Text("수혜 ${ex.favorablePct.toInt()}%", style = MaterialTheme.typography.labelSmall, color = ChangeUp)
                                if (ex.adversePct > 0)
                                    Text("부담 ${ex.adversePct.toInt()}%", style = MaterialTheme.typography.labelSmall, color = ChangeDown)
                            }
                        }
                    }
                }

                // 비중 드리프트
                val rc = rebalanceCheck
                if (rc != null && rc.evaluated) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("비중 드리프트", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rc.baselineSetAt?.let { d ->
                            Text("(기준: $d)", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    rc.drifts.forEach { d ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(d.name, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(72.dp))
                            Text(String.format("%.1f%%", d.baselinePct),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("→", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(String.format("%.1f%%", d.currentPct),
                                style = MaterialTheme.typography.labelSmall)
                            val sign = if (d.deltaPp >= 0) "+" else ""
                            Text("$sign${String.format("%.1f", d.deltaPp)}%p",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (d.fired) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (d.fired) {
                                Icon(Icons.Filled.Warning, contentDescription = null,
                                    modifier = Modifier.size(12.dp), tint = OrangeAccent)
                            }
                        }
                    }
                    if (rc.topBandFired) {
                        Spacer(Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null,
                                modifier = Modifier.size(12.dp), tint = OrangeAccent)
                            Text("상단권 쏠림 ${String.format("%.0f", rc.topBandWeightPct ?: 0.0)}%",
                                style = MaterialTheme.typography.labelSmall, color = OrangeAccent)
                            Text("(${rc.topBandStocks.joinToString("·")})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (baselineResetting) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = OrangeAccent)
                        } else {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    baselineResetting = true
                                    onResetBaseline()
                                    baselineResetting = false
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null,
                                    modifier = Modifier.size(12.dp), tint = OrangeAccent)
                                Spacer(Modifier.width(2.dp))
                                Text("기준점 재설정", style = MaterialTheme.typography.labelSmall,
                                    color = OrangeAccent)
                            }
                        }
                    }
                }

                // 생성 시각 + 재생성
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = if (review.generatedAt.isEmpty()) "참고용 · ${review.date} 기준"
                                else "참고용 · 오늘 ${review.generatedAt} 생성"
                    Text(label, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PurpleAccent)
                    } else {
                        androidx.compose.material3.TextButton(
                            onClick = onRefresh,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) {
                            Text("재생성", style = MaterialTheme.typography.labelSmall, color = PurpleAccent)
                        }
                    }
                }
                Text("투자 판단과 책임은 본인에게 있습니다",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (expanded && loading) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PurpleAccent)
                    Text("포트폴리오 구조 분석 중…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// 포지션 → portfolio-review 호출. 콜백으로 결과 전달.
private suspend fun loadPortfolioReview(
    rows: List<HoldingRow>,
    api: EdgeApi,
    mode: String,
    refresh: Boolean,
    onResult: (PortfolioReview?) -> Unit,
) {
    if (rows.isEmpty()) { onResult(null); return }
    val positions = rows.associate { row ->
        row.item.code to Pair(row.avg, row.qty.toLong())
    }
    val result = runCatching { api.getPortfolioReview(positions, mode, refresh) }.getOrNull()
    onResult(result)
}
