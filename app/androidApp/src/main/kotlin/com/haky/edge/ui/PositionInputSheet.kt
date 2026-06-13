package com.haky.edge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.WatchItem
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionInputSheet(
    item: WatchItem,
    watchlistRepo: WatchlistRepository,
    onDismiss: () -> Unit,
    onSave: (WatchItem) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    var avgTfv by remember { mutableStateOf(item.avgPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue("")) }
    var qtyText by remember { mutableStateOf(item.qty?.toString() ?: "") }
    var targetTfv by remember { mutableStateOf(item.targetPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue("")) }
    var stopTfv by remember { mutableStateOf(item.stopPrice?.toLong()?.let(::priceToTfv) ?: TextFieldValue("")) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("포지션 입력", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))

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

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("취소")
                }
                Button(
                    onClick = {
                        watchlistRepo.updatePosition(
                            code = item.code,
                            avgPrice = avgTfv.text.filter { it.isDigit() }.toDoubleOrNull(),
                            qty = qtyText.toLongOrNull(),
                            targetPrice = targetTfv.text.filter { it.isDigit() }.toDoubleOrNull(),
                            stopPrice = stopTfv.text.filter { it.isDigit() }.toDoubleOrNull(),
                        )
                        val updated = watchlistRepo.all().firstOrNull { it.code == item.code }
                        if (updated != null) onSave(updated) else onDismiss()
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
