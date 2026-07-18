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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.haky.edge.analysis.AfterTaxSummary
import com.haky.edge.analysis.TaxEngine
import com.haky.edge.analysis.TaxablePosition
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.AccountRepository
import com.haky.edge.db.HoldingRepository
import com.haky.edge.model.AccountInfo
import com.haky.edge.model.DriftEntry
import com.haky.edge.model.PortfolioReview
import com.haky.edge.model.PortfolioRisk
import com.haky.edge.model.PortfolioRiskEntry
import com.haky.edge.model.RiskStock
import com.haky.edge.model.Quote
import com.haky.edge.model.RebalanceCheck
import com.haky.edge.db.WatchlistRepository
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

private fun taxLabelFor(name: String): String = when (name) {
    "ISA" -> "ISA"
    "IRP개인연금", "퇴직연금" -> "연금 (과세이연)"
    else -> "일반"
}

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

// 같은 종목을 여러 계좌에 나눠 담은 경우 전체 뷰에서 1행으로 병합 — 수량 합·가중평균 평단
// (hydrate와 동일 의미론, 투자원금 합 보존). portfolio-review positions 맵 유일성도 이걸로 보장.
private fun mergedByCode(rows: List<HoldingRow>): List<HoldingRow> {
    if (rows.map { it.item.code }.toSet().size == rows.size) return rows
    return rows.groupBy { it.item.code }.map { (_, g) ->
        if (g.size == 1) return@map g[0]
        val qtySum = g.sumOf { it.qty }
        val invested = g.sumOf { it.avg * it.qty }
        val avg = if (qtySum > 0) invested / qtySum else g[0].avg
        val first = g[0]
        first.copy(
            item = first.item.copy(
                avgPrice = avg,
                qty = qtySum.toLong(),
                targetPrice = g.firstNotNullOfOrNull { it.item.targetPrice },
                stopPrice = g.firstNotNullOfOrNull { it.item.stopPrice },
            ),
            avg = avg,
            qty = qtySum,
        )
    }
}

