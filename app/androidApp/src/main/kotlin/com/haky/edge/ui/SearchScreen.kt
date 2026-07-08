package com.haky.edge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.OverseasStockInfo
import com.haky.edge.model.StockInfo
import com.haky.edge.ui.theme.EdgeTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    api: EdgeApi,
    watchlistRepo: WatchlistRepository,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StockInfo>>(emptyList()) }
    var overseasResults by remember { mutableStateOf<List<OverseasStockInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var inWatchlist by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        inWatchlist = watchlistRepo.all().map { it.code }.toSet()
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); overseasResults = emptyList(); return }
        scope.launch {
            loading = true
            error = null
            try {
                coroutineScope {
                    val domDeferred = async { api.search(query = q) }
                    val ovsDeferred = async { api.searchOverseas(query = q) }
                    results = domDeferred.await()
                    overseasResults = ovsDeferred.await()
                }
            } catch (e: Exception) {
                error = "검색 실패: ${e.message}"
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("종목 검색") },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    TextButton(onClick = onDismiss) { Text("완료") }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("종목명 또는 코드") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            )

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (results.isEmpty() && overseasResults.isEmpty() && query.isNotEmpty()) {
                Text(
                    "검색 결과가 없어요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn {
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            "국내",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(results, key = { it.code }) { stock ->
                        SearchResultRow(
                            stock = stock,
                            isAdded = inWatchlist.contains(stock.code),
                            onAdd = {
                                watchlistRepo.add(code = stock.code, name = stock.name)
                                inWatchlist = inWatchlist + stock.code
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
                if (overseasResults.isNotEmpty()) {
                    item {
                        Text(
                            "해외",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(overseasResults, key = { it.code }) { stock ->
                        OverseasSearchRow(
                            stock = stock,
                            isAdded = inWatchlist.contains(stock.code),
                            onAdd = {
                                val name = stock.nameEn.ifEmpty { stock.symb }
                                watchlistRepo.add(code = stock.code, name = name)
                                inWatchlist = inWatchlist + stock.code
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverseasSearchRow(
    stock: OverseasStockInfo,
    isAdded: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stock.nameEn.ifEmpty { stock.symb }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${stock.symb} · ${stock.market} · ${stock.currency}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isAdded) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "추가됨",
                tint = EdgeTheme.colors.success,
            )
        } else {
            TextButton(onClick = onAdd) { Text("추가") }
        }
    }
}

@Composable
private fun SearchResultRow(
    stock: StockInfo,
    isAdded: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stock.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${stock.code} · ${stock.market}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isAdded) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "추가됨",
                tint = EdgeTheme.colors.success,
            )
        } else {
            TextButton(onClick = onAdd) { Text("추가") }
        }
    }
}
