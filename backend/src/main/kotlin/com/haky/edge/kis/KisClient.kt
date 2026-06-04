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

    /**
     * 6자리 종목코드의 현재가/등락/거래량/고저를 조회해 정규화 Quote로 반환.
     *
     * 초당 한도를 넘으면 한투가 일부 요청을 rt_cd != "0" 으로 거부한다(관심종목 다건 조회 시 흔함).
     * 동시성 제한(Semaphore)만으론 부족해, 거부당하면 점증 백오프로 재시도해 자동 복구한다.
     */
    suspend fun getPrice(code: String): Quote {
        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            // withPermit: 동시에 최대 permits 개만 한투를 호출(나머지는 대기) → 폭주로 인한 한도 초과 완화.
            val resp = rateLimiter.withPermit { requestPrice(code, accessToken) }
            val o = resp.output
            // 한투는 HTTP 200이어도 본문 rt_cd 로 성패를 알린다("0"이 성공).
            if (resp.rtCd == "0" && o != null) {
                return o.toQuote(code)
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
        val accessToken = token()
        var lastMsg = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            val resp = rateLimiter.withPermit { requestInvestor(code, accessToken) }
            if (resp.rtCd == "0") {
                // 한투는 최신 행에 "당일"을 주는데 장 마감 전이면 전부 0(미확정)으로 온다.
                // CLAUDE.md 원칙대로 확정 일별값만 쓴다 → 외인·기관·개인이 모두 0인 행은 제외하고 N일.
                return resp.output
                    .map { it.toInvestorFlow() }
                    .filter { it.foreign != 0L || it.institution != 0L || it.individual != 0L }
                    .take(days)
            }
            lastMsg = resp.msg1.ifBlank { "rt_cd=${resp.rtCd}" }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS * (attempt + 1))
        }
        throw KisException("한투 수급 조회 실패($code): $lastMsg")
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

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_MS = 250L
    }
}

// 한투 원본(문자열) → 우리 Quote 로 변환.
// 주의: prdy_vrss(전일대비)·prdy_ctrt(등락률)는 이미 부호 포함("-192000","-9.58") → 부호 재적용 금지.
private fun KisPriceOutput.toQuote(code: String) = Quote(
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
)

// 한투 원본 수급 행 → 우리 InvestorFlow. 순매수 수량은 이미 부호 포함이라 그대로 파싱.
private fun KisInvestorRow.toInvestorFlow() = InvestorFlow(
    date = date,
    foreign = foreign.toLongSafe(),
    institution = institution.toLongSafe(),
    individual = individual.toLongSafe(),
)

// 한투 값은 문자열이고 가끔 빈 문자열이 오기도 해서, 파싱 실패 시 0으로 안전 처리한다.
private fun String.toLongSafe(): Long = trim().toLongOrNull() ?: 0L
private fun String.toDoubleSafe(): Double = trim().toDoubleOrNull() ?: 0.0

/** 토큰 파일 캐시 형식. expiryMs = 만료 시각(epoch millis). */
@Serializable
private data class TokenCache(val token: String, val expiryMs: Long)
