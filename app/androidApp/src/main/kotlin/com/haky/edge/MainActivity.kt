package com.haky.edge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.DriverFactory
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.ui.EdgeApp
import com.haky.edge.ui.theme.EdgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val driverFactory = DriverFactory(this)
        // 첫 실행은 빈 관심종목으로 시작한다(시드 없음). 사용자가 검색으로 직접 추가.
        val watchlistRepo = WatchlistRepository(driverFactory)
        val actionLogRepo = ActionLogRepository(driverFactory)
        val baseUrl = BuildConfig.EDGE_BASE_URL.ifEmpty { "http://10.0.2.2:8080" }
        val api = EdgeApi(baseUrl = baseUrl, apiToken = BuildConfig.EDGE_API_TOKEN)

        setContent {
            EdgeTheme {
                EdgeApp(watchlistRepo = watchlistRepo, actionLogRepo = actionLogRepo, api = api)
            }
        }
    }
}
