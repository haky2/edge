package com.haky.edge.macro

import com.haky.edge.kis.MacroIndicator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * CNN Fear & Greed Index 클라이언트.
 * https://production.dataviz.cnn.io/index/fearandgreed/graphdata — 공개 엔드포인트(인증 불필요).
 * 30분 인메모리 캐시: CNN은 장중에 주기적으로 갱신되므로 매 요청마다 호출하지 않는다.
 * 실패하면 null을 반환 — /macro 섹션이 F&G 없이도 살아있어야 하므로 예외를 전파하지 않는다.
 */
class FearGreedClient {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private data class Cached(val indicator: MacroIndicator, val expiryMs: Long)
    private val cached = AtomicReference<Cached?>(null)

    suspend fun get(): MacroIndicator? {
        cached.get()?.takeIf { System.currentTimeMillis() < it.expiryMs }?.let { return it.indicator }
        return runCatching { fetch() }.getOrNull()?.also {
            cached.set(Cached(it, System.currentTimeMillis() + 30 * 60_000L))
        }
    }

    private suspend fun fetch(): MacroIndicator {
        val resp: FearGreedResponse = http.get("https://production.dataviz.cnn.io/index/fearandgreed/graphdata") {
            // CNN이 봇 차단을 하므로 브라우저와 동일한 필수 헤더를 보낸다.
            headers.append("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            headers.append("Accept-Language", "en-US,en;q=0.9")
            headers.append("Referer", "https://www.cnn.com/markets/fear-and-greed")
        }.body()
        val fg = resp.fearAndGreed
        val prev = fg.previousClose ?: fg.score
        val change = fg.score - prev
        val changeRate = if (prev > 0.0) change / prev * 100.0 else 0.0
        return MacroIndicator(
            key = "fear_greed",
            label = "공포탐욕지수",
            value = fg.score,
            change = change,
            changeRate = changeRate,
            tag = ratingKo(fg.rating),
        )
    }

    private fun ratingKo(r: String) = when (r.lowercase().trim()) {
        "extreme fear" -> "극단적 공포"
        "fear" -> "공포"
        "neutral" -> "중립"
        "greed" -> "탐욕"
        "extreme greed" -> "극단적 탐욕"
        else -> r
    }
}

@Serializable
private data class FearGreedResponse(
    @SerialName("fear_and_greed") val fearAndGreed: FearGreedData,
)

@Serializable
private data class FearGreedData(
    val score: Double = 0.0,
    val rating: String = "",
    @SerialName("previous_close") val previousClose: Double? = null,
)
