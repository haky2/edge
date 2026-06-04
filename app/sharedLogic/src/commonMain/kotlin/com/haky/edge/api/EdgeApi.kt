package com.haky.edge.api

import com.haky.edge.model.Quote
import com.haky.edge.model.StockInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
 *
 * @Throws 필수: 이게 없으면 네트워크 예외(백엔드 다운 등 DarwinHttpRequestException)가 Swift catch로
 * 전달되지 않고 "Program will be terminated" 로 **앱이 크래시**한다. @Throws 가 있어야 NSError 로 넘어가
 * Swift `do/catch` 에서 잡혀 "불러오기 실패" 메시지로 처리된다.
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
    @Throws(Exception::class)
    suspend fun getQuote(code: String): Quote =
        client.get("$baseUrl/quote/$code").body()

    /**
     * 여러 종목 시세를 한 번에 가져온다(관심종목 리스트용). → GET /quotes?codes=a,b,c
     * 백엔드가 병렬 조회하며, 일부 실패분은 응답에서 빠질 수 있어 반환 개수가 요청보다 적을 수 있다.
     */
    @Throws(Exception::class)
    suspend fun getQuotes(codes: List<String>): List<Quote> =
        client.get("$baseUrl/quotes") {
            parameter("codes", codes.joinToString(","))
        }.body()

    /**
     * 종목 검색. 숫자면 코드 prefix, 아니면 이름 부분일치(백엔드가 자동 판별).
     * 빈 질의는 백엔드가 빈 배열을 반환(에러 아님).
     */
    @Throws(Exception::class)
    suspend fun search(query: String): List<StockInfo> =
        client.get("$baseUrl/search") {
            parameter("q", query)
        }.body()
}
