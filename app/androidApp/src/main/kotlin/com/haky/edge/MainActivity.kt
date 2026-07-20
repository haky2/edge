package com.haky.edge

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.AccountRepository
import com.haky.edge.db.ActionLogRepository
import com.haky.edge.db.DriverFactory
import com.haky.edge.db.HoldingRepository
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
        val holdingRepo   = HoldingRepository(driverFactory)
        val accountRepo   = AccountRepository(driverFactory)
        // 에뮬레이터(Debug)만 로컬 백엔드(10.0.2.2=호스트 맥) → `cd backend && ./run.sh` 로 백엔드 반복개발.
        // 실제 폰은 10.0.2.2 가 안 닿으므로 실기기·Release 는 운영(Cloud Run HTTPS) → 어느 폰·WiFi 에서도 동작.
        val isEmulator = Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.lowercase().contains("emulator")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
            || Build.MANUFACTURER.contains("Genymotion")
            || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            || Build.PRODUCT.contains("sdk")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
        val prodUrl = BuildConfig.EDGE_BASE_URL.ifEmpty { "http://10.0.2.2:8080" }
        val baseUrl = if (BuildConfig.DEBUG && isEmulator) "http://10.0.2.2:8080" else prodUrl
        val api = EdgeApi(baseUrl = baseUrl, apiToken = BuildConfig.EDGE_API_TOKEN)
        // M1: 카드 사용량 트래커 초기화(단일 사용자 전제). onResume마다 모아둔 배치를 flush.
        com.haky.edge.ui.Usage.configure(api, this)

        setContent {
            var themeMode by remember { mutableStateOf(AppPrefs.getTheme(this@MainActivity)) }
            val isDark = when (themeMode) {
                "light" -> false
                "dark"  -> true
                else    -> isSystemInDarkTheme()
            }
            EdgeTheme(darkTheme = isDark) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EdgeApp(
                        watchlistRepo = watchlistRepo,
                        actionLogRepo = actionLogRepo,
                        holdingRepo   = holdingRepo,
                        accountRepo   = accountRepo,
                        api = api,
                        onThemeChange = { themeMode = it },
                    )
                    // 로컬 백엔드(10.0.2.2)를 보고 있다는 표식 = 에뮬레이터 개발 빌드만.
                    // 실기기(Debug)·운영(Release)은 Cloud Run 을 보므로 배지 안 나온다.
                    if (BuildConfig.DEBUG && isEmulator) {
                        Text(
                            "LOCAL",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 2.dp, end = 8.dp)
                                .background(Color(0xFFFF9F0A), RoundedCornerShape(50))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.haky.edge.ui.Usage.flush()
    }
}
