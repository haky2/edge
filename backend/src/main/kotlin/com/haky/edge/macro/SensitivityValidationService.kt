package com.haky.edge.macro

import com.haky.edge.ai.DailyHistoryService
import com.haky.edge.macro.MacroImpactService.MacroGroup
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

// ── 리포트 DTO ─────────────────────────────────────────────────────────

/** SENSITIVITY 1칸(그룹×지표)의 실측 결과. */
@Serializable
data class SensitivityFinding(
    val group: String,          // MacroGroup 이름
    val indicatorKey: String,   // usdkrw, nasdaq, ...
    val expectedDir: Int,       // 테이블의 방향(+1/-1)
    val note: String,           // 테이블의 근거 한 줄
    val timing: String,         // KR_CONCURRENT / US_SESSION
    val lagUsed: Int,           // 본선 판정에 쓴 lag (0=동일일, 1=다음 한국 거래일)
    val nAll: Int,              // 전체 페어 수
    val nFiltered: Int,         // 노이즈 필터(중앙값 미만 제외) 후 페어 수
    val agreeRate: Double,      // 부호 일치율 (필터 후, 바스켓 0% 제외)
    val r: Double,              // Pearson(지표등락×기대부호, 바스켓수익) — 양수면 테이블 지지
    val t: Double,              // r의 t-통계량
    val altLagAgree: Double,    // 반대 lag축 일치율(참고)
    val altLagR: Double,        // 반대 lag축 상관(참고)
    val verdict: String,        // SUPPORTED / CONTRADICTED / INCONCLUSIVE / INSUFFICIENT
)

@Serializable
data class SensitivityValidationReport(
    val generatedAt: String,
    val periodStart: String,
    val periodEnd: String,
    val baskets: Map<String, List<String>>,   // 그룹 → 실제 데이터 확보된 종목
    val findings: List<SensitivityFinding>,
    val textReport: String,                   // 사람이 읽는 요약(콘솔·Slack 붙여넣기용)
)

/**
 * SENSITIVITY 테이블 실증 검증(D1) — 운영 기능이 아니라 1회성 검증 리포트.
 *
 * 방법: 지표 일별 등락 이력(Yahoo 2년치 + ECOS 국고채) × 그룹 대표 바스켓의 일수익률
 * (DailyHistoryService 재사용, 동일가중 평균)을 페어링해 부호 일치율·상관·표본수를 잰다.
 *
 * 정렬(핵심): 지표를 시간대로 나눈다.
 *  - KR_CONCURRENT(usdkrw·rate3y): KOSPI와 동시간대에 움직임 → 앱이 보여주는 "오늘 환율 → 오늘 영향"은
 *    동시 관계이므로 lag0(동일 거래일)이 본선.
 *  - US_SESSION(nasdaq·crude·copper·usdjpy): 미국 세션 날짜 D의 등락이 다음 한국 거래일에 반영
 *    (아침 브리핑의 "간밤 나스닥 → 오늘 코스피") → lag1(D보다 뒤 첫 한국 거래일)이 본선.
 *  반대 축은 참고로 함께 계산해 리포트에 담는다.
 *
 * 노이즈 필터: 지표별 |등락률| < 자기 중앙값 제거 — 지표 간 변동성 차이(FX ~0.3% vs 유가 ~2%)를
 * 자기 보정으로 흡수한다. 부호 일치율은 필터 후, 상관은 전체 페어로.
 *
 * 판정(부호 반전은 비용이 커서 반증은 이중 조건):
 *  - SUPPORTED: 일치율 ≥ 54% && r > 0, 또는 t ≥ 2.0 (n≈240이면 이항 se≈3.2%, 54%는 +1.25σ 수준.
 *    상관 t≥2와 OR라 단독으론 관대하지만 방향 일관성(r>0)을 함께 요구)
 *  - CONTRADICTED: 일치율 ≤ 46% && t ≤ −2.0 — 일치율과 유의 상관이 모두 반대일 때만.
 *  - INCONCLUSIVE: 나머지 — 테이블 유지(구조 논리 근거는 살리되 "실측 무근거"를 기록).
 *    제거까지 가지 않는 이유: 신호 감소 트레이드오프 + 매크로 민감도는 국면 의존이라
 *    2년 표본의 무유의가 관계 부재의 증명이 아님.
 */
