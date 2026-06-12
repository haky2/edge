package com.haky.edge.kis

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 한투(KIS) Open API 클라이언트.
 * - OAuth 접근토큰을 메모리 + 파일에 캐시해 재사용. 한투는 "토큰 1일 1회 발급 원칙"이고
 *   잦은 발급 시 이용이 제한될 수 있어, 24시간짜리 토큰을 파일에도 저장해 재시작 시 재발급을 피한다.
 * - 시세 등 읽기 전용 호출만 사용.
 */
class KisClient(
    private val appKey: String,
    private val appSecret: String,
    private val baseUrl: String,
) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            // encodeDefaults=true 필수: grant_type 같은 기본값 필드가 빠지면 한투가 EGW00115 반환
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }

    // 토큰 캐시 상태. 여러 요청이 동시에 들어오므로 @Volatile 로 가시성을 보장하고,
    // 실제 발급은 Mutex 로 직렬화한다(아래 double-checked locking 참고).
    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0

    // 현재가 단기 캐시. shouldAutoRefresh가 캐시 적중마다 KIS 실호출하는 것을 막는다.
    // 30초 TTL: AnalysisService의 stale 감지 쿨다운(30분)보다 훨씬 짧아 정확도 손실 없음.
    private val priceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Quote, Long>>()
    private val PRICE_CACHE_TTL_MS = 30_000L

    // 수급 당일 캐시. 외인/기관 확정값은 장후(~16:30)에 확정되고 다음 장 전까지 바뀌지 않는다.
    // 날짜 prefix("YYYY-MM-DD|code")로 키를 잡아 날짜가 바뀌면 자동 무효화.
    private val investorCache = java.util.concurrent.ConcurrentHashMap<String, List<InvestorFlow>>()

    // 한투 시세 호출 동시 실행 수 제한.
    // 한투 정책: "신규 고객은 신청 후 3일간 초당 3건"으로 유량 제한, 이후 기본 유량으로 자동 상향.
    // (모의투자는 제외) → 신규 키일수록 빡세서, 동시 실행을 묶어 한도 초과를 막는다.
    // 3일 지나 유량이 풀리면 KIS_MAX_CONCURRENCY 를 올려 다종목 조회를 더 빠르게 할 수 있다.
    private val rateLimiter = Semaphore(
        permits = System.getenv("KIS_MAX_CONCURRENCY")?.toIntOrNull() ?: 3,
    )

    // 토큰을 파일에도 저장해 재시작 시 재발급을 피한다(한투 1일 1회 발급 원칙 준수).
    // 토큰은 24시간 유효한 준-비밀값이므로 이 파일은 .gitignore 처리한다.
    private val tokenFile = File(System.getenv("KIS_TOKEN_CACHE") ?: ".kis-token.json")
    private val cacheJson = Json { ignoreUnknownKeys = true }

    /**
     * 유효한 접근토큰을 반환한다. 한투 토큰은 24시간 유효하지만 발급 자체에 분당 호출 제한이 있어,
     * 매 요청마다 새로 받으면 곧바로 막힌다 → 반드시 캐시해 재사용한다.
     *
     * double-checked locking:
     *  1) 락 없이 먼저 확인 — 대부분의 호출은 캐시가 유효해 락 비용 없이 바로 반환된다(빠른 경로).
     *  2) 캐시가 없거나 만료됐을 때만 락을 잡고, 락 안에서 한 번 더 확인한다.
     *     동시에 여러 요청이 "만료"를 봤더라도, 먼저 들어온 하나만 발급하고
     *     나머지는 그 사이 채워진 캐시를 보고 재발급을 건너뛴다(중복 발급 방지).
     */
    private suspend fun token(): String {
        validToken()?.let { return it } // 빠른 경로: 메모리 캐시가 유효하면 락 없이 반환
        return tokenMutex.withLock {
            validToken()?.let { return it }       // 락 대기 중 다른 코루틴이 발급했을 수 있음
            loadTokenFromFile()?.let { return it } // 재시작 직후: 파일에 살아있는 토큰이 있으면 재사용(재발급 회피)
            if (appKey.isBlank() || appSecret.isBlank()) {
                throw KisException("KIS_APP_KEY / KIS_APP_SECRET 가 설정되지 않았습니다 (.env 확인)")
            }
            val resp: KisTokenResponse = http.post("$baseUrl/oauth2/tokenP") {
                contentType(ContentType.Application.Json)
                // grant_type 은 KisTokenRequest 의 기본값이라, JSON 직렬화 시 encodeDefaults=true 가 아니면
                // 통째로 빠져 한투가 EGW00115(grant_type 필수) 에러를 낸다. (HttpClient 설정에서 켜둠)
                setBody(KisTokenRequest(appkey = appKey, appsecret = appSecret))
            }.body()
            if (resp.accessToken.isBlank()) {
                throw KisException("토큰 발급 실패: ${resp.errorCode} ${resp.errorDescription}".trim())
            }
            cachedToken = resp.accessToken
            // 만료 60초 전에 미리 폐기해, 경계 시점에 막 만료된 토큰으로 호출하는 일을 피한다.
            // coerceAtLeast(60): expires_in 이 비정상적으로 작아도 음수 만료가 되지 않도록 하한선.
            tokenExpiryMs = System.currentTimeMillis() + (resp.expiresIn - 60).coerceAtLeast(60) * 1000
            saveTokenToFile() // 다음 재시작 때 재사용하도록 파일에 기록
            resp.accessToken
        }
    }

    /** 메모리 캐시가 아직 유효하면 그 토큰, 아니면 null. */
    private fun validToken(): String? =
        cachedToken?.takeIf { System.currentTimeMillis() < tokenExpiryMs }

    /** 파일에 저장된 토큰이 아직 유효하면 메모리에 올리고 반환, 아니면 null. */
    private fun loadTokenFromFile(): String? = try {
        if (!tokenFile.exists()) null
        else cacheJson.decodeFromString<TokenCache>(tokenFile.readText())
            .takeIf { System.currentTimeMillis() < it.expiryMs }
            ?.also { cachedToken = it.token; tokenExpiryMs = it.expiryMs }
            ?.token
    } catch (_: Exception) {
        null // 파일 손상 등은 무시하고 새로 발급
    }

    /** 현재 메모리 토큰을 파일에 저장(다음 재시작 때 재사용). 실패해도 동작엔 지장 없음. */
    private fun saveTokenToFile() {
        val token = cachedToken ?: return
        try {
            tokenFile.writeText(cacheJson.encodeToString(TokenCache(token, tokenExpiryMs)))
        } catch (_: Exception) {
            // 저장 실패 시 다음 재시작에 재발급될 뿐, 기능엔 영향 없음
        }
    }

    /** 서버 시작 시 미리 호출해 첫 번째 API 요청의 토큰 발급 지연을 없앤다. */
    suspend fun warmup() { token() }

    /**
     * 6자리 종목코드의 현재가/등락/거래량/고저를 조회해 정규화 Quote로 반환.
     *
     * 초당 한도를 넘으면 한투가 일부 요청을 rt_cd != "0" 으로 거부한다(관심종목 다건 조회 시 흔함).
     * 동시성 제한(Semaphore)만으론 부족해, 거부당하면 점증 백오프로 재시도해 자동 복구한다.
     */
    suspend fun getPrice(code: String): Quote {
        val accessToken = token()
        // 단기 캐시 확인 — shouldAutoRefresh의 stale 감지용 호출이 KIS를 매번 치지 않게.
        priceCache[code]?.let { (q, ts) ->
            if (System.currentTimeMillis() - ts < PRICE_CACHE_TTL_MS) return q
        }
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            // withPermit: 동시에 최대 permits 개만 한투를 호출(나머지는 대기) → 폭주로 인한 한도 초과 완화.
            val resp = rateLimiter.withPermit { requestPrice(code, accessToken) }
            val o = resp.output
            // 한투는 HTTP 200이어도 본문 rt_cd 로 성패를 알린다("0"이 성공).
            if (resp.rtCd == "0" && o != null) {
                val quote = o.toQuote(code)
                priceCache[code] = Pair(quote, System.currentTimeMillis())
                return quote
            }
            lastMsg = resp.msg1.ifBlank { "rt_cd=${resp.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) {
                delay(BACKOFF_MS * (attempt + 1)) // 250ms, 500ms, 750ms ...
            }
        }
        throw KisException("한투 조회 실패($code): $lastMsg")
    }

    /** 현재가 조회 HTTP 호출 1회. 한투는 Bearer 토큰 + appkey/appsecret 헤더를 모두 요구한다. */
    private suspend fun requestPrice(code: String, accessToken: String): KisPriceResponse =
        http.get("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price") {
            header("authorization", "Bearer $accessToken")
            header("appkey", appKey)
            header("appsecret", appSecret)
            header("tr_id", "FHKST01010100") // tr_id = 어떤 API인지. 주식 현재가 시세.
            header("custtype", "P")           // P=개인
            parameter("FID_COND_MRKT_DIV_CODE", "J") // J=주식(KRX)
            parameter("FID_INPUT_ISCD", code)
        }.body()

    /**
     * 종목별 일별 투자자 수급(외인/기관/개인 순매수)을 최근 days일치 반환. 최신일이 앞.
     * 장후 확정 일별값(CLAUDE.md). getPrice 와 같은 동시성 제한 + rt_cd 백오프 재시도를 적용.
     */
    suspend fun getInvestorFlow(code: String, days: Int = 5): List<InvestorFlow> {
        val today = java.time.LocalDate.now().toString()
        val cacheKey = "$today|$code"
        investorCache[cacheKey]?.let { cached ->
            return if (days <= cached.size) cached.take(days) else cached
        }

        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            val resp = rateLimiter.withPermit { requestInvestor(code, accessToken) }
            if (resp.rtCd == "0") {
                // 한투는 최신 행에 "당일"을 주는데 장 마감 전이면 전부 0(미확정)으로 온다.
                // CLAUDE.md 원칙대로 확정 일별값만 쓴다 → 외인·기관·개인이 모두 0인 행은 제외하고 N일.
                val flows = resp.output
                    .map { it.toInvestorFlow() }
                    .filter { it.foreign != 0L || it.institution != 0L || it.individual != 0L }
                    .take(30) // 당일 캐시 목적으로 넉넉히 보관
                investorCache[cacheKey] = flows
                return flows.take(days)
            }
            lastMsg = resp.msg1.ifBlank { "rt_cd=${resp.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        throw KisException("한투 수급 조회 실패($code): $lastMsg")
    }

    /**
     * 종목 일봉 차트(최근 bars개 영업일). getPrice 와 동일한 동시성 제한 + rt_cd 재시도.
     * tr_id FHKST03010100, 조회구분 D=일봉. 최신일이 앞(output2).
     * 이평선(5/20/60)·RSI·거래량 추세 계산용으로, 넉넉하게 최소 62개(RSI 14 + 이평 60 - 12 여유) 요청.
     */
    suspend fun getDailyChart(code: String, bars: Int = 62): List<DailyBar> {
        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            val resp = rateLimiter.withPermit { requestDailyChart(code, accessToken) }
            if (resp.rtCd == "0") {
                return resp.output2.take(bars).map {
                    DailyBar(
                        date = it.date,
                        open = it.open.toLongSafe(),
                        high = it.high.toLongSafe(),
                        low = it.low.toLongSafe(),
                        close = it.close.toLongSafe(),
                        volume = it.volume.toLongSafe(),
                    )
                }.filter { it.close > 0 }   // 비정상(0) 행 방어
            }
            lastMsg = resp.msg1.ifBlank { "rt_cd=${resp.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        throw KisException("한투 일봉 조회 실패($code): $lastMsg")
    }

    /**
     * 상장주식수(lstn_stcn) 반환. ValuationBand 계산 시 EPS/BPS 분모로 사용.
     * 내부적으로 inquire-price 를 재호출하므로 이미 당일 시세가 있으면 함께 쓰도록 설계.
     */
    suspend fun getListedShares(code: String): Long {
        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            val resp = rateLimiter.withPermit { requestPrice(code, accessToken) }
            if (resp.rtCd == "0" && resp.output != null) {
                return resp.output.listedShares.toLongSafe()
            }
            lastMsg = resp.msg1.ifBlank { "rt_cd=${resp.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        throw KisException("한투 상장주식수 조회 실패($code): $lastMsg")
    }

    /**
     * 종목 월봉 차트(최근 months개월). 밸류에이션 히스토리 밴드용(5년 연도말 가격).
     * 동일 API(FHKST03010100), period_div_code=M. 최신일이 앞.
     */
    suspend fun getMonthlyChart(code: String, months: Int = 65): List<DailyBar> {
        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            val resp = rateLimiter.withPermit { requestMonthlyChart(code, accessToken, months) }
            if (resp.rtCd == "0") {
                return resp.output2.take(months).map {
                    DailyBar(
                        date = it.date,
                        open = it.open.toLongSafe(),
                        high = it.high.toLongSafe(),
                        low = it.low.toLongSafe(),
                        close = it.close.toLongSafe(),
                        volume = it.volume.toLongSafe(),
                    )
                }.filter { it.close > 0 }
            }
            lastMsg = resp.msg1.ifBlank { "rt_cd=${resp.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        throw KisException("한투 월봉 조회 실패($code): $lastMsg")
    }

    /** 월봉 HTTP 호출 1회. period_div_code=M. */
    private suspend fun requestMonthlyChart(code: String, accessToken: String, months: Int): KisDailyResponse {
        val today = java.time.LocalDate.now().toString().replace("-", "")
        val startDate = java.time.LocalDate.now().minusMonths(months.toLong()).toString().replace("-", "")
        return http.get("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice") {
            header("authorization", "Bearer $accessToken")
            header("appkey", appKey)
            header("appsecret", appSecret)
            header("tr_id", "FHKST03010100")
            header("custtype", "P")
            parameter("FID_COND_MRKT_DIV_CODE", "J")
            parameter("FID_INPUT_ISCD", code)
            parameter("FID_INPUT_DATE_1", startDate)
            parameter("FID_INPUT_DATE_2", today)
            parameter("FID_PERIOD_DIV_CODE", "M") // M=월봉
            parameter("FID_ORG_ADJ_PRC", "1")
        }.body()
    }

    /** 일봉 HTTP 호출 1회. period_div_code=D, adj_prc_div=1(수정주가). */
    private suspend fun requestDailyChart(code: String, accessToken: String): KisDailyResponse {
        // start/end: 한투는 최근일 기준으로 내려주므로 end=오늘, start=충분히 과거로 둔다.
        // 차트 기간 토글(1개월/3개월/전체)용으로 넉넉히 7개월(한투 단일 응답 최대 ~100건) 요청.
        val today = java.time.LocalDate.now().toString().replace("-", "")
        val startDate = java.time.LocalDate.now().minusMonths(7).toString().replace("-", "")
        return http.get("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice") {
            header("authorization", "Bearer $accessToken")
            header("appkey", appKey)
            header("appsecret", appSecret)
            header("tr_id", "FHKST03010100") // 국내주식 기간별 시세(일/주/월/년)
            header("custtype", "P")
            parameter("FID_COND_MRKT_DIV_CODE", "J")
            parameter("FID_INPUT_ISCD", code)
            parameter("FID_INPUT_DATE_1", startDate)
            parameter("FID_INPUT_DATE_2", today)
            parameter("FID_PERIOD_DIV_CODE", "D") // D=일봉
            parameter("FID_ORG_ADJ_PRC", "1")    // 1=수정주가
        }.body()
    }

    /**
     * 브리핑 "시장 지표"용 매크로 지표를 모아서 반환(코스피·코스닥·원/달러·다우·나스닥·S&P500).
     * 각 지표는 서로 다른 한투 엔드포인트라 병렬로 부르고, 개별 실패는 무시하고(섹션 통째로 죽지 않게)
     * 성공분만 SPEC 순서대로 돌려준다. 모두 실패하면 빈 리스트가 된다.
     */
    suspend fun getMacroIndicators(): List<MacroIndicator> = coroutineScope {
        MACRO_SPECS
            .map { spec -> async { runCatching { fetchMacro(spec) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    /** 매크로 지표 1건 조회. getPrice 와 동일한 동시성 제한 + rt_cd 백오프 재시도. */
    private suspend fun fetchMacro(spec: MacroSpec): MacroIndicator {
        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            val raw = rateLimiter.withPermit {
                when (spec.kind) {
                    MacroKind.DOMESTIC -> requestIndex(spec.iscd, accessToken)
                    MacroKind.OVERSEAS -> requestOverseas(spec.mrktDiv, spec.iscd, accessToken)
                }
            }
            if (raw.rtCd == "0") {
                // 전일대비·등락률은 부호가 없을 수 있어 prdy_vrss_sign 으로 부호를 입힌다.
                // (원본에 이미 부호가 있어도 abs×sign 이라 결과는 동일 → 어느 쪽이든 안전)
                val mul = signMultiplier(raw.sign)
                return MacroIndicator(
                    key = spec.key,
                    label = spec.label,
                    value = raw.price.toDoubleSafe(),
                    change = kotlin.math.abs(raw.change.toDoubleSafe()) * mul,
                    changeRate = kotlin.math.abs(raw.changeRate.toDoubleSafe()) * mul,
                )
            }
            lastMsg = raw.msg.ifBlank { "rt_cd=${raw.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        throw KisException("한투 매크로 조회 실패(${spec.key}): $lastMsg")
    }

    /** 국내 업종 현재지수(코스피=0001, 코스닥=1001) 1회 호출 → 정규화 raw. */
    private suspend fun requestIndex(iscd: String, accessToken: String): MacroRaw {
        val r: KisIndexResponse =
            http.get("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-index-price") {
                header("authorization", "Bearer $accessToken")
                header("appkey", appKey)
                header("appsecret", appSecret)
                header("tr_id", "FHPUP02100000") // 국내업종 현재지수
                header("custtype", "P")
                parameter("FID_COND_MRKT_DIV_CODE", "U") // U=업종
                parameter("FID_INPUT_ISCD", iscd)
            }.body()
        val o = r.output
        return MacroRaw(
            price = o?.price ?: "0",
            change = o?.change ?: "0",
            sign = o?.sign ?: "3",
            changeRate = o?.changeRate ?: "0",
            rtCd = r.rtCd,
            msg = r.msg1,
        )
    }

    /** 해외 지수/환율 기간별시세 1회 호출 → 정규화 raw. output1(요약)에서 현재값을 읽는다. */
    private suspend fun requestOverseas(mrktDiv: String, iscd: String, accessToken: String): MacroRaw {
        // 해외장은 주말·휴장이 있어 end=오늘, start=10일 전으로 넉넉히 둔다(output1 요약값만 쓰므로 범위는 여유면 충분).
        val today = java.time.LocalDate.now().toString().replace("-", "")
        val startDate = java.time.LocalDate.now().minusDays(10).toString().replace("-", "")
        val r: KisOverseasResponse =
            http.get("$baseUrl/uapi/overseas-price/v1/quotations/inquire-daily-chartprice") {
                header("authorization", "Bearer $accessToken")
                header("appkey", appKey)
                header("appsecret", appSecret)
                header("tr_id", "FHKST03030100") // 해외주식 종목/지수/환율 기간별시세
                header("custtype", "P")
                parameter("FID_COND_MRKT_DIV_CODE", mrktDiv) // N=해외지수, X=환율
                parameter("FID_INPUT_ISCD", iscd)
                parameter("FID_INPUT_DATE_1", startDate)
                parameter("FID_INPUT_DATE_2", today)
                parameter("FID_PERIOD_DIV_CODE", "D") // D=일
            }.body()
        val o = r.output1
        return MacroRaw(
            price = o?.price ?: "0",
            change = o?.change ?: "0",
            sign = o?.sign ?: "3",
            changeRate = o?.changeRate ?: "0",
            rtCd = r.rtCd,
            msg = r.msg1,
        )
    }

    /** 일별 투자자 수급 HTTP 호출 1회. tr_id = 주식현재가 투자자. */
    private suspend fun requestInvestor(code: String, accessToken: String): KisInvestorResponse =
        http.get("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-investor") {
            header("authorization", "Bearer $accessToken")
            header("appkey", appKey)
            header("appsecret", appSecret)
            header("tr_id", "FHKST01010900") // 주식현재가 투자자(종목별 일별 수급)
            header("custtype", "P")
            parameter("FID_COND_MRKT_DIV_CODE", "J")
            parameter("FID_INPUT_ISCD", code)
        }.body()

    /**
     * 브리핑 "섹터 동향"용 KOSPI 업종지수 조회.
     * getMacroIndicators 와 동일한 패턴 — 병렬 조회, 개별 실패 무시.
     */
    suspend fun getSectorIndices(): List<SectorIndex> = coroutineScope {
        SECTOR_SPECS
            .map { spec -> async { runCatching { fetchSector(spec) }.getOrNull() } }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun fetchSector(spec: SectorSpec): SectorIndex {
        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            val raw = rateLimiter.withPermit { requestIndex(spec.iscd, accessToken) }
            if (raw.rtCd == "0") {
                val mul = signMultiplier(raw.sign)
                return SectorIndex(
                    key = "sector_${spec.iscd}",
                    label = spec.label,
                    value = raw.price.toDoubleSafe(),
                    change = kotlin.math.abs(raw.change.toDoubleSafe()) * mul,
                    changeRate = kotlin.math.abs(raw.changeRate.toDoubleSafe()) * mul,
                )
            }
            lastMsg = raw.msg.ifBlank { "rt_cd=${raw.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        throw KisException("한투 업종지수 조회 실패(${spec.iscd}): $lastMsg")
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_MS = 250L
    }
}

// 한투 원본(문자열) → 우리 Quote 로 변환.
// 주의: prdy_vrss(전일대비)·prdy_ctrt(등락률)는 이미 부호 포함("-192000","-9.58") → 부호 재적용 금지.
internal fun KisPriceOutput.toQuote(code: String) = Quote(
    code = code,
    price = price.toLongSafe(),
    change = change.toLongSafe(),
    changeRate = changeRate.toDoubleSafe(),
    volume = volume.toLongSafe(),
    open = open.toLongSafe(),
    high = high.toLongSafe(),
    low = low.toLongSafe(),
    high52w = high52w.toLongSafe(),
    low52w = low52w.toLongSafe(),
    per = per.toDoubleSafe(),
    pbr = pbr.toDoubleSafe(),
    sectorName = sectorName,
)

// 한투 원본 수급 행 → 우리 InvestorFlow. 순매수 수량은 이미 부호 포함이라 그대로 파싱.
internal fun KisInvestorRow.toInvestorFlow() = InvestorFlow(
    date = date,
    foreign = foreign.toLongSafe(),
    institution = institution.toLongSafe(),
    individual = individual.toLongSafe(),
)

// 한투 값은 문자열이고 가끔 빈 문자열이 오기도 해서, 파싱 실패 시 0으로 안전 처리한다.
internal fun String.toLongSafe(): Long = trim().toLongOrNull() ?: 0L
internal fun String.toDoubleSafe(): Double = trim().toDoubleOrNull() ?: 0.0

// ── 매크로 지표 정의/헬퍼 ─────────────────────────────────────────────

/** 매크로 지표를 어떤 엔드포인트로 부를지 구분. 국내 업종지수 vs 해외 지수/환율. */
private enum class MacroKind { DOMESTIC, OVERSEAS }

/** 매크로 지표 1개의 호출 사양. mrktDiv 는 해외(N=지수, X=환율)에서만 의미가 있다. */
private data class MacroSpec(
    val key: String,
    val label: String,
    val kind: MacroKind,
    val mrktDiv: String,
    val iscd: String,
)

/** 국내/해외 응답을 부호 적용 전 공통 형태로 모은 중간 표현. */
private data class MacroRaw(
    val price: String,
    val change: String,
    val sign: String,
    val changeRate: String,
    val rtCd: String,
    val msg: String,
)

/** prdy_vrss_sign: 1상한 2상승 3보합 4하한 5하락 → 하락(4,5)이면 −1, 그 외 +1. */
internal fun signMultiplier(sign: String): Int = when (sign.trim()) {
    "4", "5" -> -1
    else -> 1
}

// 노출 지표: 코스피·코스닥(국내 업종지수) + 원/달러·다우·나스닥·S&P500·WTI유가(해외 기간별시세).
// WTI: FID_COND_MRKT_DIV_CODE="C"(상품선물), FID_INPUT_ISCD="CL"(경질원유).
// 공포탐욕지수(fear_greed)는 CNN 별도 소스라 FearGreedClient에서 추가한다.
private val MACRO_SPECS = listOf(
    MacroSpec("kospi", "코스피", MacroKind.DOMESTIC, "U", "0001"),
    MacroSpec("kosdaq", "코스닥", MacroKind.DOMESTIC, "U", "1001"),
    MacroSpec("usdkrw", "원/달러", MacroKind.OVERSEAS, "X", "FX@KRW"),
    MacroSpec("dow", "다우", MacroKind.OVERSEAS, "N", ".DJI"),
    MacroSpec("nasdaq", "나스닥", MacroKind.OVERSEAS, "N", "COMP"),
    MacroSpec("sp500", "S&P500", MacroKind.OVERSEAS, "N", "SPX"),
    MacroSpec("crude", "WTI유가", MacroKind.OVERSEAS, "N", "CL"), // 상품선물도 FID_COND_MRKT_DIV_CODE=N으로 조회됨
)

/** 토큰 파일 캐시 형식. expiryMs = 만료 시각(epoch millis). */
@Serializable
private data class TokenCache(val token: String, val expiryMs: Long)

// ── 섹터 대시보드 정의 ─────────────────────────────────────────────────

private data class SectorSpec(val iscd: String, val label: String)

// KOSPI 업종지수 코드(FID_INPUT_ISCD). inquire-index-price(FHPUP02100000, MRKT=U) 로 조회.
// 우리 포트폴리오 관련 6개 업종 선별.
private val SECTOR_SPECS = listOf(
    SectorSpec("0014", "전기전자"),   // SK하이닉스·삼성전자·LG전자
    SectorSpec("0013", "기계"),       // HD현대중공업·한화에어로스페이스
    SectorSpec("0016", "운수장비"),   // KAI·항공우주
    SectorSpec("0018", "전기가스업"), // HD현대일렉트릭·산일전기
    SectorSpec("0028", "서비스업"),   // 삼성에스디에스·현대오토에버
    SectorSpec("0012", "철강금속"),   // 대한전선
)
