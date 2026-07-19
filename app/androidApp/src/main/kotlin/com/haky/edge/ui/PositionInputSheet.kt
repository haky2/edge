package com.haky.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.AccountRepository
import com.haky.edge.db.HoldingRepository
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.AccountInfo
import com.haky.edge.model.PositionSizing
import com.haky.edge.model.PositionSizingEntry
import com.haky.edge.model.WatchItem
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionInputSheet(
    item: WatchItem,
    holdingRepo: HoldingRepository,
    accountRepo: AccountRepository,
    watchlistRepo: WatchlistRepository,
    api: EdgeApi,
    onDismiss: () -> Unit,
    onSave: (WatchItem) -> Unit,
    initialAccountId: Long? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 계좌 목록 로드 (1개=기본만이면 피커 숨김)
    var accounts by remember { mutableStateOf(listOf<AccountInfo>()) }
    var selectedAccount by remember { mutableStateOf<AccountInfo?>(null) }
    var accountDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        accounts = accountRepo.all()
        selectedAccount = if (initialAccountId != null)
            accounts.firstOrNull { it.id == initialAccountId } ?: accounts.firstOrNull { it.isDefault == 1L }
        else
            accounts.firstOrNull { it.isDefault == 1L }
    }

    val hasCustomAccounts = accounts.size > 1

    // 기존 holding 로드 (선택 계좌 기준)
    val existing = remember(selectedAccount?.id) {
        selectedAccount?.let { acc ->
            if (acc.isDefault == 1L) holdingRepo.getDefaultHolding(item.code)
            else holdingRepo.getHolding(item.code, acc.id)
        }
    }

    var avgTfv by remember(existing) {
        mutableStateOf(existing?.avgPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue(""))
    }
    var qtyText by remember(existing) {
        mutableStateOf(existing?.qty?.toString() ?: "")
    }
    var targetTfv by remember(existing) {
        mutableStateOf(existing?.targetPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue(""))
    }
    var stopTfv by remember(existing) {
        mutableStateOf(existing?.stopPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue(""))
    }
    var thesisText by remember { mutableStateOf(item.thesis ?: "") }
    val thesisMaxChars = 200

    // N1: 리스크 기준 수량 (보유 포지션 있을 때만). 다계좌 같은 종목은 수량 합산.
    val sizingEntries = remember {
        holdingRepo.all()
            .filter { it.avgPrice != null && it.qty != null }
            .groupBy { it.code }
            .map { (code, hs) -> PositionSizingEntry(code, hs.sumOf { it.qty!! }) }
    }
    val hasExistingHoldings = sizingEntries.isNotEmpty()
    val scope = rememberCoroutineScope()
    var showSizing by remember { mutableStateOf(false) }
    var capPct by remember { mutableStateOf(15.0) }
    var sizing by remember { mutableStateOf<PositionSizing?>(null) }
    var sizingLoading by remember { mutableStateOf(false) }

    fun loadSizing() {
        scope.launch {
            sizingLoading = true
            sizing = runCatching {
                api.postPositionSizing(sizingEntries, item.code, capPct)
            }.getOrNull()
            sizingLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("포지션 입력", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))

            // 계좌 피커 (계좌가 2개 이상일 때만)
            if (hasCustomAccounts && accounts.isNotEmpty()) {
                SectionLabel("계좌")
                ExposedDropdownMenuBox(
                    expanded = accountDropdownExpanded,
                    onExpandedChange = { accountDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedAccount?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("계좌 선택") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = accountDropdownExpanded,
                        onDismissRequest = { accountDropdownExpanded = false },
                    ) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    selectedAccount = account
                                    accountDropdownExpanded = false
                                    // 계좌 전환 시 필드 재로드
                                    val h = if (account.isDefault == 1L) holdingRepo.getDefaultHolding(item.code)
                                             else holdingRepo.getHolding(item.code, account.id)
                                    avgTfv    = h?.avgPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue("")
                                    qtyText   = h?.qty?.toString() ?: ""
                                    targetTfv = h?.targetPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue("")
                                    stopTfv   = h?.stopPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue("")
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            SectionLabel("내 포지션")
            PriceField("평단가", avgTfv) { new ->
                val digits = new.text.filter { it.isDigit() }
                avgTfv = digitsToTfv(digits)
            }
            Spacer(modifier = Modifier.height(8.dp))
            QtyField("수량", qtyText) { new ->
                qtyText = new.filter { it.isDigit() }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            SectionLabel("목표 / 손절")
            PriceField("목표가", targetTfv) { new ->
                val digits = new.text.filter { it.isDigit() }
                targetTfv = digitsToTfv(digits)
            }
            Spacer(modifier = Modifier.height(8.dp))
            PriceField("손절가", stopTfv) { new ->
                val digits = new.text.filter { it.isDigit() }
                stopTfv = digitsToTfv(digits)
            }

            if (hasExistingHoldings) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                SizingSection(
                    show = showSizing,
                    onToggle = {
                        showSizing = !showSizing
                        if (showSizing && sizing == null) loadSizing()
                    },
                    capPct = capPct,
                    onCapChange = { capPct = it; if (showSizing) loadSizing() },
                    loading = sizingLoading,
                    sizing = sizing,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            SectionLabel("투자 논지")
            OutlinedTextField(
                value = thesisText,
                onValueChange = { if (it.length <= thesisMaxChars) thesisText = it },
                placeholder = { Text("왜 이 종목을 들고 있나(관심 갖나)?") },
                minLines = 3,
                maxLines = 5,
                supportingText = {
                    Text(
                        "${thesisText.length}/$thesisMaxChars",
                        color = if (thesisText.length >= thesisMaxChars)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("취소")
                }
                Button(
                    onClick = {
                        val avgPrice    = avgTfv.text.filter { it.isDigit() }.toDoubleOrNull()
                        val qty         = qtyText.toLongOrNull()
                        val targetPrice = targetTfv.text.filter { it.isDigit() }.toDoubleOrNull()
                        val stopPrice   = stopTfv.text.filter { it.isDigit() }.toDoubleOrNull()
                        val accountId   = selectedAccount?.id ?: holdingRepo.defaultAccountId()
                        holdingRepo.savePositionForAccount(
                            code        = item.code,
                            name        = item.name,
                            accountId   = accountId,
                            avgPrice    = avgPrice,
                            qty         = qty,
                            targetPrice = targetPrice,
                            stopPrice   = stopPrice,
                        )
                        val thesisSaved = thesisText.trim().ifBlank { null }
                        watchlistRepo.updateThesis(item.code, item.name, thesisSaved)
                        val updated = WatchItem(code = item.code, name = item.name,
                                                avgPrice = avgPrice, qty = qty,
                                                targetPrice = targetPrice, stopPrice = stopPrice,
                                                thesis = thesisSaved)
                        onSave(updated)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("저장")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun PriceField(label: String, value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text("원") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun QtyField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text("주") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SizingSection(
    show: Boolean,
    onToggle: () -> Unit,
    capPct: Double,
    onCapChange: (Double) -> Unit,
    loading: Boolean,
    sizing: PositionSizing?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "리스크 기준 수량 보기",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggle) { Text(if (show) "접기" else "펼치기") }
    }
    if (show) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10.0, 15.0, 20.0, 25.0).forEach { c ->
                FilterChip(
                    selected = capPct == c,
                    onClick = { onCapChange(c) },
                    label = { Text("${c.toInt()}%") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(modifier = Modifier.height(24.dp).wrapContentWidth()) }
            sizing != null -> SizingResult(sizing)
            else -> Text(
                "리스크 상한을 골라 최대 수량을 계산합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "이 종목이 포트폴리오 리스크의 상한 %만 차지하도록 하는 최대 수량입니다. 매수 추천이 아니라 리스크 상한 역산입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SizingResult(s: PositionSizing) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "최대 ${String.format(Locale.US, "%,d", s.maxShares)}주",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            Text(
                "≈ ${String.format(Locale.US, "%,d", s.maxAmount)}원",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SizingChip("비중 ${String.format(Locale.US, "%.1f", s.targetWeightPct)}%")
            SizingChip("리스크 기여 ${String.format(Locale.US, "%.0f", s.atRiskContributionPct)}%")
            SizingChip("변동성 ${String.format(Locale.US, "%.0f", s.sigmaPct)}%")
        }
        if (s.approxByPeer) {
            Text(
                "⚠ 관측 부족 — 섹터 유사 종목 변동성으로 근사",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            s.caveat,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SizingChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun priceToTfv(n: Long): TextFieldValue {
    val fmt = formatDigits(n.toString())
    return TextFieldValue(fmt, TextRange(fmt.length))
}

private fun digitsToTfv(digits: String): TextFieldValue {
    val fmt = formatDigits(digits)
    return TextFieldValue(fmt, TextRange(fmt.length))
}

private fun formatDigits(digits: String): String {
    if (digits.isEmpty()) return ""
    return digits.toLongOrNull()?.let { String.format(Locale.US, "%,d", it) } ?: digits
}
