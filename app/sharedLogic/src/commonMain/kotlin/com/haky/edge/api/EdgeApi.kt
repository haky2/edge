package com.haky.edge.api

import com.haky.edge.model.AnalogReport
import com.haky.edge.model.Analysis
import com.haky.edge.model.EarningsPreview
import com.haky.edge.model.Premortem
import com.haky.edge.model.PremortemRequest
import com.haky.edge.model.StanceStats
import com.haky.edge.model.AskAnswer
import com.haky.edge.model.AskRequest
import com.haky.edge.model.AskTurn
import com.haky.edge.model.CatalystBriefReport
import com.haky.edge.model.CatalystImpact
import com.haky.edge.model.CatalystReport
import com.haky.edge.model.Comparison
import com.haky.edge.model.Backtest
import com.haky.edge.model.FlowSensitivity
import com.haky.edge.model.DailyBar
import com.haky.edge.model.DartDisclosure
import com.haky.edge.model.EarningsEntry
import com.haky.edge.model.InvestorFlow
import com.haky.edge.model.MacroImpact
import com.haky.edge.model.MacroIndicator
import com.haky.edge.model.MarketCalendar
import com.haky.edge.model.MarketMood
import com.haky.edge.model.StockImpact
import com.haky.edge.model.NewsItem
import com.haky.edge.model.PriceLimits
import com.haky.edge.model.Quote
import com.haky.edge.model.SectorBriefing
import com.haky.edge.model.SectorEntry
import com.haky.edge.model.SectorIndex
import com.haky.edge.model.ShortSellingSummary
import com.haky.edge.model.StockInfo
import com.haky.edge.model.StockWarning
import com.haky.edge.model.TargetPriceInfo
import com.haky.edge.model.MarketEvent
import com.haky.edge.model.MoodAccuracyReport
import com.haky.edge.model.PeerValuation
import com.haky.edge.model.DiscoveryReport
import com.haky.edge.model.OverseasQuote
import com.haky.edge.model.OverseasStockInfo
import com.haky.edge.model.PortfolioReview
import com.haky.edge.model.PortfolioReviewRequest
import com.haky.edge.model.RebalanceCheck
import com.haky.edge.model.ReviewPositionEntry
import com.haky.edge.model.SectorRotation
import com.haky.edge.model.DeepResearch
import com.haky.edge.model.TradeReview
import com.haky.edge.model.TradeReviewRequest
import com.haky.edge.model.ValuationBand
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
    // 배포 보안 게이트(1.0c-a): 백엔드 EDGE_API_TOKEN 과 같은 값. 비어 있으면 헤더를 안 붙인다(로컬 개발).
    private val apiToken: String = "",
) {
    private val client = HttpClient {
        // 엔진은 지정하지 않는다 — 플랫폼 classpath에 있는 엔진(iOS=Darwin, Android=OkHttp)을 자동 사용.
        install(ContentNegotiation) {
            // 백엔드가 우리가 안 보는 필드를 추가해도 깨지지 않게.
            json(Json { ignoreUnknownKeys = true })
        }
        // AI 분석(getAnalysis)은 Claude 생성에 10~50초 걸린다. Android OkHttp 기본 읽기 타임아웃(~10초)이면
        // 타임아웃 예외로 "불러오지 못했어요"가 떴다 → 넉넉히 120초로. (iOS Darwin도 동일 적용)
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
        // 토큰이 있으면 모든 요청에 공유 토큰 헤더를 자동 첨부(배포 백엔드 인증 통과용).
        if (apiToken.isNotEmpty()) {
            defaultRequest {
                header("X-Edge-Token", apiToken)
            }
        }
    }

    /** 6자리 종목코드의 현재 시세를 백엔드에서 가져온다. */
    @Throws(Exception::class)
    suspend fun getQuote(code: String): Quote =
        client.get("$baseUrl/quote/$code").body()

    /** 해외 종목 단건 시세(code = "US:NAS:AAPL"). 한투 기본 15분 지연. */
    @Throws(Exception::class)
    suspend fun getOverseasQuote(code: String): OverseasQuote =
        client.get("$baseUrl/overseas/quote") {
            parameter("code", code)
        }.body()

    /** 해외 종목 다건 시세(관심종목 리스트용). 형식 오류 코드는 백엔드에서 제외. */
    @Throws(Exception::class)
    suspend fun getOverseasQuotes(codes: List<String>): List<OverseasQuote> =
        client.get("$baseUrl/overseas/quotes") {
            parameter("codes", codes.joinToString(","))
        }.body()

    /** 해외 종목 검색. 대문자 → 심볼 prefix, 소문자·한글 → 이름 부분 일치. */
    @Throws(Exception::class)
    suspend fun searchOverseas(query: String): List<OverseasStockInfo> =
        client.get("$baseUrl/overseas/search") {
            parameter("q", query)
        }.body()

    /**
     * 해외 종목 간단 AI 코멘트(시세 15분 지연 + 뉴스만 근거 — 수급·공시·재무 없음).
     * 백엔드가 (code,날짜) 당일 전 유저 공유 캐시. 기존 Analysis 모델 재사용(요약 박스 계약 동일).
     */
    @Throws(Exception::class)
    suspend fun getOverseasAnalysis(code: String): Analysis =
        client.get("$baseUrl/overseas/analysis") {
            parameter("code", code)
        }.body()

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

    /**
     * 종목 투자유의(시장경보·단기과열·정리매매·VI). 토스 기반(한투 미제공). 발동 없으면 빈 리스트.
     * 백엔드가 키 미설정/오류 시에도 빈 배열로 응답하므로 상세 화면을 막지 않는다.
     */
    @Throws(Exception::class)
    suspend fun getWarnings(code: String): List<StockWarning> =
        client.get("$baseUrl/warnings/$code").body()

    /**
     * 종목 가격 제한폭(상·하한가). 토스 기반(한투 미제공). 제한폭 없는 시장(미국 등)이나 오류 시 null.
     */
    @Throws(Exception::class)
    suspend fun getPriceLimits(code: String): PriceLimits? = runCatching {
        client.get("$baseUrl/price-limits/$code").body<PriceLimits>()
    }.getOrNull()

    /** 종목 일별 수급(외인/기관/개인 순매수) 최근 days일치. 최신일이 앞(장후 확정값만). */
    @Throws(Exception::class)
    suspend fun getInvestorFlow(code: String, days: Int = 5): List<InvestorFlow> =
        client.get("$baseUrl/investor/$code") {
            parameter("days", days)
        }.body()

    /** 여러 종목 수급 일괄 조회(HTTP 1회). 반환: code → flows 맵. 실패 종목은 키 없음. */
    @Throws(Exception::class)
    suspend fun getInvestorBatch(codes: List<String>, days: Int = 3): Map<String, List<InvestorFlow>> =
        client.get("$baseUrl/investor/batch") {
            parameter("codes", codes.joinToString(","))
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
     * 종목 종합 코멘트(시세·52주·PER·수급·뉴스 → Claude 해석). 백엔드가 당일·모드별 캐시.
     * 포지션 없는 일반 버전 — 전 유저 공유 캐시. thesis 있으면 개인 캐시 키로 분리.
     * mode="defensive"(기본) | "aggressive"(개별 종목 매매 판단까지).
     * refresh=true: 캐시를 건너뛰고 즉시 재생성(수동 재생성 버튼 전용).
     */
    @Throws(Exception::class)
    suspend fun getAnalysis(code: String, mode: String = "defensive", refresh: Boolean = false, thesis: String? = null, thesisHistory: List<com.haky.edge.model.ThesisSnapshot> = emptyList()): Analysis =
        client.get("$baseUrl/analysis/$code") {
            if (mode != "defensive") parameter("mode", mode)
            if (refresh) parameter("refresh", "true")
            if (!thesis.isNullOrBlank()) parameter("thesis", thesis)
            // 변천 2건 미만이면 서버가 어차피 무시 — 전송 생략(URL 절약)
            if (thesisHistory.size >= 2) parameter("thesisHistory", encodeThesisHistory(thesisHistory))
        }.body()

    /**
     * 종목별 재료(DART 공시 + 뉴스) 구조화 판정. 각 재료를 호재/악재·강도·선반영까지 판정해 반환.
     * 백엔드가 (날짜·30분버킷) 캐시. refresh=true: 캐시 건너뛰고 즉시 재생성.
     */
    @Throws(Exception::class)
    suspend fun getCatalysts(code: String, days: Int = 7, refresh: Boolean = false): CatalystReport =
        client.get("$baseUrl/catalysts/$code") {
            if (days != 7) parameter("days", days)
            if (refresh) parameter("refresh", "true")
        }.body()

    /**
     * F2 수주 공시 임팩트 통계. 종목 수주·공급계약 공시의 1/5/20거래일 forward return.
     * 이벤트 없거나 오류 시 null(재료 카드 항목에서 통계 줄 숨김).
     */
    @Throws(Exception::class)
    suspend fun getCatalystImpact(code: String): CatalystImpact? = runCatching {
        client.get("$baseUrl/catalyst-impact/$code").body<CatalystImpact>()
    }.getOrNull()

    /**
     * 관심종목 재료 동향을 섹터별로 묶어 한 줄씩 반환. 캐시된 판정만 사용(Claude 미호출).
     * 브리핑 "테마별 재료 동향" 섹션용.
     */
    @Throws(Exception::class)
    suspend fun getCatalystBrief(codes: List<String>): CatalystBriefReport =
        client.get("$baseUrl/catalyst-brief") {
            parameter("codes", codes.joinToString(","))
        }.body()

    /**
     * 국내(KRX) 개장 캘린더 — 오늘 휴장 여부 + 직전/다음 거래일. 브리핑 휴장 배너용.
     * 토스 기반(한투 미제공). 키 미설정/오류 시 null(배너 숨김).
     */
    @Throws(Exception::class)
    suspend fun getMarketCalendar(): MarketCalendar? = runCatching {
        client.get("$baseUrl/market-calendar").body<MarketCalendar>()
    }.getOrNull()

    /**
     * 매크로 지표(코스피·코스닥·원/달러·다우·나스닥·S&P500). 브리핑 "시장 지표" 섹션용.
     * 개별 지표 실패는 백엔드에서 제외돼 6개 미만이 올 수 있다.
     */
    @Throws(Exception::class)
    suspend fun getMacro(): List<MacroIndicator> =
        client.get("$baseUrl/macro").body()

    /**
     * 오늘 시장 분위기(코스피 출발 방향) Claude 해석. 기존 매크로 지표 재사용.
     * mode="defensive"(사실+방향) | "aggressive"(시장 스탠스 의견까지). 모드별 당일 공유 캐시.
     * Claude 호출이라 첫 생성은 수 초 걸린다.
     * refresh=true: 캐시 bypass 재생성(수동 재생성 버튼 전용).
     */
    @Throws(Exception::class)
    suspend fun getMarketMood(mode: String = "defensive", refresh: Boolean = false): MarketMood =
        client.get("$baseUrl/market-mood") {
            parameter("mode", mode)
            if (refresh) parameter("refresh", "true")
        }.body()

    /**
     * 매크로 → 내 종목 영향 분석. 보유/관심 종목 코드를 넘기면 종목별 영향(계산) + Claude 종합 해석.
     * mode="defensive"|"aggressive". positions: code→(avgPrice, qty) 포지션 맵(공격 모드 포트폴리오 스탠스용).
     * Claude 호출이라 첫 생성은 수 초 걸리고 백엔드가 모드별 당일 캐시한다. 둘 다 비어도 호출은 됨(빈 결과).
     * refresh=true: 캐시 bypass 재생성(수동 재생성 버튼 전용).
     */
    @Throws(Exception::class)
    suspend fun getMacroImpact(
        holdings: List<String>,
        watchlist: List<String>,
        mode: String = "defensive",
        positions: Map<String, Pair<Double, Long>> = emptyMap(),
        refresh: Boolean = false,
    ): MacroImpact =
        client.get("$baseUrl/macro-impact") {
            parameter("holdings", holdings.joinToString(","))
            parameter("watchlist", watchlist.joinToString(","))
            parameter("mode", mode)
            if (positions.isNotEmpty()) {
                parameter("positions", positions.entries.joinToString(",") { (code, pos) ->
                    "$code:${pos.first.toLong()}:${pos.second}"
                })
            }
            if (refresh) parameter("refresh", "true")
        }.body()

    /**
     * KOSPI 주요 업종지수(전기전자·기계·운수장비·전기가스업·서비스업·철강금속). 브리핑 "섹터 동향" 섹션용.
     * 개별 업종 실패는 백엔드에서 제외돼 6개 미만이 올 수 있다.
     */
    @Throws(Exception::class)
    suspend fun getSectors(): List<SectorIndex> =
        client.get("$baseUrl/sectors").body()

    /**
     * 종목 코드 목록 → 대표 섹터 레이블 매핑. 포트폴리오 섹터 비중 계산용.
     * 백엔드가 7일 캐시하므로 첫 호출 외엔 즉시 반환.
     */
    @Throws(Exception::class)
    suspend fun getSectorClassify(codes: List<String>): List<SectorEntry> =
        client.get("$baseUrl/sector-classify") {
            parameter("codes", codes.joinToString(","))
        }.body()

    /**
     * 오늘 섹터 트렌드 분석 + 관심종목 중 주목 종목. 브리핑 시장 탭 "섹터 분석" 섹션용.
     * Claude 호출이라 첫 생성은 수 초 걸리고 백엔드가 당일 캐시한다.
     * refresh=true: 캐시 bypass 재생성(수동 재생성 버튼 전용).
     */
    @Throws(Exception::class)
    suspend fun getSectorBriefing(codes: List<String>, refresh: Boolean = false): SectorBriefing =
        client.get("$baseUrl/sector-briefing") {
            parameter("codes", codes.joinToString(","))
            if (refresh) parameter("refresh", "true")
        }.body()

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

    /** 종목 1개의 매크로 지표 영향 신호. Claude 없이 섹터+방향 계산만. 상세화면 "지표 영향" 섹션용. */
    @Throws(Exception::class)
    suspend fun getStockSignals(code: String): StockImpact =
        client.get("$baseUrl/macro-signal/$code").body()

    /** 종목 최근 DART 공시 목록. days: 조회 기간(기본 7일). 없으면 빈 리스트. */
    @Throws(Exception::class)
    suspend fun getDartDisclosures(code: String, days: Int = 7): List<DartDisclosure> =
        client.get("$baseUrl/dart/$code") {
            parameter("days", days)
        }.body()

    /** 여러 종목 공시 일괄 조회(HTTP 1회). 반환: 전 종목 공시 통합 목록(최신순). */
    @Throws(Exception::class)
    suspend fun getDartBatch(codes: List<String>, days: Int = 7): List<DartDisclosure> =
        client.get("$baseUrl/dart/batch") {
            parameter("codes", codes.joinToString(","))
            parameter("days", days)
        }.body()

    /**
     * 포지션 기반 개인화 코멘트. 평단·수량·목표가·손절가를 Claude에 전달해 내 포지션 기준 해석 제공.
     * targetPrice·stopPrice 미입력 시 0.0 전달(백엔드가 0.0 = 미입력으로 처리).
     * thesis 있으면 논지 유효성을 함께 점검(캐시 키 분리).
     * refresh=true: 캐시를 건너뛰고 즉시 재생성(수동 재생성 버튼 전용).
     */
    @Throws(Exception::class)
    suspend fun getAnalysisPersonalized(
        code: String,
        avgPrice: Double,
        qty: Long,
        targetPrice: Double,
        stopPrice: Double,
        mode: String = "defensive",
        refresh: Boolean = false,
        thesis: String? = null,
        thesisHistory: List<com.haky.edge.model.ThesisSnapshot> = emptyList(),
        horizon: String? = null,
    ): Analysis = client.get("$baseUrl/analysis/$code") {
        parameter("avgPrice", avgPrice)
        parameter("qty", qty)
        if (targetPrice > 0.0) parameter("targetPrice", targetPrice)
        if (stopPrice > 0.0) parameter("stopPrice", stopPrice)
        if (mode != "defensive") parameter("mode", mode)
        if (refresh) parameter("refresh", "true")
        if (!thesis.isNullOrBlank()) parameter("thesis", thesis)
        if (thesisHistory.size >= 2) parameter("thesisHistory", encodeThesisHistory(thesisHistory))
        // 계좌 성격 — "long"(장기 계좌 컨텍스트)만 전송(자유는 기존 동작·공유 캐시 유지)
        if (horizon == "long") parameter("horizon", horizon)
    }.body()

    /** 논지 변천 JSON 직렬화 — 라우트가 ThesisSnapshot 배열로 역직렬화한다. */
    private fun encodeThesisHistory(history: List<com.haky.edge.model.ThesisSnapshot>): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.haky.edge.model.ThesisSnapshot.serializer()), history)

    /** 종목 공매도 거래량·잔고 요약. KRX 데이터, 당일 캐시. 데이터 없으면 null. */
    @Throws(Exception::class)
    suspend fun getShortSelling(code: String): ShortSellingSummary? = runCatching {
        client.get("$baseUrl/short-selling/$code").body<ShortSellingSummary>()
    }.getOrNull()

    /**
     * PER/PBR 역사적 밴드 + 현재 백분위. 과거 5년 연간 말 기준 계산.
     * DART/KIS 데이터 부족 시 null.
     */
    @Throws(Exception::class)
    suspend fun getValuationBand(code: String): ValuationBand? = runCatching {
        client.get("$baseUrl/valuation-band/$code").body<ValuationBand>()
    }.getOrNull()

    /** 동종(peer) 상대 밸류에이션. 클러스터 미정의·peer 부족 시 null(카드 숨김). */
    @Throws(Exception::class)
    suspend fun getPeerValuation(code: String): PeerValuation? = runCatching {
        client.get("$baseUrl/peer-valuation/$code").body<PeerValuation>()
    }.getOrNull()

    /**
     * 신호별 익일 적중률 백테스트(외인·기관 순매수·거래량 급증). 일봉 부족 시 null.
     * 표본수 n과 confident 플래그를 그대로 노출 — 작은 표본은 화면에서 신중히 표기.
     */
    @Throws(Exception::class)
    suspend fun getBacktest(code: String): Backtest? = runCatching {
        client.get("$baseUrl/backtest/$code").body<Backtest>()
    }.getOrNull()

    /** 수급 규모와 당일 등락률의 Pearson 상관(외인/기관). 데이터 부족 시 null. */
    @Throws(Exception::class)
    suspend fun getFlowSensitivity(code: String): FlowSensitivity? = runCatching {
        client.get("$baseUrl/flow-sensitivity/$code").body<FlowSensitivity>()
    }.getOrNull()

    /**
     * 유사 국면 통계(F1). 오늘 상태와 유사했던 과거 시점들의 이후 5/20/60거래일
     * 실제 수익률 분포 — 과거 기저율이지 예측이 아님. 이력 부족·오류 시 null(카드 숨김).
     */
    @Throws(Exception::class)
    suspend fun getAnalog(code: String): AnalogReport? = runCatching {
        client.get("$baseUrl/analog/$code").body<AnalogReport>()
    }.getOrNull()

    /** AI 시장 방향 예측 적중률 리포트. 예측(미국 지수·환율) vs 실제(코스피) 채점 결과. */
    @Throws(Exception::class)
    suspend fun getMoodAccuracy(): MoodAccuracyReport =
        client.get("$baseUrl/market-mood-log").body()

    /** 종목 코멘트 스탠스 적중률(F6). 시장 방향 예측과 별도 지표. 오류 시 null(항목 숨김). */
    @Throws(Exception::class)
    suspend fun getStanceStats(): StanceStats? = runCatching {
        client.get("$baseUrl/stance-stats").body<StanceStats>()
    }.getOrNull()

    /** 실적 발표 프리뷰(F3): run-rate 유지 시 YoY + 과거 발표일 반응 통계. 오류 시 null. */
    @Throws(Exception::class)
    suspend fun getEarningsPreview(code: String): EarningsPreview? = runCatching {
        client.get("$baseUrl/earnings-preview/$code").body<EarningsPreview>()
    }.getOrNull()

    /**
     * 매수 프리모템 생성(F5): 매수 사유를 받아 "가설이 깨지는 조건"을 구조화.
     * 매수 기록당 1회(백엔드 Claude 호출). 실패 시 null(기록 자체는 영향 없음).
     */
    @Throws(Exception::class)
    suspend fun createPremortem(
        code: String,
        reason: String,
        avgPrice: Double? = null,
        qty: Long? = null,
        stopPrice: Double? = null,
    ): Premortem? = runCatching {
        client.post("$baseUrl/premortem/$code") {
            contentType(ContentType.Application.Json)
            setBody(PremortemRequest(reason, avgPrice, qty, stopPrice))
        }.body<Premortem>()
    }.getOrNull()

    /** 저장된 프리모템 조회(상세 화면 조건 카드). 없거나 오류면 null(카드 숨김). */
    @Throws(Exception::class)
    suspend fun getPremortem(code: String): Premortem? = runCatching {
        client.get("$baseUrl/premortem/$code").body<Premortem>()
    }.getOrNull()

    /**
     * 완결된 매매 복기(B2). 클라가 action_log에서 매수·매도 쌍을 조합해 보낸다.
     * 생성에 수 초 걸림 — 백그라운드 Task로 호출. 실패 시 null(기록 자체에 영향 없음).
     */
    @Throws(Exception::class)
    suspend fun postTradeReview(
        code: String,
        buyDate: String,
        buyPrice: Double,
        sellDate: String,
        sellPrice: Double,
        qty: Long? = null,
        buyReason: String? = null,
        sellReason: String? = null,
        thesis: String? = null,
    ): TradeReview? = runCatching {
        client.post("$baseUrl/trade-review") {
            contentType(ContentType.Application.Json)
            setBody(TradeReviewRequest(
                code = code,
                buyDate = buyDate,
                buyPrice = buyPrice,
                sellDate = sellDate,
                sellPrice = sellPrice,
                qty = qty,
                buyReason = buyReason,
                sellReason = sellReason,
                thesis = thesis,
            ))
        }.body<TradeReview>()
    }.getOrNull()

    /**
     * 종목 딥리서치(C1/C2). 웹검색+사실 데이터 결합 심층 리포트.
     * 생성에 수십 초 걸릴 수 있음 — 버튼 탭 시 백그라운드 로드, 당일 캐시.
     */
    @Throws(Exception::class)
    suspend fun getDeepResearch(code: String): DeepResearch =
        client.get("$baseUrl/deep-research/$code").body()

    /**
     * 종목 자유 질문 Q&A. analyze()와 같은 사실 데이터를 근거로 질문에만 답한다.
     * history: 이전 대화 턴(서버 무상태 — 앱이 직전 3턴을 되보냄).
     * avgPrice·qty 있으면 내 포지션 기준 해석 포함. thesis 있으면 논지 유효성도 점검.
     */
    @Throws(Exception::class)
    suspend fun ask(
        code: String,
        question: String,
        avgPrice: Double? = null,
        qty: Long? = null,
        targetPrice: Double? = null,
        stopPrice: Double? = null,
        mode: String = "defensive",
        history: List<AskTurn> = emptyList(),
        thesis: String? = null,
    ): AskAnswer = client.post("$baseUrl/ask/$code") {
        contentType(ContentType.Application.Json)
        setBody(AskRequest(question, avgPrice, qty, targetPrice, stopPrice, mode, history, thesis?.trim()?.ifBlank { null }))
    }.body()

    /**
     * 포트폴리오 종합 진단. 보유 전체 구조 분석(집중도·매크로 노출·밸류 분포 + Claude 해석).
     * positions: code → (avgPrice, qty). theses: code → 투자 논지(최대 200자).
     * 개인별 캐시(날짜+포지션집합+논지해시+모드). thesis 없으면 구버전 캐시 호환.
     * JSON body POST — 한글 논지 여러 건을 URL에 담으면 한도 초과 위험.
     * accountScope=true면 계좌 탭 범위(부분 포트폴리오) — 서버가 리밸런싱 스냅샷을 갱신하지 않는다.
     */
    @Throws(Exception::class)
    suspend fun getPortfolioReview(
        positions: Map<String, Pair<Double, Long>>,
        theses: Map<String, String> = emptyMap(),
        mode: String = "defensive",
        refresh: Boolean = false,
        accountScope: Boolean = false,
        horizon: String? = null,
    ): PortfolioReview {
        val entries = positions.map { (code, pos) ->
            ReviewPositionEntry(code, pos.first, pos.second, theses[code]?.trim()?.ifBlank { null })
        }
        return client.post("$baseUrl/portfolio-review") {
            contentType(ContentType.Application.Json)
            setBody(PortfolioReviewRequest(
                entries, mode.takeIf { it != "defensive" }, refresh,
                scope = if (accountScope) "account" else null,
                // 장기 계좌 컨텍스트만 전송(자유는 기존 동작·캐시 키 불변)
                horizon = horizon.takeIf { it == "long" },
            ))
        }.body()
    }

    /** 리밸런싱 비중 점검 결과. 스냅샷 없거나 낡으면 evaluated=false. */
    @Throws(Exception::class)
    suspend fun getRebalanceCheck(): RebalanceCheck =
        client.get("$baseUrl/rebalance-check").body()

    /** 현재 스냅샷을 기준점으로 재설정. */
    @Throws(Exception::class)
    suspend fun postRebalanceBaseline() {
        client.post("$baseUrl/rebalance/baseline")
    }

    /** peer 바스켓 유니버스에서 2개 이상 신호가 켜진 지켜볼 후보 발굴(당일 캐시). LLM 0. */
    @Throws(Exception::class)
    suspend fun getDiscovery(): DiscoveryReport =
        client.get("$baseUrl/discovery").body()

    /** KOSPI 6개 업종지수 5일/20일 상대강도 순환 판정(당일 캐시). LLM 0. */
    @Throws(Exception::class)
    suspend fun getSectorRotation(): SectorRotation =
        client.get("$baseUrl/sector-rotation").body()

    /** 향후 N일 거시 이벤트 목록(CPI·FOMC·한은·MSCI·동시만기일 등). 기본 30일. */
    @Throws(Exception::class)
    suspend fun getEvents(days: Int = 30): List<MarketEvent> =
        client.get("$baseUrl/events") {
            parameter("days", days)
        }.body()

    /** 이벤트 캘린더 동기화(Claude 웹검색). 캐시 없을 때 자동 호출. */
    @Throws(Exception::class)
    suspend fun syncEvents() {
        client.post("$baseUrl/events/sync")
    }

    /**
     * 두 종목 비교 코멘트. 핵심 지표(현재가·52주위치·PER·수급·밸류에이션)를 나란히 수집하고
     * Claude가 어느 쪽이 더 나아 보이는지 결론을 내린다. 당일·모드별 캐시(codeA/B 순서 무관).
     * refresh=true: 캐시 bypass 재생성.
     */
    @Throws(Exception::class)
    suspend fun getComparison(
        codeA: String,
        codeB: String,
        mode: String = "defensive",
        refresh: Boolean = false,
    ): Comparison = client.get("$baseUrl/compare") {
        parameter("codeA", codeA)
        parameter("codeB", codeB)
        if (mode != "defensive") parameter("mode", mode)
        if (refresh) parameter("refresh", "true")
    }.body()
}
