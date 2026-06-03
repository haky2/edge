package com.stockapp.kis

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 한투(KIS) Open API 클라이언트.
 * - OAuth 접근토큰을 메모리에 캐시(만료 1분 전까지 재사용) — 한투 토큰 발급은 분당 제한이 있어 캐시 필수.
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

    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0

    private suspend fun token(): String {
        cachedToken?.let { if (System.currentTimeMillis() < tokenExpiryMs) return it }
        return tokenMutex.withLock {
            cachedToken?.let { if (System.currentTimeMillis() < tokenExpiryMs) return it }
            if (appKey.isBlank() || appSecret.isBlank()) {
                throw KisException("KIS_APP_KEY / KIS_APP_SECRET 가 설정되지 않았습니다 (.env 확인)")
            }
            val resp: KisTokenResponse = http.post("$baseUrl/oauth2/tokenP") {
                contentType(ContentType.Application.Json)
                setBody(KisTokenRequest(appkey = appKey, appsecret = appSecret))
            }.body()
            if (resp.accessToken.isBlank()) {
                throw KisException("토큰 발급 실패: ${resp.errorCode} ${resp.errorDescription}".trim())
            }
            cachedToken = resp.accessToken
            tokenExpiryMs = System.currentTimeMillis() + (resp.expiresIn - 60).coerceAtLeast(60) * 1000
            resp.accessToken
        }
    }

    /** 6자리 종목코드의 현재가/등락/거래량/고저를 조회해 정규화 Quote로 반환. */
    suspend fun getPrice(code: String): Quote {
        val accessToken = token()
        val resp: KisPriceResponse =
            http.get("$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price") {
                header("authorization", "Bearer $accessToken")
                header("appkey", appKey)
                header("appsecret", appSecret)
                header("tr_id", "FHKST01010100")
                header("custtype", "P")
                parameter("FID_COND_MRKT_DIV_CODE", "J")
                parameter("FID_INPUT_ISCD", code)
            }.body()

        val o = resp.output
        if (resp.rtCd != "0" || o == null) {
            throw KisException(resp.msg1.ifBlank { "한투 조회 실패 (rt_cd=${resp.rtCd})" })
        }
        return Quote(
            code = code,
            price = o.price.toLongSafe(),
            change = o.change.toLongSafe(), // 한투가 이미 부호 포함
            changeRate = o.changeRate.toDoubleSafe(),
            volume = o.volume.toLongSafe(),
            open = o.open.toLongSafe(),
            high = o.high.toLongSafe(),
            low = o.low.toLongSafe(),
            high52w = o.high52w.toLongSafe(),
            low52w = o.low52w.toLongSafe(),
        )
    }
}

private fun String.toLongSafe(): Long = trim().toLongOrNull() ?: 0L
private fun String.toDoubleSafe(): Double = trim().toDoubleOrNull() ?: 0.0
