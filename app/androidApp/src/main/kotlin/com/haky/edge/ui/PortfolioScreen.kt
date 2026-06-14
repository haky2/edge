package com.haky.edge.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.Quote
import com.haky.edge.model.WatchItem
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
    watchlistRepo: WatchlistRepository,
    api: EdgeApi,
) {
    var rows by remember { mutableStateOf<List<HoldingRow>>(emptyList()) }
    var sectorMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        val all = watchlistRepo.all()
        val holdings = all.filter { it.avgPrice != null && it.qty != null }
        if (holdings.isEmpty()) {
            rows = emptyList()
            loading = false
            return
        }
        val codes = holdings.map { it.code }
        val quotes = runCatching { api.getQuotes(codes) }.getOrDefault(emptyList())
        val quoteMap = quotes.associateBy { it.code }
        rows = holdings.mapNotNull { item ->
            val avg = item.avgPrice ?: return@mapNotNull null
            val qty = (item.qty ?: return@mapNotNull null).toDouble()
            val quote = quoteMap[item.code]
            val price = quote?.let { it.price.toDouble() } ?: avg
            HoldingRow(item, quote, avg, qty, price)
        }
        loading = false
        // 섹터 분류는 화면 표시 차단 없이 별도 로드
        runCatching { api.getSectorClassify(codes) }.getOrNull()?.let { entries ->
            sectorMap = entries.associate { it.code to it.sectorLabel }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            CompactHeader(
                title = "내 자산",
                actions = {
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
                else -> HoldingsList(rows, sectorMap)
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

@Composable
private fun HoldingsList(rows: List<HoldingRow>, sectorMap: Map<String, String>) {
    val sectorRows = run {
        val map = mutableMapOf<String, Double>()
        for (row in rows) {
            val sector = sectorMap[row.item.code] ?: "기타"
            map[sector] = (map[sector] ?: 0.0) + row.evaluated
        }
        map.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SummaryCard(rows = rows, sectorRows = sectorRows)
            Spacer(Modifier.height(8.dp))
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(row.item.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(68.dp))
                DivergingBar(
                    pnl = row.pnl,
                    maxAbs = maxAbs,
                    modifier = Modifier.weight(1f).height(10.dp),
                )
                val sign = if (row.pnl >= 0) "+" else ""
                Text("$sign${fmt.format(row.pnl.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.pnl >= 0) ChangeUp else ChangeDown,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.widthIn(min = 64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun DivergingBar(pnl: Double, maxAbs: Double, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val half = size.width / 2f
        val ratio = (abs(pnl) / maxAbs).coerceAtMost(1.0).toFloat()
        val fillW = max(2f, half * ratio)
        // 중심선
        drawLine(
            color = Color.Gray.copy(alpha = 0.4f),
            start = Offset(half, 0f),
            end = Offset(half, size.height),
            strokeWidth = 1f,
        )
        if (pnl >= 0) {
            drawRoundRect(
                color = ChangeUp.copy(alpha = 0.65f),
                topLeft = Offset(half, 1f),
                size = Size(fillW, size.height - 2f),
                cornerRadius = CornerRadius(2f),
            )
        } else {
            drawRoundRect(
                color = ChangeDown.copy(alpha = 0.65f),
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
