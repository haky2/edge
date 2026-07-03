package com.haky.edge.macro

import com.haky.edge.kis.MacroIndicator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * COMEX 구리선물(HG=F) 가격 클라이언트 — Yahoo Finance 비공개 API 사용.
 * KIS Open API는 구리(HG) 종목 데이터를 제공하지 않아 Yahoo Finance로 우회한다.
 * 30분 인메모리 캐시. 실패 시 null 반환(섹션 통째로 죽지 않게).
 */
class CopperClient {
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
        val resp: YahooChartResponse = http.get(
            "https://query1.finance.yahoo.com/v8/finance/chart/HG=F?interval=1d&range=5d"
        ) {
            // Yahoo Finance 봇 차단 회피용 기본 브라우저 헤더
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
        }.body()

        val result = resp.chart.results.firstOrNull() ?: error("구리 데이터 없음")
        val meta = result.meta
        val price = meta.regularMarketPrice

        // chartPreviousClose = range(5d) 시작 전날 종가 = 5거래일 전 값이라 "전일 대비"에 쓰면 틀린다
        // (YahooMacroClient와 동일 수정 — 2026-07 감사 M1: 구리 등락률이 5일 누적으로 계산되던 버그).
        // 일봉 closes 끝에서 2번째가 직전 거래일 종가. 배열이 1개 이하면 chartPreviousClose 폴백.
        val closes = result.indicators.quote.firstOrNull()?.close?.filterNotNull() ?: emptyList()
        val prev = if (closes.size >= 2) closes[closes.size - 2]
                   else meta.chartPreviousClose.takeIf { it > 0.0 } ?: price
        val change = price - prev
        val changeRate = if (prev > 0.0) change / prev * 100.0 else 0.0

        return MacroIndicator(
            key = "copper",
            label = "구리",
            value = price,
            change = change,
            changeRate = changeRate,
        )
    }
}

@Serializable
private data class YahooChartResponse(
    val chart: YahooChart,
)

@Serializable
private data class YahooChart(
    @SerialName("result") val results: List<YahooResult> = emptyList(),
)

@Serializable
private data class YahooResult(
    val meta: YahooMeta,
    val indicators: YahooIndicators = YahooIndicators(),
)

@Serializable
private data class YahooIndicators(val quote: List<YahooQuoteBlock> = emptyList())

@Serializable
private data class YahooQuoteBlock(val close: List<Double?> = emptyList())

@Serializable
private data class YahooMeta(
    val regularMarketPrice: Double = 0.0,
    val chartPreviousClose: Double = 0.0,
)
