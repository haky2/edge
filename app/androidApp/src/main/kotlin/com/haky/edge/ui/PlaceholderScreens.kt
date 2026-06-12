package com.haky.edge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.Quote
import com.haky.edge.model.WatchItem

// ──── 종목 상세 스텁 (Batch B에서 전체 구현) ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailStubScreen(
    item: WatchItem,
    quote: Quote?,
    watchlistRepo: WatchlistRepository,
    actionLogRepo: ActionLogRepository,
    api: EdgeApi,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall)
                Text(item.code, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (quote != null) {
                    Text(
                        "%,d원".format(quote.price),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    "종목 상세 — Batch C에서 구현",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

// ──── 내 자산 탭 스텁 (Batch E에서 구현) ───────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    watchlistRepo: WatchlistRepository,
    api: EdgeApi,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("내 자산") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("내 자산 — Batch E에서 구현", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ──── 브리핑 탭 스텁 (Batch D에서 구현) ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefingScreen(
    api: EdgeApi,
    watchlistRepo: WatchlistRepository,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("브리핑") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("브리핑 — Batch D에서 구현", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ──── 내 패턴 탭 스텁 (Batch E에서 구현) ───────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    watchlistRepo: WatchlistRepository,
    actionLogRepo: ActionLogRepository,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("내 패턴") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("내 패턴 — Batch E에서 구현", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ──── 설정 탭 (분석 모드 토글 포함) ───────────────────────────────────────

private val analysisModes = listOf("defensive" to "방어 🛡️", "aggressive" to "공격 ⚔️")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var modeIndex by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Text("분석 모드", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))

            SingleChoiceSegmentedButtonRow {
                analysisModes.forEachIndexed { index, (_, label) ->
                    SegmentedButton(
                        selected = modeIndex == index,
                        onClick = { modeIndex = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = analysisModes.size),
                        label = { Text(label) },
                    )
                }
            }

            Text(
                text = if (modeIndex == 1)
                    "⚔️ 공격적 모드는 계산된 지표에 근거한 단호한 의견을 제시해요. 브리핑에서는 포트폴리오 스탠스(비중 조절·현금 확보 등), 종목상세에서는 평단 손익·신호·밸류 위치를 근거로 개별 종목 매매 판단까지 포함돼요. 참고용이며 투자 책임은 본인에게 있어요."
                else
                    "🛡️ 방어적 모드는 사실과 방향만 담백하게 전달해요. 적극적인 시장 스탠스 의견을 보려면 공격으로 바꿔보세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