// ── PortfolioScreen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    holdingRepo: HoldingRepository,
    accountRepo: AccountRepository,
    watchlistRepo: WatchlistRepository,
    api: EdgeApi,
    onStockClick: ((WatchItem, Quote?, Long?) -> Unit)? = null,
) {
    var rows by remember { mutableStateOf<List<HoldingRow>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<AccountInfo>>(emptyList()) }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var sectorMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var portfolioReview by remember { mutableStateOf<PortfolioReview?>(null) }
    var rebalanceCheck by remember { mutableStateOf<RebalanceCheck?>(null) }
    var portfolioRisk by remember { mutableStateOf<PortfolioRisk?>(null) }
    var riskLoading by remember { mutableStateOf(false) }
    var reviewLoading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var showAccountMgmt by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun loadRisk() {
        val scopeId = selectedAccountId
        val scopeRows = if (scopeId == null) mergedByCode(rows) else rows.filter { it.accountId == scopeId }
        if (scopeRows.isEmpty()) return
        riskLoading = true
        portfolioRisk = runCatching {
            val positions = scopeRows.map { r -> PortfolioRiskEntry(r.item.code, r.qty.toLong()) }
            api.postPortfolioRisk(positions)
        }.getOrNull()
        riskLoading = false
    }

    // 진단 범위 = 계좌 탭 선택. 전체 탭은 병합 전체, 계좌 탭은 그 계좌만(빈 계좌면 카드 숨김).
    suspend fun loadReview(force: Boolean) {
        val scopeId = selectedAccountId
        val scopeRows = if (scopeId == null) rows else rows.filter { it.accountId == scopeId }
        reviewLoading = scopeRows.isNotEmpty()
        if (force) portfolioReview = null
        // 계좌 성격 — 계좌 탭은 그 계좌, 전체 탭은 보유 계좌 전부 장기일 때만 "long"(혼합=자유).
        val horizonIds = scopeId?.let { listOf(it) } ?: rows.map { it.accountId }.distinct()
        val horizon = accountRepo.effectiveHorizon(horizonIds)
            .takeIf { it == AccountRepository.HORIZON_LONG }
        loadPortfolioReview(scopeRows, api, AppPrefs.getMode(context), force, accountScope = scopeId != null, horizon = horizon) {
            // 응답 대기 중 계좌 탭이 바뀌었으면 폐기(늦게 온 이전 계좌 진단이 덮어쓰는 것 방지)
            if (scopeId == selectedAccountId) {
                portfolioReview = it
                reviewLoading = false
            }
        }
    }

    suspend fun load() {
        loading = true
        // G1: holding 테이블이 보유 포지션의 단일 정본
        val allHoldings = holdingRepo.all().filter { it.avgPrice != null && it.qty != null }
        // thesis는 watchlist 테이블 기반 — holding에 없으므로 별도 로드
        val thesisMap = watchlistRepo.all().mapNotNull { it.thesis?.let { t -> it.code to t } }.toMap()
        // G3: 계좌 목록 로드 (세그먼트 컨트롤 노출 여부 판단)
        accounts = accountRepo.all()
        // 삭제된 계좌를 가리키는 선택 해제 — 세그먼트가 숨어도 필터만 남아 빈 화면이 고착되는 것 방지
        val sel = selectedAccountId
        if (sel != null && (accounts.none { it.id == sel } || accounts.none { it.isDefault == 0L })) {
            selectedAccountId = null
        }
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
            // StockDetailScreen 연결용 WatchItem — holding 포지션 + watchlist 논지를 담아 전달
            val item = WatchItem(code = h.code, name = h.name,
                                 avgPrice = h.avgPrice, qty = h.qty,
                                 targetPrice = h.targetPrice, stopPrice = h.stopPrice,
                                 thesis = thesisMap[h.code])
            HoldingRow(item, quote, avg, qty, price, h.accountId)
        }
        loading = false
        // 섹터 분류 + 리스크 + 포트폴리오 진단은 화면 표시 차단 없이 별도 로드
        runCatching { api.getSectorClassify(codes) }.getOrNull()?.let { entries ->
            sectorMap = entries.associate { it.code to it.sectorLabel }
        }
        loadRisk()
        loadReview(false)
        // 리밸런싱 체크는 전체 포트폴리오 기준(R1 스냅샷)이라 전체 탭에서만 조회.
        rebalanceCheck = if (selectedAccountId == null) {
            runCatching { api.getRebalanceCheck() }.getOrNull()
        } else null
    }

    LaunchedEffect(Unit) { load() }

    // 계좌 탭 전환 → 그 계좌 범위로 진단 재조회(같은 날 같은 범위면 서버 캐시 적중).
    // 첫 컴포지션(rows 비어 있음)은 load()가 담당하므로 건너뜀.
    LaunchedEffect(selectedAccountId) {
        if (rows.isNotEmpty()) {
            portfolioReview = null
            portfolioRisk = null
            loadRisk()
            loadReview(false)
            rebalanceCheck = if (selectedAccountId == null) {
                runCatching { api.getRebalanceCheck() }.getOrNull()
            } else null
        }
    }

    if (showAccountMgmt) {
        AccountManagementSheet(
            accountRepo = accountRepo,
            holdingRepo = holdingRepo,
            onDismiss = {
                showAccountMgmt = false
                // 계좌 추가/삭제/보유 이전이 있었을 수 있다 — 세그먼트·rows 동기화
                scope.launch { load() }
            },
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
                    // G3: 선택 계좌 기준 필터링 (null = 전체, 다계좌 동일 종목은 병합)
                    val displayRows = if (selectedAccountId == null) mergedByCode(rows)
                                      else rows.filter { it.accountId == selectedAccountId }
                    // 세후 계산: raw rows (unmerged) per account → TaxEngine
                    val taxSourceRows = if (selectedAccountId == null) rows
                                        else rows.filter { it.accountId == selectedAccountId }
                    val accNameMap = accounts.associate { it.id to it.name }
                    val taxPositions = taxSourceRows.map { row ->
                        val name = accNameMap[row.accountId] ?: ""
                        TaxablePosition(code = row.item.code,
                                        taxType = TaxEngine.taxTypeOf(name),
                                        avgPrice = row.avg, qty = row.qty,
                                        currentPrice = row.price)
                    }
                    val afterTaxSummary = if (taxPositions.isNotEmpty()) TaxEngine.compute(taxPositions) else null
                    val afterTaxAccLabels: List<Pair<String, String>> = run {
                        val seen = mutableSetOf<Long>()
                        taxSourceRows.mapNotNull { row ->
                            if (!seen.add(row.accountId)) return@mapNotNull null
                            val name = accNameMap[row.accountId] ?: ""
                            name to taxLabelFor(name)
                        }
                    }
                    HoldingsList(
                        rows = displayRows,
                        accounts = accounts,
                        selectedAccountId = selectedAccountId,
                        onAccountSelect = { selectedAccountId = it },
                        onStockClick = onStockClick,
                        sectorRows = run {
                            val map = mutableMapOf<String, Double>()
                            for (row in displayRows) map[sectorMap[row.item.code] ?: "기타"] =
                                (map[sectorMap[row.item.code] ?: "기타"] ?: 0.0) + row.evaluated
                            map.entries.sortedByDescending { it.value }.map { it.key to it.value }
                        },
                        afterTaxSummary = afterTaxSummary,
                        afterTaxAccLabels = afterTaxAccLabels,
                        risk = portfolioRisk,
                        riskLoading = riskLoading,
                        review = portfolioReview,
                        rebalanceCheck = rebalanceCheck,
                        reviewLoading = reviewLoading,
                        onReviewRefresh = { scope.launch { loadReview(true) } },
                        onResetBaseline = {
                            // suspend 람다 — 카드가 스피너 상태를 완료 시점과 동기화한다
                            runCatching { api.postRebalanceBaseline() }
                            rebalanceCheck = runCatching { api.getRebalanceCheck() }.getOrNull()
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
    onStockClick: ((WatchItem, Quote?, Long?) -> Unit)? = null,
    sectorRows: List<Pair<String, Double>>,
    afterTaxSummary: AfterTaxSummary?,
    afterTaxAccLabels: List<Pair<String, String>>,
    risk: PortfolioRisk?,
    riskLoading: Boolean,
    review: PortfolioReview?,
    rebalanceCheck: RebalanceCheck?,
    reviewLoading: Boolean,
    onReviewRefresh: () -> Unit,
    onResetBaseline: suspend () -> Unit,
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
        if (afterTaxSummary != null) {
            item {
                AfterTaxCard(summary = afterTaxSummary, accLabels = afterTaxAccLabels)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (risk != null || riskLoading) {
            item {
                PortfolioRiskCard(risk = risk, loading = riskLoading)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (review != null || reviewLoading) {
            item {
                PortfolioReviewCard(
                    review = review,
                    rebalanceCheck = rebalanceCheck,
                    loading = reviewLoading,
                    accountLabel = selectedAccountId?.let { sel -> accounts.find { it.id == sel }?.name },
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
                    if (rows.isEmpty() && selectedAccountId != null) {
                        Text(
                            "이 계좌에 보유 종목이 없습니다",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        rows.forEachIndexed { i, row ->
                            HoldingRowItem(
                                row = row,
                                onClick = onStockClick?.let { cb -> { cb(row.item, row.quote, selectedAccountId) } },
                            )
                            if (i < rows.size - 1) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
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
private fun HoldingRowItem(row: HoldingRow, onClick: (() -> Unit)? = null) {
    val pnlColor = when {
        row.pnl > 0 -> ChangeUp
        row.pnl < 0 -> ChangeDown
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(onClick?.let { cb -> Modifier.clickable { cb() } } ?: Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
    accountLabel: String?,   // 계좌 탭 선택 중이면 진단 범위가 그 계좌임을 표시(전체 탭 = null)
    onRefresh: () -> Unit,
    onResetBaseline: suspend () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var commentExpanded by remember { mutableStateOf(false) }
    var baselineResetting by remember { mutableStateOf(false) }
    val resetScope = rememberCoroutineScope()

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
                    accountLabel?.let { label ->
                        Text(
                            "$label 기준",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = PurpleAccent,
                            modifier = Modifier
                                .background(PurpleAccent.copy(alpha = 0.12f), RoundedCornerShape(50))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
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
                                    // suspend 콜백 완료까지 스피너 유지 + 더블탭 방지
                                    resetScope.launch {
                                        baselineResetting = true
                                        onResetBaseline()
                                        baselineResetting = false
                                    }
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
// accountScope=true(계좌 탭 범위)면 서버가 리밸런싱 스냅샷(R1)을 갱신하지 않는다 — 부분 집합 오염 방지.
private suspend fun loadPortfolioReview(
    rows: List<HoldingRow>,
    api: EdgeApi,
    mode: String,
    refresh: Boolean,
    accountScope: Boolean = false,
    horizon: String? = null,
    onResult: (PortfolioReview?) -> Unit,
) {
    if (rows.isEmpty()) { onResult(null); return }
    // 다계좌 동일 종목은 병합해 전달 — code 키 맵이라 병합 없이는 마지막 계좌 값만 남는다
    // (계좌 범위 rows는 UNIQUE(code, account_id)라 병합이 no-op)
    val merged = mergedByCode(rows)
    val positions = merged.associate { row -> row.item.code to Pair(row.avg, row.qty.toLong()) }
    val theses = merged.mapNotNull { row ->
        row.item.thesis?.takeIf { it.isNotBlank() }?.let { row.item.code to it }
    }.toMap()
    val result = runCatching {
        api.getPortfolioReview(positions, theses, mode, refresh, accountScope = accountScope, horizon = horizon)
    }.getOrNull()
    onResult(result)
}

// ── 세후 손익 카드 ────────────────────────────────────────────────────────────

@Composable
private fun AfterTaxCard(summary: AfterTaxSummary, accLabels: List<Pair<String, String>>) {
    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)
    val netColor = when {
        summary.netPnl > 0 -> ChangeUp
        summary.netPnl < 0 -> ChangeDown
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    fun fmtPnl(v: Long): String = "${if (v > 0) "+" else ""}${fmt.format(v)}원"

    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
               verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 헤더
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%", style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF34A853), fontWeight = FontWeight.Bold)
                Text("세후 손익 (간이 · 전량 매도 기준)", style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider()
            // 세전 손익
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("세전 손익", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtPnl(summary.taxableGross), style = MaterialTheme.typography.labelSmall)
            }
            // 거래세
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("증권거래세 (0.2%)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("−${fmt.format(summary.transactionTax)}원",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 해외 양도세
            if (summary.hasOverseas) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("해외 양도세", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("−${fmt.format(summary.overseasTax)}원",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            // 세후 순손익
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("세후 순손익", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(fmtPnl(summary.netPnl),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = netColor)
            }
            // 연금 별도
            if (summary.hasPension) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("연금 계좌 (과세이연)", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = OrangeAccent)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("평가손익", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fmtPnl(summary.pensionGross), style = MaterialTheme.typography.labelSmall)
                    }
                    Text("세후 순손익에 미포함 · 인출 방식·시점에 따라 세율 가변 (3.3~16.5%).",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // ISA 안내
            if (summary.hasIsa) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ℹ", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ISA 계좌의 주식 매매차익은 일반 계좌와 동일하게 계산됩니다. ISA 비과세 한도는 배당·이자 소득에 해당됩니다.",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 계좌별 세제 (2개 이상)
            if (accLabels.size > 1) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("계좌별 적용 세제", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    accLabels.forEach { (name, label) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
                            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // footer
            HorizontalDivider()
            Text("간이 계산 (오늘 전량 매도 가정). 배당·연금 인출·대주주세·수수료 등 제외 — 정확한 세액은 세무사 상담.",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

// ── 리스크 스냅샷 카드 ───────────────────────────────────────────────────────

@Composable
private fun PortfolioRiskCard(risk: PortfolioRisk?, loading: Boolean) {
    val tealColor = Color(0xFF009688)
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
               verticalArrangement = Arrangement.spacedBy(0.dp)) {
            // ── 헤더 ──
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("리스크 스냅샷",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                risk?.let { r ->
                    val (hhiLabel, hhiColor) = when {
                        r.hhi < 1500 -> "분산형" to Color(0xFF4CAF50)
                        r.hhi < 2500 -> "보통" to Color(0xFFFF9800)
                        else -> "집중형" to Color(0xFFF44336)
                    }
                    Text(hhiLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = hhiColor,
                        modifier = Modifier
                            .background(hhiColor.copy(alpha = 0.15f), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (loading) {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }

            if (risk == null) {
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("리스크 계산 중…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Column
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            // ── 수치 요약 행 ──
            Row(modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp))
                    .padding(vertical = 6.dp)) {
                RiskStatCell("변동성", String.format("%.1f%%", risk.portfolioVolPct),
                             Modifier.weight(1f))
                risk.portfolioBeta?.let { beta ->
                    RiskStatCell("베타", String.format("%.2f", beta), Modifier.weight(1f))
                }
                RiskStatCell("분산효과", String.format("%.2f배", risk.diversificationRatio),
                             Modifier.weight(1f))
                risk.avgCorr?.let { ac ->
                    RiskStatCell("평균상관", String.format("%.2f", ac), Modifier.weight(1f))
                }
            }

            // ── 클러스터 경고 ──
            val warnClusters = risk.clusters.filter { it.names.size > 1 }
            if (warnClusters.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    warnClusters.take(2).forEach { cl ->
                        Row(verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🔗", style = MaterialTheme.typography.labelSmall)
                            Text("${cl.names.joinToString("·")} 동조 클러스터 " +
                                 "(${String.format("%.0f", cl.weightPct)}% 비중, r≥0.7)",
                                style = MaterialTheme.typography.labelSmall,
                                color = OrangeAccent)
                        }
                    }
                }
            }

            // ── 종목별 비중 vs 리스크 기여도 ──
            if (risk.stocks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("종목", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(1f))
                    Text("비중", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                    Text("리스크 기여", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
                }
                Spacer(Modifier.height(4.dp))
                risk.stocks.sortedByDescending { it.riskContribPct }.forEach { s ->
                    val diff = s.riskContribPct - s.weightPct
                    val diffColor = when {
                        diff > 5 -> ChangeUp
                        diff < -5 -> ChangeDown
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(String.format("%.1f%%", s.weightPct),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(44.dp))
                        val sign = if (diff >= 0) "+" else ""
                        Text("$sign${String.format("%.1f", diff)}%p",
                            style = MaterialTheme.typography.labelSmall,
                            color = diffColor,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp))
                        Text(String.format("%.1f%%", s.riskContribPct),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (s.riskContribPct > s.weightPct + 5) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (s.riskContribPct > s.weightPct + 5) ChangeUp
                                    else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(44.dp))
                    }
                }
            }

            // ── 제외 종목 caveat ──
            if (risk.excluded.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text(risk.caveat.ifEmpty { "${risk.excluded.joinToString(", ")} 제외 (관측 부족)" },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── 날짜 ──
            Spacer(Modifier.height(6.dp))
            Text("${risk.date} · ${risk.windowDays}거래일 실측",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun RiskStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
