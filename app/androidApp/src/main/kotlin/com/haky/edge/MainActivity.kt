package com.haky.edge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.DriverFactory
import com.haky.edge.db.WatchlistRepository
import com.haky.edge.ui.AppPrefs
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
        // Debug(개발 빌드)는 항상 로컬 백엔드(에뮬레이터 10.0.2.2=호스트 맥), Release는 운영(Cloud Run) URL.
        // → 개발 중엔 재배포 없이 `cd backend && ./run.sh` 만 띄우면 에뮬이 그 로컬 서버를 본다.
        val baseUrl = if (BuildConfig.DEBUG) "http://10.0.2.2:8080"
                      else BuildConfig.EDGE_BASE_URL.ifEmpty { "http://10.0.2.2:8080" }
        val api = EdgeApi(baseUrl = baseUrl, apiToken = BuildConfig.EDGE_API_TOKEN)

        setContent {
            var themeMode by remember { mutableStateOf(AppPrefs.getTheme(this@MainActivity)) }
            val isDark = when (themeMode) {
                "light" -> false
                "dark"  -> true
                else    -> isSystemInDarkTheme()
            }
            EdgeTheme(darkTheme = isDark) {
                EdgeApp(
                    watchlistRepo = watchlistRepo,
                    actionLogRepo = actionLogRepo,
                    api = api,
                    onThemeChange = { themeMode = it },
                )
            }
        }
    }
}
