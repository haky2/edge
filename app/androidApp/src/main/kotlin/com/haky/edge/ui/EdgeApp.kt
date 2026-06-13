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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.model.Quote
import com.haky.edge.model.WatchItem

sealed class AppDestination {
    object Watchlist : AppDestination()
    data class StockDetail(val item: WatchItem, val quote: Quote?) : AppDestination()
    object Search : AppDestination()
    object Portfolio : AppDestination()
    object Briefing : AppDestination()
    object Stats : AppDestination()
    object Settings : AppDestination()
    data class Comparison(val itemA: WatchItem, val itemB: WatchItem) : AppDestination()
}

enum class AppTab(val label: String, val icon: ImageVector) {
    Watchlist("관심종목", Icons.Filled.Star),
    Portfolio("내 자산", Icons.Filled.Home),
    Briefing("브리핑", Icons.Filled.Menu),
    Stats("내 패턴", Icons.Filled.Person),
    Settings("설정", Icons.Filled.Settings),
}

@Composable
fun EdgeApp(
    watchlistRepo: WatchlistRepository,
    actionLogRepo: ActionLogRepository,
    api: EdgeApi,
) {
    var destination by remember { mutableStateOf<AppDestination>(AppDestination.Watchlist) }
    var activeTab by remember { mutableStateOf(AppTab.Watchlist) }

    // 상세/검색/비교 화면에서 뒤로가기 → 이전 탭으로
    val isDetailScreen = destination is AppDestination.StockDetail
            || destination is AppDestination.Search
            || destination is AppDestination.Comparison
    BackHandler(enabled = isDetailScreen) {
        destination = when (destination) {
            is AppDestination.Comparison -> AppDestination.StockDetail(
                (destination as AppDestination.Comparison).itemA, null
            )
            else -> when (activeTab) {
                AppTab.Watchlist -> AppDestination.Watchlist
                AppTab.Portfolio -> AppDestination.Portfolio
                AppTab.Briefing -> AppDestination.Briefing
                AppTab.Stats -> AppDestination.Stats
                AppTab.Settings -> AppDestination.Settings
            }
        }
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
                                destination = when (tab) {
                                    AppTab.Watchlist -> AppDestination.Watchlist
                                    AppTab.Portfolio -> AppDestination.Portfolio
                                    AppTab.Briefing -> AppDestination.Briefing
                                    AppTab.Stats -> AppDestination.Stats
                                    AppTab.Settings -> AppDestination.Settings
                                }
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
                when (dest) {
                    is AppDestination.Watchlist -> WatchlistScreen(
                        watchlistRepo = watchlistRepo,
                        api = api,
                        onStockClick = { item, quote ->
                            destination = AppDestination.StockDetail(item, quote)
                        },
                        onAddClick = { destination = AppDestination.Search },
                    )
                    is AppDestination.StockDetail -> StockDetailScreen(
                        item = dest.item,
                        initialQuote = dest.quote,
                        watchlistRepo = watchlistRepo,
                        actionLogRepo = actionLogRepo,
                        api = api,
                        onBack = { destination = AppDestination.Watchlist },
                        onCompare = { itemB ->
                            destination = AppDestination.Comparison(dest.item, itemB)
                        },
                    )
                    is AppDestination.Search -> SearchScreen(
                        api = api,
                        watchlistRepo = watchlistRepo,
                        onDismiss = { destination = AppDestination.Watchlist },
                    )
                    is AppDestination.Portfolio -> PortfolioScreen(
                        watchlistRepo = watchlistRepo,
                        api = api,
                    )
                    is AppDestination.Briefing -> BriefingScreen(api = api, watchlistRepo = watchlistRepo)
                    is AppDestination.Stats -> StatsScreen(watchlistRepo = watchlistRepo, actionLogRepo = actionLogRepo, api = api)
                    is AppDestination.Settings -> SettingsScreen()
                    is AppDestination.Comparison -> ComparisonScreen(
                        itemA = dest.itemA,
                        itemB = dest.itemB,
                        api = api,
                        onBack = { destination = AppDestination.StockDetail(dest.itemA, null) },
                    )
                }
            }
        }
    }
}
