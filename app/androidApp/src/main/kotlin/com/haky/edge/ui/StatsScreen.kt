package com.haky.edge.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.ActionLogEntry
import com.haky.edge.model.WatchItem
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import com.haky.edge.ui.theme.PurpleAccent
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

// ── 내부 모델 ────────────────────────────────────────────────────────────────

private data class HoldPair(
    val id: String,
    val code: String,
    val buyAt: Long,
    val sellAt: Long,
    val buyPrice: Long?,
    val sellPrice: Long?,
    val buyReason: String?,
) {
    val days: Int get() = ((sellAt - buyAt) / (1000 * 60 * 60 * 24)).toInt()
    val isWin: Boolean? get() {
        val b = buyPrice?.toDouble() ?: return null
        val s = sellPrice?.toDouble() ?: return null
        return if (b > 0) s > b else null
    }
}

private data class WinRateRow(
    val reason: String,
    val wins: Int,
    val losses: Int,
) {
    val total: Int get() = wins + losses
    val rate: Double get() = if (total > 0) wins.toDouble() / total * 100 else 0.0
    val isReliable: Boolean get() = total >= 5
}

private enum class DisciplineStatus { StopViolated, StopRespected, TargetReached, ProfitExit }

private data class DisciplineRow(
    val id: String,
    val code: String,
    val buyAt: Long,
    val sellAt: Long,
    val buyPrice: Long,
    val sellPrice: Long,
    val stopPrice: Long?,
    val targetPrice: Long?,
) {
    val actualReturnPct: Double get() = (sellPrice - buyPrice).toDouble() / buyPrice * 100
    val status: DisciplineStatus get() {
        if (targetPrice != null && sellPrice >= targetPrice) return DisciplineStatus.TargetReached
        if (stopPrice != null && sellPrice < stopPrice) return DisciplineStatus.StopViolated
        if (stopPrice != null && sellPrice < buyPrice) return DisciplineStatus.StopRespected
        return DisciplineStatus.ProfitExit
    }
    val stopOvershootPct: Double? get() {
        if (status != DisciplineStatus.StopViolated || stopPrice == null) return null
        return (sellPrice - stopPrice).toDouble() / buyPrice * 100
    }
}

private data class MissedRow(
    val code: String,
    val name: String,
    val lastInterestAt: Long,
    val loggedPrice: Long?,
    var lookbackPrice: Long? = null,
    var currentPrice: Long? = null,
) {
    val thenPrice: Long? get() = loggedPrice ?: lookbackPrice
    val hypotheticalReturn: Double? get() {
        val then = thenPrice?.toDouble() ?: return null
        val now = currentPrice?.toDouble() ?: return null
        return if (then > 0) (now - then) / then * 100 else null
    }
}

// ── StatsScreen ───────────────────────────────────────────────────────────────

