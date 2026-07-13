package com.haky.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.haky.edge.db.AccountRepository
import com.haky.edge.db.HoldingRepository
import com.haky.edge.model.AccountInfo

private val ACCOUNT_PRESETS = listOf("ISA", "IRP개인연금", "퇴직연금", "일반")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementSheet(
    accountRepo: AccountRepository,
    holdingRepo: HoldingRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var accounts by remember { mutableStateOf(listOf<AccountInfo>()) }
    var showAddSection by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var migrationDialog by remember { mutableStateOf<MigrationDialogState?>(null) }

    fun reload() { accounts = accountRepo.all() }

    LaunchedEffect(Unit) { reload() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("계좌 관리", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showAddSection = !showAddSection }) {
                    Icon(Icons.Default.Add, contentDescription = "계좌 추가")
                }
            }

            if (showAddSection) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // 프리셋 칩
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ACCOUNT_PRESETS.forEach { preset ->
                            SuggestionChip(
                                onClick = {
                                    // 프리셋만 장기 기본값 매핑(sharedLogic 공유 규칙) — 커스텀 이름은 추론 안 함
                                    addAccount(preset, AccountRepository.presetHorizon(preset), accountRepo, holdingRepo, ::reload) { state -> migrationDialog = state; showAddSection = false }
                                },
                                label = {
                                    val isLong = AccountRepository.presetHorizon(preset) == AccountRepository.HORIZON_LONG
                                    Text(if (isLong) "$preset·장기" else preset, style = MaterialTheme.typography.bodySmall)
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            label = { Text("직접 입력") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (customName.isNotBlank()) {
                                    addAccount(customName.trim(), AccountRepository.HORIZON_FREE, accountRepo, holdingRepo, ::reload) { state -> migrationDialog = state; showAddSection = false }
                                    customName = ""
                                }
                            },
                            enabled = customName.isNotBlank(),
                        ) { Text("추가") }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            Text(
                "기본 계좌는 삭제할 수 없으며, 삭제 시 보유 종목은 기본 계좌로 이전됩니다.\n" +
                    "장기 계좌는 AI 코멘트가 단기 매매 대신 장기 관점 중심으로 해석합니다. 배지를 탭해 변경할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn {
                items(accounts, key = { it.id }) { account ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(account.name, modifier = Modifier.weight(1f))
                        // 장기/자유 스위치 — 슬라이스 C에서 AI 코멘트가 이 속성을 읽는다
                        val isLong = account.horizon == AccountRepository.HORIZON_LONG
                        FilterChip(
                            selected = isLong,
                            onClick = {
                                accountRepo.updateHorizon(
                                    account.id,
                                    if (isLong) AccountRepository.HORIZON_FREE else AccountRepository.HORIZON_LONG,
                                )
                                reload()
                            },
                            label = { Text(if (isLong) "장기" else "자유", style = MaterialTheme.typography.labelSmall) },
                        )
                        if (account.isDefault == 1L) {
                            FilterChip(
                                selected = false, onClick = {},
                                label = { Text("기본", style = MaterialTheme.typography.labelSmall) },
                            )
                        } else {
                            IconButton(onClick = {
                                accountRepo.deleteById(account.id)
                                reload()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    migrationDialog?.let { state ->
        AlertDialog(
            onDismissRequest = { migrationDialog = null },
            title = { Text("보유 종목 이전") },
            text = { Text("기본 계좌의 보유 종목 ${state.count}개를 '${state.accountName}'으로 이동하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    holdingRepo.moveToAccount(fromAccountId = holdingRepo.defaultAccountId(), toAccountId = state.accountId)
                    migrationDialog = null
                }) { Text("이동") }
            },
            dismissButton = {
                TextButton(onClick = { migrationDialog = null }) { Text("유지") }
            },
        )
    }
}

private data class MigrationDialogState(val accountId: Long, val accountName: String, val count: Long)

private fun addAccount(
    name: String,
    horizon: String,
    accountRepo: AccountRepository,
    holdingRepo: HoldingRepository,
    reload: () -> Unit,
    showMigration: (MigrationDialogState) -> Unit,
) {
    val wasFirstCustom = accountRepo.countCustom() == 0L
    val newAccount = accountRepo.insertAndGet(name, horizon)
    reload()
    if (wasFirstCustom) {
        val count = accountRepo.countInDefault()
        if (count > 0L) {
            showMigration(MigrationDialogState(newAccount.id, newAccount.name, count))
        }
    }
}
