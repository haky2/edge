package com.haky.edge.api

import com.haky.edge.model.Analysis
import com.haky.edge.model.DailyBar
import com.haky.edge.model.DartDisclosure
import com.haky.edge.model.EarningsEntry
import com.haky.edge.model.InvestorFlow
import com.haky.edge.model.MacroImpact
import com.haky.edge.model.MacroIndicator
import com.haky.edge.model.NewsItem
import com.haky.edge.model.Quote
import com.haky.edge.model.SectorIndex
import com.haky.edge.model.StockInfo
import com.haky.edge.model.TargetPriceInfo
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

    /** 종목 일별 수급(외인/기관/개인 순매수) 최근 days일치. 최신일이 앞(장후 확정값만). */
    @Throws(Exception::class)
    suspend fun getInvestorFlow(code: String, days: Int = 5): List<InvestorFlow> =
        client.get("$baseUrl/investor/$code") {
            parameter("days", days)
        }.body()

    /**
     * 종목 관련 최신 뉴스 헤드라인. stockName 은 WatchItem.name 을 그대로 넘긴다.
     * 백엔드가 네이버 검색 API 로 최신순 N건을 가져온다(HTML 태그 제거 후).
     */
    @Throws(Exception::class)
    suspend fun getNews(stockName: String, display: Int = 5): List<NewsItem> =
        client.get("$baseUrl/news") {
            parameter("q", stockName)
            parameter("display", display)
        }.body()

    /**
     * 일봉 OHLCV (최신일이 앞). 이평선·RSI·거래량 추세 계산에 사용.
     * bars 기본값 62 = MA60 계산에 필요한 최소값(60) + 여유 2.
     */
    @Throws(Exception::class)
    suspend fun getDaily(code: String, bars: Int = 62): List<DailyBar> =
        client.get("$baseUrl/daily/$code") {
            parameter("bars", bars)
        }.body()

    /**
     * 종목 종합 코멘트(시세·52주·PER·수급·뉴스 → Claude 해석). 백엔드가 당일 캐시.
     * 포지션 없는 일반 버전 — 전 유저 공유 캐시.
     */
    @Throws(Exception::class)
    suspend fun getAnalysis(code: String): Analysis =
        client.get("$baseUrl/analysis/$code").body()

    /**
     * 매크로 지표(코스피·코스닥·원/달러·다우·나스닥·S&P500). 브리핑 "시장 지표" 섹션용.
     * 개별 지표 실패는 백엔드에서 제외돼 6개 미만이 올 수 있다.
     */
    @Throws(Exception::class)
    suspend fun getMacro(): List<MacroIndicator> =
        client.get("$baseUrl/macro").body()

    /**
     * 매크로 → 내 종목 영향 분석. 보유/관심 종목 코드를 넘기면 종목별 영향(계산) + Claude 종합 해석.
     * Claude 호출이라 첫 생성은 수 초 걸리고 백엔드가 당일 캐시한다. 둘 다 비어도 호출은 됨(빈 결과).
     */
    @Throws(Exception::class)
    suspend fun getMacroImpact(holdings: List<String>, watchlist: List<String>): MacroImpact =
        client.get("$baseUrl/macro-impact") {
            parameter("holdings", holdings.joinToString(","))
            parameter("watchlist", watchlist.joinToString(","))
        }.body()

    /**
     * KOSPI 주요 업종지수(전기전자·기계·운수장비·전기가스업·서비스업·철강금속). 브리핑 "섹터 동향" 섹션용.
     * 개별 업종 실패는 백엔드에서 제외돼 6개 미만이 올 수 있다.
     */
    @Throws(Exception::class)
    suspend fun getSectors(): List<SectorIndex> =
        client.get("$baseUrl/sectors").body()

    /**
     * 관심종목의 다음 정기공시 예정일 목록(분기/반기/사업보고서). daysUntil 오름차순.
     * DART pblntf_ty=A 기반. D-90 이내만 반환. 없으면 빈 리스트.
     */
    @Throws(Exception::class)
    suspend fun getEarnings(codes: List<String>): List<EarningsEntry> =
        client.get("$baseUrl/earnings") {
            parameter("codes", codes.joinToString(","))
        }.body()

    /**
     * 네이버 금융 컨센서스 목표주가. 애널리스트 미커버리지 종목은 null 반환(404).
     * 당일 백엔드 캐시 — 중복 스크래핑 없음.
     */
    @Throws(Exception::class)
    suspend fun getTargetPrice(code: String): TargetPriceInfo? {
        return try {
            client.get("$baseUrl/target-price/$code").body()
        } catch (_: Exception) { null }
    }

    /** 종목 최근 DART 공시 목록. days: 조회 기간(기본 7일). 없으면 빈 리스트. */
    @Throws(Exception::class)
    suspend fun getDartDisclosures(code: String, days: Int = 7): List<DartDisclosure> =
        client.get("$baseUrl/dart/$code") {
            parameter("days", days)
        }.body()

    /**
     * 포지션 기반 개인화 코멘트. 평단·수량·목표가·손절가를 Claude에 전달해 내 포지션 기준 해석 제공.
     * targetPrice·stopPrice 미입력 시 0.0 전달(백엔드가 0.0 = 미입력으로 처리).
     */
    @Throws(Exception::class)
    suspend fun getAnalysisPersonalized(
        code: String,
        avgPrice: Double,
        qty: Long,
        targetPrice: Double,
        stopPrice: Double,
    ): Analysis = client.get("$baseUrl/analysis/$code") {
        parameter("avgPrice", avgPrice)
        parameter("qty", qty)
        if (targetPrice > 0.0) parameter("targetPrice", targetPrice)
        if (stopPrice > 0.0) parameter("stopPrice", stopPrice)
    }.body()
}
