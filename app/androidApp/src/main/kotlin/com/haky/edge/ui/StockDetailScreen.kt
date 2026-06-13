package com.haky.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
            quote?.let { q -> MarketDataCard(q) }
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

// ─── 시세 카드 ───────────────────────────────────────────

@Composable
private fun MarketDataCard(q: Quote) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("시세", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(2.dp))
        val rows = listOf(
            listOf("거래량" to q.volume.fmt(), "시가" to q.open.fmt()),
            listOf("고가" to q.high.fmt(), "저가" to q.low.fmt()),
            listOf("52주 최고" to q.high52w.fmt(), "52주 최저" to q.low52w.fmt()),
        )
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { idx, (label, value) ->
                    if (idx > 0) Spacer(modifier = Modifier.width(16.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
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
