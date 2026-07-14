package com.haky.edge.macro

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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Yahoo Finance 일봉 이력 공유 유틸 — 검증 라우트(discovery-validation, 향후 moodweight-validation)용.
 * SensitivityValidationService.fetchYahooHistory와 같은 엔드포인트·파싱(추출 판단은 ③ 때 —
 * 기검증 코드라 건드리지 않고 신규 수요만 여기로).
 *
 * zone: 봉 timestamp → 날짜 변환 기준. 미국 지표는 UTC, ^KS11(코스피)은 Asia/Seoul
 * (KRX 09:00 KST 개장봉이 UTC로는 전일 밤이 될 수 있어 시장 로컬 날짜로 맞춘다).
 */
class YahooHistoryClient {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /** (날짜, 종가) 오름차순. 마지막 봉은 장중 미확정일 수 있어 제외. */
    suspend fun dailyCloses(
        symbol: String,
        range: String = "2y",
        zone: ZoneId = ZoneOffset.UTC,
    ): List<Pair<LocalDate, Double>> {
        val resp: YahooDailyResponse = http.get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=$range"
        ) {
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
        }.body()
        val result = resp.chart.results.firstOrNull() ?: error("$symbol 데이터 없음")
        val closes = result.indicators.quote.firstOrNull()?.close ?: emptyList()
        return result.timestamps.zip(closes)
            .mapNotNull { (ts, c) -> if (c == null || c <= 0.0) null else ts to c }
            .sortedBy { it.first }
            .dropLast(1)
            .map { (ts, c) -> Instant.ofEpochSecond(ts).atZone(zone).toLocalDate() to c }
    }
}

@Serializable
private data class YahooDailyResponse(val chart: YahooDailyChart)

@Serializable
private data class YahooDailyChart(@SerialName("result") val results: List<YahooDailyResult> = emptyList())

@Serializable
private data class YahooDailyResult(
    @SerialName("timestamp") val timestamps: List<Long> = emptyList(),
    val indicators: YahooDailyIndicators = YahooDailyIndicators(),
)

@Serializable
private data class YahooDailyIndicators(val quote: List<YahooDailyQuote> = emptyList())

@Serializable
private data class YahooDailyQuote(val close: List<Double?> = emptyList())
