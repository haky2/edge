package com.haky.edge.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.Quote
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    watchlistRepo: WatchlistRepository,
    api: EdgeApi,
    onStockClick: (WatchItem, Quote?) -> Unit,
    onAddClick: () -> Unit,
) {
    var items by remember { mutableStateOf<List<WatchItem>>(emptyList()) }
    var quotes by remember { mutableStateOf<Map<String, Quote>>(emptyMap()) }
    var supplyBadges by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var sparklines by remember { mutableStateOf<Map<String, List<Double>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadSparklines(watchlist: List<WatchItem>) {
        val todayStr = run {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"))
            "%04d%02d%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        }
        coroutineScope {
            val results = watchlist.map { item ->
                async {
                    try {
                        val bars = api.getDaily(code = item.code, bars = 8)
                        if (bars.size < 2) return@async item.code to emptyList()
                        val pastBars = bars.filter { it.date != todayStr }
                        if (pastBars.size < 2) return@async item.code to emptyList()
                        val closes = pastBars.reversed().takeLast(7).map { it.close.toDouble() }
                        item.code to closes
                    } catch (_: Exception) {
                        item.code to emptyList()
                    }
                }
            }.awaitAll()
            sparklines = results.filter { it.second.isNotEmpty() }.toMap()
        }
    }

    suspend fun loadSupplyBadges(watchlist: List<WatchItem>) {
        coroutineScope {
            val results = watchlist.map { item ->
                async {
                    try {
                        val flows = api.getInvestorFlow(code = item.code, days = 3)
                        if (flows.size < 3) return@async item.code to emptyList<String>()
                        val labels = mutableListOf<String>()
                        if (flows[0].foreign > 0 && flows[1].foreign > 0 && flows[2].foreign > 0) labels.add("외인 3일↑")
                        if (flows[0].institution > 0 && flows[1].institution > 0 && flows[2].institution > 0) labels.add("기관 3일↑")
                        item.code to labels
                    } catch (_: Exception) {
                        item.code to emptyList<String>()
                    }
                }
            }.awaitAll()
            supplyBadges = results.filter { it.second.isNotEmpty() }.toMap()
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            val watchlist = watchlistRepo.all()
            items = watchlist
            try {
                val quoteList = api.getQuotes(watchlist.map { it.code })
                quotes = quoteList.associateBy { it.code }
            } catch (e: Exception) {
                error = "불러오기 실패: ${e.message}"
            }
            loading = false
            launch { loadSparklines(watchlist) }
            launch { loadSupplyBadges(watchlist) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            CompactHeader(title = "관심종목") {
                IconButton(onClick = onAddClick) {
                    Icon(Icons.Filled.Add, contentDescription = "종목 추가")
                }
                IconButton(onClick = { refresh() }) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
                    }
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                error?.let { msg ->
                    item {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                if (items.isEmpty() && !loading) {
                    item {
                        EmptyWatchlist(
                            onAddClick = onAddClick,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                items.forEachIndexed { index, watchItem ->
                                    key(watchItem.code) {
                                        SwipeToDeleteRow(
                                            onDelete = {
                                                watchlistRepo.remove(watchItem.code)
                                                items = items.filter { it.code != watchItem.code }
                                            }
                                        ) {
                                            WatchlistRow(
                                                item = watchItem,
                                                quote = quotes[watchItem.code],
                                                sparklines = sparklines[watchItem.code] ?: emptyList(),
                                                badges = supplyBadges[watchItem.code] ?: emptyList(),
                                                onClick = { onStockClick(watchItem, quotes[watchItem.code]) },
                                            )
                                        }
                                    }
                                    if (index < items.size - 1) {
                                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWatchlist(onAddClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "관심종목이 없어요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "종목을 검색해 관심종목에 추가하면\n시세·수급·AI 분석을 한눈에 볼 수 있어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAddClick) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("종목 추가")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFF3B30))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "삭제", tint = Color.White)
            }
        },
        content = { content() },
    )
}

@Composable
private fun WatchlistRow(
    item: WatchItem,
    quote: Quote?,
    sparklines: List<Double>,
    badges: List<String>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 종목명 + 코드 + 수급 배지
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    item.code,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                badges.forEach { badge ->
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier
                            .background(OrangeAccent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        color = OrangeAccent,
                    )
                }
            }
        }

        // 추세 아이콘 + 연속일수
        if (quote != null && sparklines.size >= 2) {
            val isUp = quote.changeRate > 0
            val isDown = quote.changeRate < 0
            val streak = if (isUp || isDown) consecutiveStreak(sparklines, isUp) else 0
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Icon(
                    imageVector = if (isUp) Icons.Filled.TrendingUp else if (isDown) Icons.Filled.TrendingDown else Icons.Filled.TrendingFlat,
                    contentDescription = null,
                    tint = if (isUp) ChangeUp else if (isDown) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                if (isUp || isDown) {
                    Text(
                        "${streak}일째 ${if (isUp) "상승" else "하락"}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (isUp) ChangeUp else ChangeDown,
                    )
                } else {
                    Text(
                        "보합",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 현재가 + 등락률
        if (quote != null) {
            val chgColor = when {
                quote.changeRate > 0 -> ChangeUp
                quote.changeRate < 0 -> ChangeDown
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val symbol = when {
                quote.changeRate > 0 -> "▲"
                quote.changeRate < 0 -> "▼"
                else -> "—"
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "%,d".format(quote.price),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    "$symbol ${"%.2f".format(abs(quote.changeRate))}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = chgColor,
                )
            }
        } else {
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun consecutiveStreak(closes: List<Double>, todayUp: Boolean): Int {
    var count = 1
    for (i in closes.size - 1 downTo 1) {
        if ((closes[i] >= closes[i - 1]) == todayUp) count++ else break
    }
    return minOf(count, 7)
}
