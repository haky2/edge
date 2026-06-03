package com.haky.edge.api

import com.haky.edge.model.Quote
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Edge 백엔드 호출용 클라이언트. 앱(iOS/Android)은 외부 API를 직접 부르지 않고 항상 이 백엔드만 호출한다.
 *
 * baseUrl 기본값 주의:
 *  - iOS 시뮬레이터: 맥과 네트워크를 공유해 `localhost` 가 그대로 맥의 백엔드를 가리킨다.
 *  - Android 에뮬레이터: `localhost` 가 에뮬 자신을 의미하므로 `http://10.0.2.2:8080` 를 써야 한다.
 *  - 실기기/배포: 나중에 Cloud Run URL로 교체.
 *
 * suspend 함수는 Kotlin/Native가 Swift에 `async throws` 로 노출 → Swift에서 `try await api.getQuote(...)`.
 */
class EdgeApi(
    private val baseUrl: String = "http://localhost:8080",
) {
    private val client = HttpClient {
        // 엔진은 지정하지 않는다 — 플랫폼 classpath에 있는 엔진(iOS=Darwin, Android=OkHttp)을 자동 사용.
        install(ContentNegotiation) {
            // 백엔드가 우리가 안 보는 필드를 추가해도 깨지지 않게.
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** 6자리 종목코드의 현재 시세를 백엔드에서 가져온다. */
    suspend fun getQuote(code: String): Quote =
        client.get("$baseUrl/quote/$code").body()
}
