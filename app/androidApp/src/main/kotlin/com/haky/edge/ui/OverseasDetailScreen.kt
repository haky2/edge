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
import com.haky.edge.api.EdgeApi
import com.haky.edge.model.OverseasQuote
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverseasDetailScreen(
    item: WatchItem,
    api: EdgeApi,
    onBack: () -> Unit,
) {
    var quote by remember { mutableStateOf<OverseasQuote?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.code) {
        loading = true
        try {
            quote = api.getOverseasQuote(code = item.code)
        } catch (e: Exception) {
            error = "불러오기 실패: ${e.message}"
        }
        loading = false
    }

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