@Composable
fun StatsScreen(
    watchlistRepo: WatchlistRepository,
    actionLogRepo: ActionLogRepository,
    api: EdgeApi,
) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<ActionLogEntry>>(emptyList()) }
    var nameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var positionMap by remember { mutableStateOf<Map<String, WatchItem>>(emptyMap()) }
    var missedRows by remember { mutableStateOf<List<MissedRow>>(emptyList()) }
    var missedLoading by remember { mutableStateOf(false) }

    // 접기/펼치기 — SharedPrefs 영속
    var recentExpanded by remember { mutableStateOf(AppPrefs.getStatsExpanded(ctx, AppPrefs.STATS_RECENT)) }
    var codeExpanded   by remember { mutableStateOf(AppPrefs.getStatsExpanded(ctx, AppPrefs.STATS_CODE)) }
    var holdExpanded   by remember { mutableStateOf(AppPrefs.getStatsExpanded(ctx, AppPrefs.STATS_HOLD)) }
    var reasonExpanded by remember { mutableStateOf(AppPrefs.getStatsExpanded(ctx, AppPrefs.STATS_REASON)) }

    // 집계 계산
    val pairRows = remember(entries) { computePairs(entries) }
    val winRateRows = remember(pairRows) { computeWinRates(pairRows) }
    val disciplineRows = remember(pairRows, positionMap) { computeDiscipline(pairRows, positionMap) }
    val reasonRows = remember(entries) { computeReasons(entries) }
    val codeRows = remember(entries) { computeCodeRows(entries) }
    val avgHoldDays = remember(pairRows) { if (pairRows.isEmpty()) null else pairRows.sumOf { it.days }.toDouble() / pairRows.size }

    LaunchedEffect(Unit) {
        entries = actionLogRepo.getAll()
        val watchItems = watchlistRepo.all()
        nameMap = watchItems.associate { it.code to it.name }
        positionMap = watchItems.associateBy { it.code }

        // 놓친 종목 비동기 로드
        missedLoading = true
        missedRows = loadMissed(entries, nameMap, api)
        missedLoading = false
    }

    Scaffold(
        topBar = { CompactHeader(title = "내 패턴") }
    ) { innerPadding ->
        if (entries.isEmpty() && !missedLoading) {
            EmptyStats(modifier = Modifier.fillMaxSize().padding(innerPadding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            // ── 요약 ──
            item {
                val oldestDate = entries.minOfOrNull { it.createdAt }
            SectionCard(
                header = if (oldestDate != null) "전체 ${entries.size}건 · ${shortDate(oldestDate)}부터" else "전체 ${entries.size}건",
                footer = "종목 상세 화면에서 관심·매수·매도를 기록할 때마다 쌓여요.",
            ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        SummaryCell(entries.count { it.action == "buy" }, "매수", ChangeUp, Modifier.weight(1f))
                        VerticalDivider()
                        SummaryCell(entries.count { it.action == "sell" }, "매도", ChangeDown, Modifier.weight(1f))
                        VerticalDivider()
                        SummaryCell(entries.count { it.action == "interest" }, "관심", OrangeAccent, Modifier.weight(1f))
                    }
                }
            }

            // ── 손절/익절 규율 ──
            item {
                val dRows = disciplineRows
                val violations = dRows.filter { it.status == DisciplineStatus.StopViolated }
                val targets = dRows.filter { it.status == DisciplineStatus.TargetReached }
                var showAllDiscipline by remember { mutableStateOf(false) }
                SectionCard(
                    header = if (dRows.isEmpty()) "손절/익절 규율" else "손절/익절 규율 (${dRows.size}쌍)",
                    footer = "종목 상세에서 설정한 손절가·목표가 기준으로 실제 매도가 규율을 지켰는지 확인해요.",
                ) {
                    if (dRows.isEmpty()) {
                        Text("기준가(목표가·손절가)가 설정된\n매수→매도 쌍이 필요해요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            DiscCell(violations.size, "손절 어김", ChangeDown, Modifier.weight(1f))
                            VerticalDivider()
                            DiscCell(targets.size, "목표 달성", ChangeUp, Modifier.weight(1f))
                        }
                        if (violations.isNotEmpty()) {
                            val overshoots = violations.mapNotNull { it.stopOvershootPct }
                            val avg = overshoots.sum() / overshoots.size
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("평균 손절선 초과", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(String.format("%.1f%%p", avg),
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                        color = ChangeDown)
                                    Text("더 손실 후 매도", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        val displayRows = if (showAllDiscipline || dRows.size <= 5) dRows else dRows.take(5)
                        displayRows.forEach { row ->
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            DisciplineRowItem(row, nameMap)
                        }
                        if (dRows.size > 5) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                if (showAllDiscipline) "접기 ↑" else "${dRows.size - 5}건 더 보기 ↓",
                                style = MaterialTheme.typography.labelSmall,
                                color = PurpleAccent,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAllDiscipline = !showAllDiscipline }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // ── 신호별 승률 ──
            item {
                val wRows = winRateRows
                val totalPairs = wRows.sumOf { it.total }
                SectionCard(
                    header = if (wRows.isEmpty()) "신호별 승률" else "신호별 승률 (${wRows.size}개 신호 · ${totalPairs}쌍)",
                    footer = "매수할 때 입력한 사유 태그가 '신호'예요. 쌍이 5개 미만(n=N 표시)인 신호는 표본 부족으로 신뢰하기 어려워요.",
                ) {
                    if (wRows.isEmpty()) {
                        Text("아직 계산할 수 없어요\n(가격이 기록된 매수→매도 쌍이 필요해요)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        wRows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            WinRateRowItem(row)
                        }
                    }
                }
            }

            // ── 놓친 종목 ──
            item {
                SectionCard(
                    header = "놓친 종목 (관심 후 미매수 ${missedRows.size}개)",
                    footer = "종목 상세 하단 '관심 기록' 버튼으로 기록된 종목 중 매수하지 않은 것의 가상 수익률이에요.",
                ) {
                    when {
                        missedLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("현재가 확인 중…", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        missedRows.isEmpty() -> Text("관심 기록 후 매수하지 않은 종목이 없어요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> missedRows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            MissedRowItem(row)
                        }
                    }
                }
            }

            // ── 사유 태그 (접기) ──
            item {
                CollapsibleSectionCard(
                    title = "사유 태그",
                    sub = if (reasonRows.isEmpty()) null else "Top ${min(reasonRows.size, 8)}개",
                    expanded = reasonExpanded,
                    onToggle = { reasonExpanded = it; AppPrefs.setStatsExpanded(ctx, AppPrefs.STATS_REASON, it) },
                    footer = "관심·매수·매도 기록 시 입력한 사유 태그 빈도. 어떤 이유로 행동하는지 패턴을 볼 수 있어요.",
                ) {
                    if (reasonRows.isEmpty()) {
                        Text("아직 사유 태그 기록이 없어요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val maxCount = reasonRows.first().second.toFloat()
                        reasonRows.take(8).forEachIndexed { i, (reason, count) ->
                            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(reason, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(8.dp))
                                Text("${count}회", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Canvas(modifier = Modifier.width(60.dp).height(6.dp)) {
                                    val barW = size.width * (count.toFloat() / maxCount)
                                    val rankAlpha = (0.85f - i * 0.07f).coerceIn(0.25f, 0.9f)
                                    drawRoundRect(PurpleAccent.copy(alpha = rankAlpha), size = Size(barW, size.height),
                                        cornerRadius = CornerRadius(2f))
                                }
                            }
                        }
                    }
                }
            }

            // ── 최근 활동 (접기) ──
            item {
                CollapsibleSectionCard(
                    title = "최근 활동",
                    sub = "최근 ${min(entries.size, 20)}건",
                    expanded = recentExpanded,
                    onToggle = { recentExpanded = it; AppPrefs.setStatsExpanded(ctx, AppPrefs.STATS_RECENT, it) },
                    footer = "가장 최근 기록 20건. 상세 화면에서 관심·매수·매도 버튼을 누를 때마다 쌓여요.",
                ) {
                    entries.take(20).forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider(
                            modifier = Modifier.padding(vertical = 3.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        )
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionPill(actionLabel(e.action), actionColor(e.action))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(nameMap[e.code] ?: e.code, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val reason = e.reason
                                if (reason != null) {
                                    Text(reason, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(shortTs(e.createdAt), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (e.price != null) {
                                    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)
                                    Text("${fmt.format(e.price)}원", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // ── 종목별 활동 (접기) ──
            item {
                CollapsibleSectionCard(
                    title = "종목별 활동",
                    sub = "${codeRows.size}종목",
                    expanded = codeExpanded,
                    onToggle = { codeExpanded = it; AppPrefs.setStatsExpanded(ctx, AppPrefs.STATS_CODE, it) },
                    footer = "종목마다 관심·매수·매도를 몇 번 기록했는지. 자주 들여다본 종목이 위에 나와요.",
                ) {
                    codeRows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(nameMap[row.code] ?: row.code, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(row.code, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (row.buys > 0) ActionPill("${row.buys}매수", ChangeUp)
                            if (row.sells > 0) ActionPill("${row.sells}매도", ChangeDown)
                            if (row.interests > 0) ActionPill("${row.interests}관심", OrangeAccent)
                        }
                    }
                }
            }

            // ── 보유기간 (접기) ──
            if (avgHoldDays != null || pairRows.isNotEmpty()) {
                item {
                    CollapsibleSectionCard(
                        title = "매수→매도 보유기간",
                        sub = if (pairRows.isEmpty()) null else "${pairRows.size}쌍",
                        expanded = holdExpanded,
                        onToggle = { holdExpanded = it; AppPrefs.setStatsExpanded(ctx, AppPrefs.STATS_HOLD, it) },
                        footer = "매수 기록 후 같은 종목을 매도하기까지 걸린 시간. 7일 이하는 주황색으로 강조돼요.",
                    ) {
                        avgHoldDays?.let { avg ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("평균 보유기간", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(holdLabel(avg), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    Text("(${pairRows.size}쌍 기준)", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        pairRows.forEachIndexed { i, row ->
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(nameMap[row.code] ?: row.code, style = MaterialTheme.typography.bodyMedium)
                                    Text("${shortDate(row.buyAt)} → ${shortDate(row.sellAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(holdLabel(row.days.toDouble()),
                                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                    color = if (row.days <= 7) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ── 빈 화면 ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyStats(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Insights,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "기록이 없어요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "종목 상세 화면에서 관심·매수·매도를\n기록하면 여기서 패턴을 볼 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── 공통 카드 컴포넌트 ────────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    header: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                header,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
            content()
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun CollapsibleSectionCard(
    title: String,
    sub: String?,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (sub != null) {
                        Text(sub, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "접기" else "펼치기",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))
                    content()
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

// ── 서브 컴포넌트 ─────────────────────────────────────────────────────────────

@Composable
private fun SummaryCell(count: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiscCell(count: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = if (count > 0) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VerticalDivider() {
    Box(modifier = Modifier.width(1.dp).height(36.dp),
        contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(1.dp).height(36.dp)
            .then(Modifier.padding(vertical = 6.dp)),
            ) { /* divider */ }
        HorizontalDivider(modifier = Modifier.height(36.dp).width(1.dp))
    }
}

@Composable
private fun WinRateRowItem(row: WinRateRow) {
    val rateColor = when {
        !row.isReliable -> MaterialTheme.colorScheme.onSurfaceVariant
        row.rate >= 60 -> ChangeUp
        row.rate >= 40 -> MaterialTheme.colorScheme.onSurface
        else -> ChangeDown
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(row.reason, style = MaterialTheme.typography.bodyMedium,
                color = if (row.isReliable) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!row.isReliable) {
                    Text("n=${row.total}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${String.format("%.0f", row.rate)}%",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = rateColor)
                Text("${row.wins}승 ${row.losses}패",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 승률 바
        Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            val total = row.total.toFloat()
            if (total == 0f) return@Canvas
            val winW = size.width * row.wins / total - 1
            val lossW = size.width * row.losses / total - 1
            val winAlpha = if (row.isReliable) 0.65f else 0.25f
            val lossAlpha = if (row.isReliable) 0.4f else 0.15f
            if (row.wins > 0) {
                drawRoundRect(ChangeUp.copy(alpha = winAlpha),
                    size = Size(winW.coerceAtLeast(0f), size.height), cornerRadius = CornerRadius(2f))
            }
            if (row.losses > 0) {
                drawRoundRect(ChangeDown.copy(alpha = lossAlpha),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - lossW.coerceAtLeast(0f), 0f),
                    size = Size(lossW.coerceAtLeast(0f), size.height), cornerRadius = CornerRadius(2f))
            }
        }
    }
}

@Composable
private fun DisciplineRowItem(row: DisciplineRow, nameMap: Map<String, String>) {
    val (statusLabel, statusColor) = when (row.status) {
        DisciplineStatus.StopViolated  -> "손절 어김" to ChangeDown
        DisciplineStatus.StopRespected -> "손절 지킴" to Color(0xFF009688)
        DisciplineStatus.TargetReached -> "목표 달성" to ChangeUp
        DisciplineStatus.ProfitExit    -> "수익 청산" to OrangeAccent
    }
    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(statusLabel, statusColor)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(nameMap[row.code] ?: row.code, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${shortDate(row.buyAt)} → ${shortDate(row.sellAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val ret = row.actualReturnPct
            val sign = if (ret >= 0) "+" else ""
            Text("$sign${String.format("%.1f%%", ret)}",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = if (ret >= 0) ChangeUp else ChangeDown)
            row.stopOvershootPct?.let { ov ->
                Text("손절선 대비 ${String.format("%.1f%%p", ov)}",
                    style = MaterialTheme.typography.labelSmall, color = ChangeDown.copy(alpha = 0.8f))
            }
            if (row.stopOvershootPct == null && row.status == DisciplineStatus.StopRespected && row.stopPrice != null) {
                Text("손절선 ${fmt.format(row.stopPrice)}원 위",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.status == DisciplineStatus.TargetReached && row.targetPrice != null) {
                Text("목표 ${fmt.format(row.targetPrice)}원 달성",
                    style = MaterialTheme.typography.labelSmall, color = ChangeUp.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun MissedRowItem(row: MissedRow) {
    val fmt = NumberFormat.getNumberInstance(Locale.KOREA)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            row.hypotheticalReturn?.let { ret ->
                val sign = if (ret >= 0) "+" else ""
                Text("$sign${String.format("%.1f%%", ret)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = if (ret >= 0) ChangeUp else ChangeDown)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("관심 ${shortDate(row.lastInterestAt)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.thenPrice?.let { then ->
                Text("·", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("당시 ${fmt.format(then)}원", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Text("· 가격 미기록", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.currentPrice?.let { now ->
                Text("→ 현재 ${fmt.format(now)}원", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ── 집계 연산 ────────────────────────────────────────────────────────────────

private fun computePairs(entries: List<ActionLogEntry>): List<HoldPair> {
    val result = mutableListOf<HoldPair>()
    val codes = entries.map { it.code }.toSet()
    for (code in codes) {
        val codeEntries = entries.filter { it.code == code }
        val buys  = codeEntries.filter { it.action == "buy" }.sortedBy { it.createdAt }
        val sells = codeEntries.filter { it.action == "sell" }.sortedBy { it.createdAt }
        var si = 0
        for (buy in buys) {
            while (si < sells.size && sells[si].createdAt <= buy.createdAt) si++
            if (si >= sells.size) break
            val sell = sells[si]
            result.add(HoldPair(
                id = "${code}_${buy.createdAt}",
                code = code,
                buyAt = buy.createdAt, sellAt = sell.createdAt,
                buyPrice = buy.price, sellPrice = sell.price,
                buyReason = buy.reason,
            ))
            si++
        }
    }
    return result.sortedByDescending { it.sellAt }
}

private fun computeWinRates(pairs: List<HoldPair>): List<WinRateRow> {
    val map = mutableMapOf<String, Pair<Int, Int>>()
    for (pair in pairs) {
        val win = pair.isWin ?: continue
        val reason = pair.buyReason?.takeIf { it.isNotBlank() } ?: continue
        val (w, l) = map[reason] ?: (0 to 0)
        map[reason] = if (win) (w + 1 to l) else (w to l + 1)
    }
    return map.map { (reason, wl) -> WinRateRow(reason, wl.first, wl.second) }
        .sortedByDescending { it.total }
}

private fun computeDiscipline(pairs: List<HoldPair>, positionMap: Map<String, WatchItem>): List<DisciplineRow> =
    pairs.mapNotNull { pair ->
        val bp = pair.buyPrice ?: return@mapNotNull null
        val sp = pair.sellPrice ?: return@mapNotNull null
        val item = positionMap[pair.code]
        val stop   = item?.stopPrice?.let { it.toLong() }
        val target = item?.targetPrice?.let { it.toLong() }
        if (stop == null && target == null) return@mapNotNull null
        DisciplineRow(pair.id, pair.code, pair.buyAt, pair.sellAt, bp, sp, stop, target)
    }

private fun computeReasons(entries: List<ActionLogEntry>): List<Pair<String, Int>> {
    val counts = mutableMapOf<String, Int>()
    for (e in entries) {
        val r = e.reason?.takeIf { it.isNotBlank() } ?: continue
        counts[r] = (counts[r] ?: 0) + 1
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }
}

private data class CodeRow(val code: String, val buys: Int, val sells: Int, val interests: Int)

private fun computeCodeRows(entries: List<ActionLogEntry>): List<CodeRow> {
    data class Counts(var buys: Int = 0, var sells: Int = 0, var interests: Int = 0)
    val map = mutableMapOf<String, Counts>()
    for (e in entries) {
        val c = map.getOrPut(e.code) { Counts() }
        when (e.action) { "buy" -> c.buys++; "sell" -> c.sells++; else -> c.interests++ }
    }
    return map.entries
        .sortedByDescending { it.value.buys + it.value.sells + it.value.interests }
        .map { CodeRow(it.key, it.value.buys, it.value.sells, it.value.interests) }
}

// ── 놓친 종목 로드 ────────────────────────────────────────────────────────────

private suspend fun loadMissed(
    entries: List<ActionLogEntry>,
    nameMap: Map<String, String>,
    api: EdgeApi,
): List<MissedRow> {
    val interestCodes = entries.filter { it.action == "interest" }.map { it.code }.toSet()
    val buyCodes      = entries.filter { it.action == "buy" }.map { it.code }.toSet()
    val missedCodes   = interestCodes - buyCodes
    if (missedCodes.isEmpty()) return emptyList()

    val rows = missedCodes.mapNotNull { code ->
        val latest = entries.filter { it.code == code && it.action == "interest" }
            .maxByOrNull { it.createdAt } ?: return@mapNotNull null
        MissedRow(code = code, name = nameMap[code] ?: code,
            lastInterestAt = latest.createdAt, loggedPrice = latest.price)
    }.toMutableList()

    // 현재가 일괄 조회
    val quotes = runCatching { api.getQuotes(rows.map { it.code }) }.getOrDefault(emptyList())
    val qmap = quotes.associateBy { it.code }
    for (i in rows.indices) rows[i].currentPrice = qmap[rows[i].code]?.price

    // 구버전(loggedPrice == null): 일봉 소급으로 추정
    rows.filter { it.loggedPrice == null }.forEach { row ->
        val bars = runCatching { api.getDaily(code = row.code, bars = 120) }.getOrNull() ?: return@forEach
        val targetDate = epochToYYYYMMDD(row.lastInterestAt)
        val match = bars.firstOrNull { it.date <= targetDate }
        val idx = rows.indexOfFirst { it.code == row.code }
        if (idx >= 0) rows[idx].lookbackPrice = match?.close
    }

    return rows.sortedByDescending { it.lastInterestAt }
}

// ── 포맷 헬퍼 ────────────────────────────────────────────────────────────────

private fun epochToYYYYMMDD(millis: Long): String {
    val sdf = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
    return sdf.format(Date(millis))
}

private fun actionLabel(action: String) = when (action) { "buy" -> "매수"; "sell" -> "매도"; else -> "관심" }
private fun actionColor(action: String) = when (action) { "buy" -> ChangeUp; "sell" -> ChangeDown; else -> OrangeAccent }

private fun holdLabel(days: Double) = when {
    days < 1   -> "당일"
    days < 30  -> "${days.toInt()}일"
    days < 365 -> "${(days / 30).toInt()}개월"
    else       -> "${String.format("%.1f", days / 365)}년"
}

private fun shortDate(millis: Long): String {
    val sdf = SimpleDateFormat("MM/dd", Locale.KOREA)
    return sdf.format(Date(millis))
}

private fun shortTs(millis: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
    return sdf.format(Date(millis))
}
