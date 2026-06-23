package com.haky.edge.toss

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import com.haky.edge.util.KST
import io.ktor.serialization.kotlinx.json.json
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * 토스(Toss) Open API 클라이언트 — 한투에 없는 데이터(투자유의·개장캘린더·상하한가)를 보강한다.
 * 시세 메인(현재가·수급·지수·월봉)은 KisClient 가 유지하고, 여기선 그 외만 채운다.
 *
 * 인증: OAuth2 client_credentials. 액세스 토큰을 메모리 + 파일에 캐시해 재사용한다
 * (KisClient 와 동일한 double-checked locking + 파일 캐시 패턴 — 재시작 시 재발급을 피함).
 * 슬라이스0은 토큰 발급 + prices 1건 라운드트립까지만(연결 확인). warnings/calendar 는 후속 슬라이스.
 */
class TossClient(
    private val clientId: String,
    private val clientSecret: String,
    private val baseUrl: String = "https://openapi.tossinvest.com",
) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // 토큰 캐시 상태. 발급은 Mutex 로 직렬화(아래 double-checked locking).
    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0

    // 토큰을 파일에도 저장해 재시작 시 재발급을 피한다. 토큰은 준-비밀값이므로 .gitignore 처리(.toss-token.json).
    private val tokenFile = File(System.getenv("TOSS_TOKEN_CACHE") ?: ".toss-token.json")
    private val cacheJson = Json { ignoreUnknownKeys = true }

    /**
     * 유효한 액세스 토큰을 반환한다.
     *  1) 락 없이 메모리 캐시 확인(빠른 경로) → 2) 만료 시에만 락 잡고 파일 캐시 → 3) 그래도 없으면 발급.
     */
    private suspend fun token(): String {
        validToken()?.let { return it }
        return tokenMutex.withLock {
            validToken()?.let { return it }       // 락 대기 중 다른 코루틴이 발급했을 수 있음
            loadTokenFromFile()?.let { return it } // 재시작 직후: 파일에 살아있는 토큰 재사용
            if (clientId.isBlank() || clientSecret.isBlank()) {
                throw TossException("TOSS_CLIENT_ID / TOSS_CLIENT_SECRET 가 설정되지 않았습니다 (.env 확인)")
            }
            // 토큰 엔드포인트는 form-urlencoded. submitForm 이 POST + 해당 content-type 으로 보낸다.
            val resp: TossTokenResponse = http.submitForm(
                url = "$baseUrl/oauth2/token",
                formParameters = Parameters.build {
                    append("grant_type", "client_credentials")
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                },
            ).body()
            if (resp.accessToken.isBlank()) {
                throw TossException("토스 토큰 발급 실패: ${resp.error} ${resp.errorDescription}".trim())
            }
            cachedToken = resp.accessToken
            // expires_in 기준 만료에 60초 여유. 토큰이 JWT(eyJ...)면 exp 도 파싱해 둘 중 이른 값을 쓴다(재사용 토큰 대비).
            val fromExpiresIn = System.currentTimeMillis() + (resp.expiresIn - 60).coerceAtLeast(60) * 1000
            val fromJwtExp = jwtExpMs(resp.accessToken)
            tokenExpiryMs = if (fromJwtExp > 0) minOf(fromExpiresIn, fromJwtExp) else fromExpiresIn
            saveTokenToFile()
            resp.accessToken
        }
    }

    /** 메모리 캐시가 아직 유효하면 그 토큰, 아니면 null. */
    private fun validToken(): String? =
        cachedToken?.takeIf { System.currentTimeMillis() < tokenExpiryMs }

    /** 파일에 저장된 토큰이 아직 유효하면 메모리에 올리고 반환, 아니면 null. */
    private fun loadTokenFromFile(): String? = try {
        if (!tokenFile.exists()) null
        else cacheJson.decodeFromString<TossTokenCache>(tokenFile.readText())
            .takeIf { System.currentTimeMillis() < it.expiryMs }
            ?.also { cachedToken = it.token; tokenExpiryMs = it.expiryMs }
            ?.token
    } catch (_: Exception) {
        null // 파일 손상 등은 무시하고 새로 발급
    }

    /** 현재 메모리 토큰을 파일에 저장. 실패해도 동작엔 지장 없음(다음 재시작에 재발급될 뿐). */
    private fun saveTokenToFile() {
        val token = cachedToken ?: return
        try {
            tokenFile.writeText(cacheJson.encodeToString(TossTokenCache(token, tokenExpiryMs)))
        } catch (_: Exception) {
        }
    }

    /** 서버 시작 시 미리 호출해 첫 요청의 토큰 발급 지연을 없앤다(키 미설정이면 조용히 건너뜀). */
    suspend fun warmup() {
        if (clientId.isNotBlank() && clientSecret.isNotBlank()) token()
    }

    /** 캐시 토큰을 무효화(메모리+파일). 401(만료·타 프로세스 발급으로 무효화 등) 시 재발급을 강제한다. */
    private fun invalidateToken() {
        cachedToken = null
        tokenExpiryMs = 0
        runCatching { tokenFile.delete() }
    }

    /**
     * Bearer 토큰을 붙여 GET. 401(토큰 무효)이면 토큰을 버리고 1회 재발급해 재시도한다
     * (토스는 새 토큰 발급 시 이전 토큰을 무효화할 수 있어, 캐시 토큰이 401날 수 있다).
     * 재시도 후에도 2xx가 아니면 예외 — 에러 응답이 빈 결과(예: 가짜 '휴장')로 둔갑하는 것을 막는다.
     */
    private suspend fun authedGet(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        suspend fun once(): HttpResponse = http.get(url) {
            header("Authorization", "Bearer ${token()}")
            block()
        }
        var resp = once()
        if (resp.status == HttpStatusCode.Unauthorized) {
            invalidateToken()
            resp = once()
        }
        if (!resp.status.isSuccess()) throw TossException("토스 호출 실패: ${resp.status} ($url)")
        return resp
    }

    /**
     * 다종목 현재가 조회(최대 200개 콤마구분). 토스는 lastPrice 만 준다 — 등락률/거래량은 없음.
     * 슬라이스0의 연결 확인용. 메인 시세는 KisClient.getPrice 가 계속 담당한다.
     */
    suspend fun getPrices(symbols: List<String>): List<TossPrice> {
        if (symbols.isEmpty()) return emptyList()
        val resp: TossPricesResponse = authedGet("$baseUrl/api/v1/prices") {
            parameter("symbols", symbols.joinToString(","))
        }.body()
        return resp.result
    }

    /**
     * 종목 투자유의(시장경보·단기과열·정리매매·VI) 원본 목록. 발동 항목이 없으면 빈 리스트.
     * 키 미설정이면 빈 리스트를 돌려 호출부(상세 화면)가 토스 없이도 동작하게 한다.
     */
    suspend fun getWarnings(symbol: String): List<TossWarning> {
        if (clientId.isBlank() || clientSecret.isBlank()) return emptyList()
        val resp: TossWarningsResponse = authedGet("$baseUrl/api/v1/stocks/$symbol/warnings").body()
        return resp.result
    }

    /**
     * 진행 중인 투자유의만 한글 라벨·severity 로 정규화해 반환. 해제일이 지난 과거 경보는 제외
     * (만료된 칩/문구는 오해를 부른다). endDate 미정(진행 중)은 포함. 상세 칩·AI 코멘트 facts 공용.
     */
    suspend fun getActiveWarnings(symbol: String): List<StockWarning> {
        val today = LocalDate.now(KST).toString() // yyyy-MM-dd (KST 기준)
        return getWarnings(symbol)
            .filter { it.endDate.isNullOrBlank() || it.endDate >= today }
            .map { it.toStockWarning() }
    }

    /**
     * 종목 가격 제한폭(상·하한가). 제한폭 없는 시장(미국 등)이면 둘 다 null인 PriceLimits.
     * 키 미설정이면 null(호출부가 카드 숨김).
     */
    suspend fun getPriceLimits(symbol: String): PriceLimits? {
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        val resp: TossPriceLimitsResponse = authedGet("$baseUrl/api/v1/price-limits") {
            parameter("symbol", symbol)
        }.body()
        return resp.result.toPriceLimits()
    }

    // 개장 캘린더 당일 캐시(KST 날짜 키). 캘린더는 하루 단위로만 바뀌어 전 유저가 1회 호출분을 공유.
    @Volatile private var calendarCache: Pair<String, MarketCalendar>? = null

    /**
     * 국내(KRX) 개장 캘린더 — 오늘 휴장 여부 + 직전/다음 거래일. 당일 캐시.
     * 키 미설정/오류 시 null(호출부가 캘린더 없이도 동작하게).
     */
    suspend fun getMarketCalendar(): MarketCalendar? {
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        val today = LocalDate.now(KST).toString()
        calendarCache?.let { (day, cal) -> if (day == today) return cal }
        val resp: TossCalendarResponse = authedGet("$baseUrl/api/v1/market-calendar/KR").body()
        val cal = resp.result.toMarketCalendar()
        calendarCache = today to cal
        return cal
    }
}

/** 파일 캐시에 저장하는 토큰 + 만료시각(ms). */
@Serializable
private data class TossTokenCache(val token: String, val expiryMs: Long)

/**
 * JWT 의 payload(2번째 세그먼트)에서 exp(초)를 읽어 ms 로 반환. 실패하면 0.
 * 토스 토큰은 JWT 라 expires_in 과 별개로 실제 만료시각을 직접 확인할 수 있다.
 */
internal fun jwtExpMs(token: String): Long = try {
    val payload = token.split(".").getOrNull(1) ?: return 0
    val json = String(Base64.getUrlDecoder().decode(payload))
    val exp = Regex(""""exp"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    exp * 1000
} catch (_: Exception) {
    0
}
