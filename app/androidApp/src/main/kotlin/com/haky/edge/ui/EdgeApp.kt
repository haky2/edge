package com.haky.edge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.AccountRepository
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.HoldingRepository
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.OverseasQuote
import com.haky.edge.model.Quote
import com.haky.edge.model.WatchItem

sealed class AppDestination {
    object Watchlist : AppDestination()
    data class StockDetail(val item: WatchItem, val quote: Quote?, val initialAccountId: Long? = null) : AppDestination()
    data class OverseasDetail(val item: WatchItem, val overseasQuote: OverseasQuote?) : AppDestination()
    object Search : AppDestination()
    object Portfolio : AppDestination()
    object Briefing : AppDestination()
    object Stats : AppDestination()
    object Settings : AppDestination()
}

enum class AppTab(val label: String, val icon: ImageVector) {
    Watchlist("관심종목", Icons.Filled.Star),
    Portfolio("내 자산", Icons.Filled.AccountBalanceWallet),
    Briefing("브리핑", Icons.Filled.Article),
    Stats("내 패턴", Icons.Filled.Insights),
    Settings("설정", Icons.Filled.Settings),
}

private fun tabDestination(tab: AppTab): AppDestination = when (tab) {
    AppTab.Watchlist -> AppDestination.Watchlist
    AppTab.Portfolio -> AppDestination.Portfolio
    AppTab.Briefing -> AppDestination.Briefing
    AppTab.Stats -> AppDestination.Stats
    AppTab.Settings -> AppDestination.Settings
}

@Composable
fun EdgeApp(
    watchlistRepo: WatchlistRepository,
    actionLogRepo: ActionLogRepository,
    holdingRepo: HoldingRepository,
    accountRepo: AccountRepository,
    api: EdgeApi,
    onThemeChange: (String) -> Unit = {},
) {
    var destination by remember { mutableStateOf<AppDestination>(AppDestination.Watchlist) }
    var activeTab by remember { mutableStateOf(AppTab.Watchlist) }
    // 브리핑 하위탭(내 종목/시장) 선택을 EdgeApp 레벨에 보관 → 다른 탭 갔다 와도 유지.
    var briefingTab by remember { mutableStateOf(BriefTab.MyStocks) }

    // 상세/검색/비교 화면에서 뒤로가기 → 이전 탭으로
    val isDetailScreen = destination is AppDestination.StockDetail
            || destination is AppDestination.OverseasDetail
            || destination is AppDestination.Search
    BackHandler(enabled = isDetailScreen) {
        destination = tabDestination(activeTab)
    }

    Scaffold(
        bottomBar = {
            if (!isDetailScreen) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = activeTab == tab,
                            onClick = {
                                activeTab = tab
                                destination = tabDestination(tab)
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { dest ->
                // M1: 화면 진입 계측. dest 인스턴스가 바뀔 때마다(매 진입) 1회 view.
                LaunchedEffect(dest) {
                    when (dest) {
                        is AppDestination.StockDetail -> Usage.view("detail")
                        is AppDestination.Briefing -> Usage.view("briefing")
                        is AppDestination.Stats -> Usage.view("stats")
                        is AppDestination.Portfolio -> Usage.view("portfolio")
                        else -> {}
                    }
                }
                when (dest) {
                    is AppDestination.Watchlist -> WatchlistScreen(
                        watchlistRepo = watchlistRepo,
                        api = api,
                        onStockClick = { item, quote ->
                            destination = AppDestination.StockDetail(item, quote)
                        },
                        onOverseasStockClick = { item, ovsQuote ->
                            destination = AppDestination.OverseasDetail(item, ovsQuote)
                        },
                        onAddClick = { destination = AppDestination.Search },
                    )
                    is AppDestination.OverseasDetail -> OverseasDetailScreen(
                        item = dest.item,
                        initialQuote = dest.overseasQuote,
                        api = api,
                        onBack = { destination = tabDestination(activeTab) },
                    )
                    is AppDestination.StockDetail -> StockDetailScreen(
                        item = dest.item,
                        initialQuote = dest.quote,
                        initialAccountId = dest.initialAccountId,
                        watchlistRepo = watchlistRepo,
                        holdingRepo = holdingRepo,
                        accountRepo = accountRepo,
                        actionLogRepo = actionLogRepo,
                        api = api,
                        onBack = { destination = tabDestination(activeTab) },
                    )
                    is AppDestination.Search -> SearchScreen(
                        api = api,
                        watchlistRepo = watchlistRepo,
                        onDismiss = { destination = AppDestination.Watchlist },
                    )
                    is AppDestination.Portfolio -> PortfolioScreen(
                        holdingRepo = holdingRepo,
                        accountRepo = accountRepo,
                        watchlistRepo = watchlistRepo,
                        api = api,
                        onStockClick = { item, quote, accountId ->
                            destination = AppDestination.StockDetail(item, quote, initialAccountId = accountId)
                        },
                    )
                    is AppDestination.Briefing -> BriefingScreen(
                        api = api,
                        watchlistRepo = watchlistRepo,
                        selectedTab = briefingTab,
                        onSelectTab = { briefingTab = it },
                        onStockClick = { item, quote ->
                            destination = AppDestination.StockDetail(item, quote)
                        },
                    )
                    is AppDestination.Stats -> StatsScreen(watchlistRepo = watchlistRepo, holdingRepo = holdingRepo, actionLogRepo = actionLogRepo, api = api)
                    is AppDestination.Settings -> SettingsScreen(onThemeChange = onThemeChange)
                }
            }
        }
    }
}
