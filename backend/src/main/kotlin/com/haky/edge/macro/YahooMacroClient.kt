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
 * 미 10년물 국채금리(^TNX) + 달러인덱스(DX-Y.NYB) — Yahoo Finance 비공개 API 사용.
 * KIS Open API가 미지원 지표라 Yahoo Finance로 우회한다 (CopperClient와 동일 패턴).
 * 30분 인메모리 캐시. 개별 실패 시 해당 지표만 누락, 나머지는 유지.
 */
class YahooMacroClient {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private data class Cached(val indicators: List<MacroIndicator>, val expiryMs: Long)
    private val cached = AtomicReference<Cached?>(null)

    suspend fun get(): List<MacroIndicator> {
        cached.get()?.takeIf { System.currentTimeMillis() < it.expiryMs }?.let { return it.indicators }
        return runCatching { fetchAll() }.getOrElse { emptyList() }.also { result ->
            if (result.isNotEmpty()) cached.set(Cached(result, System.currentTimeMillis() + 30 * 60_000L))
        }
    }

    private suspend fun fetchAll(): List<MacroIndicator> = listOfNotNull(
        runCatching { fetchOne("^TNX",       "tnx", "미10년물") }.getOrNull(),
        runCatching { fetchOne("DX-Y.NYB",   "dxy", "달러인덱스") }.getOrNull(),
        runCatching { fetchOne("EWY",        "ewy", "EWY 한국ETF") }.getOrNull(),
        runCatching { fetchOne("^SOX",       "sox", "필라델피아반도체") }.getOrNull(),
        runCatching { fetchOne("^RUT",       "rut", "러셀2000") }.getOrNull(),
    )

    private suspend fun fetchOne(symbol: String, key: String, label: String): MacroIndicator {
        val resp: YahooResponse = http.get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=5d"
        ) {
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
        }.body()

        val result = resp.chart.results.firstOrNull() ?: error("$label 데이터 없음")
        val meta = result.meta
        val price = meta.regularMarketPrice

        // chartPreviousClose = range 시작 전날 종가(5d면 5거래일 전). 전일 대비 계산에 쓰면 틀린다.
        // 일봉 closes 배열의 끝에서 2번째 값이 직전 거래일 종가 → 이걸 prev로 쓴다.
        // 배열이 1개 이하면 chartPreviousClose로 폴백.
        val closes = result.indicators.quote.firstOrNull()?.close?.filterNotNull() ?: emptyList()
        val prev = if (closes.size >= 2) closes[closes.size - 2]
                   else meta.chartPreviousClose.takeIf { it > 0.0 } ?: price

        val change = price - prev
        val changeRate = if (prev > 0.0) change / prev * 100.0 else 0.0

        return MacroIndicator(key = key, label = label, value = price, change = change, changeRate = changeRate)
    }
}

@Serializable
private data class YahooResponse(val chart: YChart)

@Serializable
private data class YChart(@SerialName("result") val results: List<YResult> = emptyList())

@Serializable
private data class YResult(val meta: YMeta, val indicators: YIndicators = YIndicators())

@Serializable
private data class YMeta(
    val regularMarketPrice: Double = 0.0,
    val chartPreviousClose: Double = 0.0,
)

@Serializable
private data class YIndicators(val quote: List<YQuote> = emptyList())

@Serializable
private data class YQuote(val close: List<Double?> = emptyList())
