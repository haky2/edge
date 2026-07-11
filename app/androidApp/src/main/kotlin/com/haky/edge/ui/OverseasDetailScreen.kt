package com.haky.edge.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.rememberCoroutineScope
import com.haky.edge.api.EdgeApi
import com.haky.edge.model.Analysis
import com.haky.edge.model.OverseasQuote
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import com.haky.edge.ui.theme.PurpleAccent
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverseasDetailScreen(
    item: WatchItem,
    initialQuote: OverseasQuote? = null,
    api: EdgeApi,
    onBack: () -> Unit,
) {
    var quote by remember { mutableStateOf(initialQuote) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadAnalysis() {
        analyzing = true
        analysis = runCatching { api.getOverseasAnalysis(code = item.code) }.getOrNull()
        analyzing = false
    }

    LaunchedEffect(item.code) {
        loading = true
        try {
            quote = api.getOverseasQuote(code = item.code)
        } catch (e: Exception) {
            error = "불러오기 실패: ${e.message}"
        }
        loading = false
    }

    // AI 코멘트는 시세와 독립 병렬 로드 — 백엔드가 당일 공유 캐시라 재진입은 즉시.
    LaunchedEffect(item.code) { loadAnalysis() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name, style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            error?.let { msg ->
                item {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            quote?.let { q ->
                item { PriceHeaderCard(q) }
                item { StatsCard(q) }
            }

            item {
                OverseasAiCommentCard(
                    analysis = analysis,
                    analyzing = analyzing,
                    onRetry = { scope.launch { loadAnalysis() } },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun PriceHeaderCard(q: OverseasQuote) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    priceText(q),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                )
                if (q.delayed) {
                    Text(
                        "15분 지연",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier
                            .background(OrangeAccent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = OrangeAccent,
                    )
                }
                Text(
                    q.currency,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            val isUp = q.changeRate > 0; val isDown = q.changeRate < 0
            val chgColor = if (isUp) ChangeUp else if (isDown) ChangeDown else MaterialTheme.colorScheme.onSurfaceVariant
            val arrow = if (isUp) "▲" else if (isDown) "▼" else "—"
            Text(
                "$arrow ${"%.2f".format(abs(q.change))} (${"%.2f".format(abs(q.changeRate))}%)",
                style = MaterialTheme.typography.bodyMedium,
                color = chgColor,
            )
        }
    }
}

@Composable
private fun StatsCard(q: OverseasQuote) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("주요 지표", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            StatRow("시가", priceText(q, q.open))
            StatRow("고가", priceText(q, q.high))
            StatRow("저가", priceText(q, q.low))
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StatRow("52주 고점", priceText(q, q.high52w))
            StatRow("52주 저점", priceText(q, q.low52w))
            StatRow("거래량", volumeText(q.volume))
        }
    }
}

// 해외 AI 코멘트 — 국내 AICommentCard의 경량판(모드·재생성·근거두께 없음, 시세+뉴스만 근거).
@Composable
private fun OverseasAiCommentCard(
    analysis: Analysis?,
    analyzing: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = PurpleAccent)
                Text("AI 코멘트", style = MaterialTheme.typography.titleSmall)
                Text(
                    "시세·뉴스 기반",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (analyzing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            if (analysis != null) {
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

                val paragraphs = analysis.comment.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    paragraphs.forEach { p ->
                        Text(
                            parseMarkdownBold(p),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        "참고용 · ${analysis.date} ${analysis.generatedAt} 생성 · 국내 종목과 달리 수급·공시 근거 없음",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "투자 판단과 책임은 본인에게 있습니다",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (analyzing) {
                Text("시세·뉴스를 종합해 코멘트를 생성하고 있어요…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("코멘트를 불러오지 못했어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "↻ 다시 시도",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = PurpleAccent,
                    modifier = Modifier.clickable { onRetry() }.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

private fun priceText(q: OverseasQuote, price: Double = q.price): String {
    val sym = if (q.currency == "USD") "$" else "${q.currency} "
    val digits = if (price < 10) 4 else if (price < 100) 3 else 2
    return "$sym${"%.${digits}f".format(price)}"
}

private fun volumeText(vol: Long): String = when {
    vol >= 1_000_000 -> "%.1fM".format(vol / 1_000_000.0)
    vol >= 1_000 -> "%.1fK".format(vol / 1_000.0)
    else -> "$vol"
}