class SensitivityValidationService(
    private val dailyHistory: DailyHistoryService,
    private val ecosApiKey: String,
) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    suspend fun validate(): SensitivityValidationReport {
        // 1) 지표 이력 수집 — 지표별 (UTC 날짜, 전일 대비 %)
        val indicatorSeries = mutableMapOf<String, List<Pair<LocalDate, Double>>>()
        for (spec in INDICATOR_SPECS) {
            val series = runCatching {
                if (spec.yahooSymbol != null) fetchYahooHistory(spec.yahooSymbol)
                else fetchEcosHistory()
            }.getOrElse { e ->
                println("[SensitivityValidation] ${spec.key} 이력 실패: ${e.message}")
                emptyList()
            }
            if (series.size >= MIN_N) indicatorSeries[spec.key] = series
            else println("[SensitivityValidation] ${spec.key} 표본 부족(${series.size}) — 제외")
        }

        // 2) 그룹 바스켓 일수익률 — 종목별 500거래일 일봉(캐시 재사용) → 동일가중 평균
        val basketReturns = mutableMapOf<MacroGroup, Map<LocalDate, Double>>()
        val basketCodes = mutableMapOf<String, List<String>>()
        for ((group, codes) in VALIDATION_BASKETS) {
            val perStock = codes.mapNotNull { code ->
                runCatching {
                    val bars = dailyHistory.getHistory(code)
                    if (bars.size < 100) null else code to dailyReturns(bars.map { it.date to it.close })
                }.getOrElse {
                    println("[SensitivityValidation] $group/$code 일봉 실패: ${it.message}")
                    null
                }
            }
            if (perStock.size < 2) {
                println("[SensitivityValidation] $group 바스켓 종목 부족(${perStock.size}) — 제외")
                continue
            }
            basketReturns[group] = averageReturns(perStock.map { it.second })
            basketCodes[group.name] = perStock.map { it.first }
        }

        // 3) 테이블 26칸 각각 페어링 → 통계 → 판정
        val findings = mutableListOf<SensitivityFinding>()
        for ((group, sensList) in MacroImpactService.SENSITIVITY) {
            val basket = basketReturns[group] ?: continue
            val basketDates = basket.keys.sorted()
            for (sens in sensList) {
                val spec = INDICATOR_SPECS.firstOrNull { it.key == sens.indicatorKey } ?: continue
                val series = indicatorSeries[sens.indicatorKey] ?: continue

                val mainLag = if (spec.timing == Timing.KR_CONCURRENT) 0 else 1
                val mainPairs = pairSeries(series, basket, basketDates, mainLag)
                val altPairs = pairSeries(series, basket, basketDates, 1 - mainLag)
                val main = computeStats(mainPairs, sens.direction)
                val alt = computeStats(altPairs, sens.direction)

                findings += SensitivityFinding(
                    group = group.name,
                    indicatorKey = sens.indicatorKey,
                    expectedDir = sens.direction,
                    note = sens.note,
                    timing = spec.timing.name,
                    lagUsed = mainLag,
                    nAll = main.nAll,
                    nFiltered = main.nFiltered,
                    agreeRate = main.agreeRate.round3(),
                    r = main.r.round3(),
                    t = main.t.round2(),
                    altLagAgree = alt.agreeRate.round3(),
                    altLagR = alt.r.round3(),
                    verdict = verdictOf(main),
                )
            }
        }

        val allDates = indicatorSeries.values.flatten().map { it.first }
        val report = SensitivityValidationReport(
            generatedAt = LocalDate.now(ZoneOffset.UTC).toString(),
            periodStart = allDates.minOrNull()?.toString() ?: "-",
            periodEnd = allDates.maxOrNull()?.toString() ?: "-",
            baskets = basketCodes,
            findings = findings,
            textReport = renderText(findings, basketCodes),
        )
        println(report.textReport)
        return report
    }

    // ── 외부 이력 수집 ────────────────────────────────────────────────

    /** Yahoo chart 2년 일봉 → (UTC 날짜, 전일 대비 %). 최신 봉은 장중 미확정일 수 있어 제외. */
    private suspend fun fetchYahooHistory(symbol: String): List<Pair<LocalDate, Double>> {
        val resp: YahooHistResponse = http.get(
            "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1d&range=2y"
        ) {
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
        }.body()
        val result = resp.chart.results.firstOrNull() ?: error("$symbol 데이터 없음")
        val timestamps = result.timestamps
        val closes = result.indicators.quote.firstOrNull()?.close ?: emptyList()
        val bars = timestamps.zip(closes)
            .mapNotNull { (ts, c) -> if (c == null || c <= 0.0) null else ts to c }
            .sortedBy { it.first }
            .dropLast(1)   // 마지막 봉은 진행 중일 수 있음
        return toChangeSeries(bars.map { (ts, c) ->
            Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate() to c
        })
    }

    /** ECOS 국고채 3년 일별 이력 2년 → (날짜, 전일 대비 %). */
    private suspend fun fetchEcosHistory(): List<Pair<LocalDate, Double>> {
        if (ecosApiKey.isBlank()) error("ECOS_API_KEY 없음")
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
        val end = LocalDate.now(ZoneOffset.UTC)
        val start = end.minusYears(2)
        val url = "https://ecos.bok.or.kr/api/StatisticSearch/$ecosApiKey/json/kr/1/600/817Y002/D/" +
            "${start.format(fmt)}/${end.format(fmt)}/010300000"
        val resp: EcosHistResponse = http.get(url).body()
        val rows = resp.search?.rows
            ?.mapNotNull { row ->
                val v = row.value.toDoubleOrNull() ?: return@mapNotNull null
                val d = runCatching { LocalDate.parse(row.time, fmt) }.getOrNull() ?: return@mapNotNull null
                d to v
            }
            ?.sortedBy { it.first }
            ?: error("ECOS 데이터 없음")
        return toChangeSeries(rows)
    }

    private fun Double.round2() = kotlin.math.round(this * 100) / 100
    private fun Double.round3() = kotlin.math.round(this * 1000) / 1000

    // ── 지표 정의 ────────────────────────────────────────────────────

    internal enum class Timing { KR_CONCURRENT, US_SESSION }

    private data class IndicatorSpec(val key: String, val yahooSymbol: String?, val timing: Timing)

    companion object {
        internal const val MIN_N = 80

        // 지표 → 이력 소스·시간대. usdkrw의 Yahoo FX 봉은 UTC 자정 기준이라 UTC 날짜 D ≈ 한국 D일 장중(근사).
        // usdjpy는 24시간 거래지만 뉴욕 마감 관행 봉 → 미국형으로 근사(리포트에 명시).
        private val INDICATOR_SPECS = listOf(
            IndicatorSpec("usdkrw", "KRW=X",  Timing.KR_CONCURRENT),
            IndicatorSpec("rate3y", null,      Timing.KR_CONCURRENT),   // ECOS
            IndicatorSpec("nasdaq", "^IXIC",  Timing.US_SESSION),
            IndicatorSpec("usdjpy", "JPY=X",  Timing.US_SESSION),
            IndicatorSpec("crude",  "CL=F",   Timing.US_SESSION),
            IndicatorSpec("copper", "HG=F",   Timing.US_SESSION),
        )

        // 그룹 대표 바스켓. SECTOR_PEERS(PeerValuationService)의 코드 재사용 + 빈 그룹 보강.
        // 반도체: peer 바스켓 없음(국내 2개뿐) → 삼성전자·하이닉스가 곧 섹터.
        // 전자: 바스켓 없음 → LG전자·삼성전기·LG이노텍.
        internal val VALIDATION_BASKETS = mapOf(
            MacroGroup.SEMICONDUCTOR to listOf("005930", "000660"),
            MacroGroup.TECH_GROWTH   to listOf("018260", "307950", "022100", "035420", "035720"),
            MacroGroup.AUTOMOBILE    to listOf("005380", "000270", "012330"),
            MacroGroup.SHIPBUILDING  to listOf("329180", "010140", "042660", "009540"),
            MacroGroup.DEFENSE       to listOf("012450", "047810", "079550", "064350"),
            MacroGroup.POWER_EQUIP   to listOf("267260", "010120", "298040", "062040", "001440"),
            MacroGroup.ELECTRONICS   to listOf("066570", "009150", "011070"),
        )

        /** (날짜, 값) 시계열 → (날짜, 전일 대비 %) — 첫 항목은 기준일이라 제외. */
        internal fun toChangeSeries(values: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> =
            values.zipWithNext { (_, prev), (date, cur) ->
                date to (if (prev > 0.0) (cur - prev) / prev * 100.0 else 0.0)
            }

        /** 일봉(date "YYYYMMDD", close — 최신이 앞) → 날짜 오름차순 (날짜, 일수익률 %). */
        internal fun dailyReturns(bars: List<Pair<String, Long>>): Map<LocalDate, Double> {
            val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
            val sorted = bars
                .mapNotNull { (d, c) ->
                    val date = runCatching { LocalDate.parse(d, fmt) }.getOrNull() ?: return@mapNotNull null
                    if (c <= 0L) null else date to c
                }
                .sortedBy { it.first }
            return sorted.zipWithNext { (_, prev), (date, cur) ->
                date to (cur - prev).toDouble() / prev * 100.0
            }.toMap()
        }

        /** 종목별 수익률 맵 → 날짜별 동일가중 평균. 종목 절반 이상 존재하는 날만(상장일 차이 방어). */
        internal fun averageReturns(perStock: List<Map<LocalDate, Double>>): Map<LocalDate, Double> {
            val minCount = (perStock.size + 1) / 2
            return perStock.flatMap { it.entries }
                .groupBy({ it.key }, { it.value })
                .filterValues { it.size >= minCount }
                .mapValues { it.value.average() }
        }

        /**
         * 지표 시계열 × 바스켓 수익률 페어링.
         * lag=0: 동일 날짜. lag=1: 지표 날짜보다 뒤 첫 바스켓 거래일(갭 ≤ 7일 — 연휴 초과 방지).
         */
        internal fun pairSeries(
            indicator: List<Pair<LocalDate, Double>>,
            basket: Map<LocalDate, Double>,
            basketDatesSorted: List<LocalDate>,
            lag: Int,
        ): List<Pair<Double, Double>> = indicator.mapNotNull { (date, chg) ->
            val basketDate = if (lag == 0) {
                date.takeIf { basket.containsKey(it) }
            } else {
                // 이진 탐색으로 date보다 큰 첫 거래일
                val idx = basketDatesSorted.binarySearch(date).let { if (it >= 0) it + 1 else -(it + 1) }
                basketDatesSorted.getOrNull(idx)?.takeIf {
                    java.time.temporal.ChronoUnit.DAYS.between(date, it) <= 7
                }
            } ?: return@mapNotNull null
            chg to basket.getValue(basketDate)
        }

        internal data class CellStats(
            val nAll: Int,
            val nFiltered: Int,
            val agreeRate: Double,
            val r: Double,
            val t: Double,
        )

        /**
         * 페어 통계. x축은 지표등락×기대부호로 뒤집어 "r>0 = 테이블 방향 지지"로 통일.
         * 부호 일치율: |지표등락| ≥ 자기 중앙값(노이즈 컷) && 바스켓 ≠ 0% 페어에서
         * sign(바스켓) == sign(지표×기대부호) 비율.
         */
        internal fun computeStats(pairs: List<Pair<Double, Double>>, expectedDir: Int): CellStats {
            if (pairs.isEmpty()) return CellStats(0, 0, 0.0, 0.0, 0.0)
            val xs = pairs.map { it.first * expectedDir }
            val ys = pairs.map { it.second }
            val r = pearson(xs, ys)
            val n = pairs.size
            // |r|=1(완벽 상관)이면 t는 발산 — 부호 유지한 큰 값으로 대체(0으로 두면 "유의하지 않음"으로 오판).
            val t = when {
                n <= 2 -> 0.0
                abs(r) >= 1.0 -> r * 1e6
                else -> r * sqrt((n - 2).toDouble()) / sqrt(1 - r * r)
            }

            val absMoves = pairs.map { abs(it.first) }.sorted()
            val median = absMoves[absMoves.size / 2].takeIf { it > 0.0 } ?: Double.MIN_VALUE
            val filtered = pairs.filter { abs(it.first) >= median && it.first != 0.0 && it.second != 0.0 }
            val agree = if (filtered.isEmpty()) 0.0
            else filtered.count { (x, y) ->
                val expected = if (x > 0) expectedDir else -expectedDir
                (y > 0 && expected > 0) || (y < 0 && expected < 0)
            }.toDouble() / filtered.size

            return CellStats(nAll = n, nFiltered = filtered.size, agreeRate = agree, r = r, t = t)
        }

        internal fun verdictOf(s: CellStats): String = when {
            s.nFiltered < MIN_N -> "INSUFFICIENT"
            (s.agreeRate >= 0.54 && s.r > 0) || s.t >= 2.0 -> "SUPPORTED"
            s.agreeRate <= 0.46 && s.t <= -2.0 -> "CONTRADICTED"
            else -> "INCONCLUSIVE"
        }

        internal fun pearson(xs: List<Double>, ys: List<Double>): Double {
            val n = xs.size
            if (n < 2) return 0.0
            val mx = xs.average()
            val my = ys.average()
            val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
            val dx = sqrt(xs.sumOf { (it - mx).pow(2) })
            val dy = sqrt(ys.sumOf { (it - my).pow(2) })
            val denom = dx * dy
            return if (denom < 1e-10) 0.0 else (num / denom).coerceIn(-1.0, 1.0)
        }

        internal fun renderText(findings: List<SensitivityFinding>, baskets: Map<String, List<String>>): String = buildString {
            appendLine("═══ SENSITIVITY 실증 검증 리포트 ═══")
            appendLine("바스켓: " + baskets.entries.joinToString("; ") { "${it.key}=${it.value.size}종목" })
            appendLine()
            val byVerdict = findings.groupBy { it.verdict }
            appendLine("판정 요약: SUPPORTED=${byVerdict["SUPPORTED"]?.size ?: 0} " +
                "INCONCLUSIVE=${byVerdict["INCONCLUSIVE"]?.size ?: 0} " +
                "CONTRADICTED=${byVerdict["CONTRADICTED"]?.size ?: 0} " +
                "INSUFFICIENT=${byVerdict["INSUFFICIENT"]?.size ?: 0}")
            appendLine()
            for (f in findings.sortedWith(compareBy({ it.group }, { it.indicatorKey }))) {
                val dirStr = if (f.expectedDir > 0) "+1" else "-1"
                val lagStr = if (f.lagUsed == 0) "동일일" else "익일"
                appendLine("[${f.verdict}] ${f.group} × ${f.indicatorKey} (기대 $dirStr, $lagStr 본선)")
                appendLine("    일치율 ${"%.1f".format(f.agreeRate * 100)}% (n=${f.nFiltered}/${f.nAll}) · " +
                    "r=${"%.3f".format(f.r)} t=${"%.2f".format(f.t)} · " +
                    "반대축: 일치 ${"%.1f".format(f.altLagAgree * 100)}% r=${"%.3f".format(f.altLagR)}")
            }
        }
    }
}

// ── Yahoo/ECOS 이력 응답 모델(timestamp 포함 — YahooMacroClient 모델엔 없어 별도 정의) ──

@Serializable
private data class YahooHistResponse(val chart: YahooHistChart)

@Serializable
private data class YahooHistChart(@SerialName("result") val results: List<YahooHistResult> = emptyList())

@Serializable
private data class YahooHistResult(
    @SerialName("timestamp") val timestamps: List<Long> = emptyList(),
    val indicators: YahooHistIndicators = YahooHistIndicators(),
)

@Serializable
private data class YahooHistIndicators(val quote: List<YahooHistQuote> = emptyList())

@Serializable
private data class YahooHistQuote(val close: List<Double?> = emptyList())

@Serializable
private data class EcosHistResponse(
    @SerialName("StatisticSearch") val search: EcosHistSearch? = null,
)

@Serializable
private data class EcosHistSearch(@SerialName("row") val rows: List<EcosHistRow> = emptyList())

@Serializable
private data class EcosHistRow(
    @SerialName("TIME") val time: String = "",
    @SerialName("DATA_VALUE") val value: String = "",
)
