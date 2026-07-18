package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.IndexPoint
import com.haky.edge.kis.KisClient
import com.haky.edge.master.StockMaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

// ── 입력 ──────────────────────────────────────────────────────────────────

/** 보유 1건 — 리스크 계산은 수량만 필요(비중 = 최근 확정 종가 × 수량). */
data class RiskHolding(val code: String, val name: String, val qty: Long)

// ── 출력 DTO ──────────────────────────────────────────────────────────────

/** 종목 1개의 리스크 스냅샷. riskContribPct가 핵심 — 비중과 리스크 기여는 다르다. */
@Serializable
data class RiskStock(
    val code: String,
    val name: String,
    val weightPct: Double,        // 평가 비중(%)
    val volPct: Double,           // 연환산 변동성(%, 60거래일 실측)
    val beta: Double? = null,     // vs KOSPI(같은 창). 관측 부족 시 null
    val riskContribPct: Double,   // 포트폴리오 분산 기여(%) — 합계 100
)

/** 높은 상관 쌍 1개(r ≥ 0.7). */
@Serializable
data class CorrPair(val nameA: String, val nameB: String, val corr: Double)

/** 상관 클러스터 — r ≥ 0.7 간선의 연결성분("한 방에 같이 움직이는 묶음"). */
@Serializable
data class RiskCluster(val names: List<String>, val weightPct: Double, val avgCorr: Double)

/**
 * POST /portfolio-risk — 실측 상관 기반 포트폴리오 리스크 스냅샷. LLM 0, 전부 계산.
 * 섹터 라벨 기반 추정(portfolio-review의 공통 노출)과 달리 실제 일봉 수익률 상관을 쓴다.
 */
@Serializable
data class PortfolioRisk(
    val date: String,
    val windowDays: Int,               // 수익률 관측 창(거래일)
    val stocks: List<RiskStock> = emptyList(),
    val portfolioVolPct: Double,       // 연환산 포트폴리오 변동성(%)
    val weightedAvgVolPct: Double,     // 비중 가중 개별 변동성 평균(%) — 분산효과 없을 때의 변동성
    val diversificationRatio: Double,  // weightedAvgVol / portfolioVol (1=분산효과 없음, 클수록 효과 큼)
    val portfolioBeta: Double? = null, // vs KOSPI(비중 가중)
    val kospiVolPct: Double? = null,   // 참고: 같은 창의 코스피 변동성(%)
    val avgCorr: Double? = null,       // 평균 쌍상관(종목 2개 이상일 때)
    val topPairs: List<CorrPair> = emptyList(),
    val clusters: List<RiskCluster> = emptyList(),
    val hhi: Int,                      // 허핀달 지수(0~10000). 1500↓ 분산 / 2500↑ 집중(통념 기준)
    val top2WeightPct: Double,
    val excluded: List<String> = emptyList(),  // 관측 부족으로 제외된 종목명
    val caveat: String = "",
)

/**
 * 포트폴리오 리스크 엔진 — 신규 데이터 소스 0: 일봉(F1 캐시) × 코스피(0001)만 교차.
 *
 * 방법론:
 * - 최근 60거래일 로그수익률. 관측 40 미만 종목(신규 상장 등)은 제외하고 비중 재정규화(제외 사실 명시).
 * - 쌍 상관은 두 종목의 거래일 교집합 위에서 Pearson(거래정지일 결측 안전). 교집합까지 부족한
 *   드문 쌍은 전체 평균 상관으로 대체(0으로 두면 리스크 과소평가).
 * - 포트폴리오 변동성 = √(wᵀΣw), Σ는 쌍상관×개별변동성으로 조립. 쌍별 결측 처리 때문에
 *   Σ가 이론상 비양정치일 수 있어 분산은 0 하한 가드.
 * - 리스크 기여도 RC_i = w_i·(Σw)_i / σ²_p — "포트폴리오 리스크의 몇 %가 이 종목 몫인가".
 *   비중과 다른 값이 나오는 것이 이 지표의 존재 이유(고변동 소형 비중이 리스크를 지배할 수 있음).
 * - 베타는 종목·코스피 수익률 교집합 위 cov/var. 연환산은 ×√252.
 * - 비중은 최근 확정 종가 × 수량(수익률 계열과 같은 데이터, 시세 API 콜 0).
 *
 * LLM 0 — 순수 계산. 해석 주입(포트폴리오 진단 facts)은 후속 슬라이스에서 결정.
 * 캐시: (영업일 + code:qty 집합) — 일봉이 영업일 단위라 당일 내 불변.
 */
class PortfolioRiskService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val history: DailyHistoryService,
) {
    private val fileCache = FileCache("portfolio-risk", PortfolioRisk.serializer())

    suspend fun analyze(positions: Map<String, Long>): PortfolioRisk {
        require(positions.isNotEmpty()) { "보유 포지션이 비어 있습니다" }
        val today = effectiveMarketDate()
        val key = "$today|" + positions.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        fileCache.get(key)?.let { return it }

        val holdings = coroutineScope {
            positions.map { (code, qty) ->
                async {
                    val name = master.findByCode(code)?.name ?: code
                    val bars = runCatching { history.getHistory(code, minBars = WINDOW + 10) }
                        .getOrElse { emptyList() }
                    Triple(RiskHolding(code, name, qty), bars, Unit)
                }
            }.awaitAll()
        }
        val barsByCode = holdings.associate { it.first.code to it.second }
        val kospiAsc = fetchKospiAsc()

        val result = compute(holdings.map { it.first }, barsByCode, kospiAsc, today)
        fileCache.put(key, result)
        return result
    }

    /** 코스피 일별 종가(오래된 순) — 60거래일 수익률에 필요한 만큼(달력 ~160일)만 청크 병합. */
    private suspend fun fetchKospiAsc(): List<IndexPoint> {
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        var start = LocalDate.now(KST).minusDays(160)
        val end = LocalDate.now(KST)
        val merged = mutableMapOf<String, IndexPoint>()
        while (start <= end) {
            val chunkEnd = minOf(start.plusDays(89), end)
            runCatching {
                kis.getSectorIndexChartRange("0001", start.format(fmt), chunkEnd.format(fmt))
            }.getOrElse { emptyList() }.forEach { merged[it.date] = it }
            start = chunkEnd.plusDays(1)
        }
        return merged.values.sortedBy { it.date }
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        const val WINDOW = 60           // 수익률 관측 창(거래일)
        const val MIN_RETURNS = 40      // 이 미만 관측 종목·쌍은 신뢰 불가
        const val CORR_THRESHOLD = 0.7  // 클러스터 간선 기준
        private const val ANNUALIZE = 252.0

        /**
         * 순수 계산 함수 — 테스트 가능하도록 IO 없이 분리(코드베이스 관례).
         * barsByCode 순서 무관(내부 정렬), kospiAsc는 오래된 순.
         */
        internal fun compute(
            holdings: List<RiskHolding>,
            barsByCode: Map<String, List<DailyBar>>,
            kospiAsc: List<IndexPoint>,
            today: String,
        ): PortfolioRisk {
            // 종목별 수익률 계열(날짜→로그수익률, 최근 WINDOW개). 관측 부족은 제외.
            data class Series(
                val h: RiskHolding,
                val returns: Map<String, Double>,  // 날짜 오름차순 유지(LinkedHashMap)
                val dailyVol: Double,
                val value: Long,
            )

            val excluded = mutableListOf<String>()
            val series = holdings.mapNotNull { h ->
                val closes = barsByCode[h.code].orEmpty()
                    .sortedBy { it.date }.map { it.date to it.close }
                val rets = LinkedHashMap<String, Double>()
                for (i in 1 until closes.size) {
                    val prev = closes[i - 1].second
                    val cur = closes[i].second
                    if (prev > 0 && cur > 0) rets[closes[i].first] = ln(cur.toDouble() / prev)
                }
                val window = rets.entries.toList().takeLast(WINDOW)
                if (window.size < MIN_RETURNS) { excluded += h.name; return@mapNotNull null }
                val windowMap = LinkedHashMap<String, Double>().apply { window.forEach { put(it.key, it.value) } }
                Series(h, windowMap, sampleStd(window.map { it.value }), closes.last().second * h.qty)
            }
            require(series.isNotEmpty()) { "변동성 계산에 필요한 일봉 이력이 있는 종목이 없습니다" }

            val totalValue = series.sumOf { it.value }.coerceAtLeast(1)
            val w = series.map { it.value.toDouble() / totalValue }
            val n = series.size

            // 쌍 상관(교집합 날짜 위 Pearson). 교집합 부족 쌍은 null → 나중에 평균 상관으로 대체.
            val corr = Array(n) { DoubleArray(n) { Double.NaN } }
            val validCorrs = mutableListOf<Double>()
            for (i in 0 until n) {
                corr[i][i] = 1.0
                for (j in i + 1 until n) {
                    val common = series[i].returns.keys intersect series[j].returns.keys
                    if (common.size >= MIN_RETURNS) {
                        val a = common.sorted().map { series[i].returns.getValue(it) }
                        val b = common.sorted().map { series[j].returns.getValue(it) }
                        val r = pearson(a, b)
                        corr[i][j] = r; corr[j][i] = r
                        validCorrs += r
                    }
                }
            }
            val avgCorr = validCorrs.takeIf { it.isNotEmpty() }?.average()
            for (i in 0 until n) for (j in 0 until n) {
                if (corr[i][j].isNaN()) corr[i][j] = avgCorr ?: 0.0
            }

            // 포트폴리오 분산 = ΣΣ w_i w_j ρ_ij σ_i σ_j (일간). 비양정치 가드로 0 하한.
            val sigma = series.map { it.dailyVol }
            var varP = 0.0
            for (i in 0 until n) for (j in 0 until n) varP += w[i] * w[j] * corr[i][j] * sigma[i] * sigma[j]
            varP = varP.coerceAtLeast(0.0)
            val volP = sqrt(varP)
            val weightedAvgVol = (0 until n).sumOf { w[it] * sigma[it] }

            // 리스크 기여도 — varP≈0(이론상)이면 비중으로 폴백.
            val contrib = if (varP > 1e-12) {
                (0 until n).map { i ->
                    w[i] * (0 until n).sumOf { j -> w[j] * corr[i][j] * sigma[i] * sigma[j] } / varP * 100
                }
            } else w.map { it * 100 }

            // 베타 vs 코스피(교집합 위 cov/var).
            val kospiRets = LinkedHashMap<String, Double>()
            for (i in 1 until kospiAsc.size) {
                val prev = kospiAsc[i - 1].close
                if (prev > 0 && kospiAsc[i].close > 0) kospiRets[kospiAsc[i].date] = ln(kospiAsc[i].close / prev)
            }
            val kospiWindow = kospiRets.entries.toList().takeLast(WINDOW)
            val kospiVol = if (kospiWindow.size >= MIN_RETURNS)
                annualizePct(sampleStd(kospiWindow.map { it.value })) else null

            val betas = series.map { s ->
                val common = (s.returns.keys intersect kospiRets.keys).sorted()
                if (common.size < MIN_RETURNS) return@map null
                val a = common.map { s.returns.getValue(it) }
                val k = common.map { kospiRets.getValue(it) }
                val kVar = variance(k)
                if (kVar < 1e-12) null else covariance(a, k) / kVar
            }
            val betaCoverage = (0 until n).filter { betas[it] != null }.sumOf { w[it] }
            val portfolioBeta = if (betaCoverage >= 0.5) {
                (0 until n).filter { betas[it] != null }.sumOf { w[it] * betas[it]!! } / betaCoverage
            } else null

            // r ≥ 0.7 쌍 + union-find 클러스터.
            val pairs = mutableListOf<Triple<Int, Int, Double>>()
            for (i in 0 until n) for (j in i + 1 until n) {
                if (corr[i][j] >= CORR_THRESHOLD) pairs += Triple(i, j, corr[i][j])
            }
            val parent = IntArray(n) { it }
            fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; parent[x] = r; return r }
            pairs.forEach { (i, j, _) -> parent[find(i)] = find(j) }
            val clusters = (0 until n).groupBy { find(it) }.values
                .filter { it.size >= 2 }
                .map { members ->
                    val intra = mutableListOf<Double>()
                    for (a in members.indices) for (b in a + 1 until members.size) {
                        intra += corr[members[a]][members[b]]
                    }
                    RiskCluster(
                        names = members.sortedByDescending { w[it] }.map { series[it].h.name },
                        weightPct = round2(members.sumOf { w[it] } * 100),
                        avgCorr = round2(intra.average()),
                    )
                }
                .sortedByDescending { it.weightPct }

            val topPairs = pairs.sortedByDescending { it.third }.take(8)
                .map { (i, j, r) -> CorrPair(series[i].h.name, series[j].h.name, round2(r)) }

            val weightsPct = w.map { it * 100 }
            val hhi = round(weightsPct.sumOf { it * it }).toInt()
            val top2 = weightsPct.sortedDescending().take(2).sum()

            val stocks = series.indices.map { i ->
                RiskStock(
                    code = series[i].h.code,
                    name = series[i].h.name,
                    weightPct = round2(weightsPct[i]),
                    volPct = round2(annualizePct(sigma[i])),
                    beta = betas[i]?.let { round2(it) },
                    riskContribPct = round2(contrib[i]),
                )
            }.sortedByDescending { it.riskContribPct }

            return PortfolioRisk(
                date = today,
                windowDays = WINDOW,
                stocks = stocks,
                portfolioVolPct = round2(annualizePct(volP)),
                weightedAvgVolPct = round2(annualizePct(weightedAvgVol)),
                diversificationRatio = if (volP > 1e-12) round2(weightedAvgVol / volP) else 1.0,
                portfolioBeta = portfolioBeta?.let { round2(it) },
                kospiVolPct = kospiVol?.let { round2(it) },
                avgCorr = avgCorr?.let { round2(it) },
                topPairs = topPairs,
                clusters = clusters,
                hhi = hhi,
                top2WeightPct = round2(top2),
                excluded = excluded,
                caveat = "최근 ${WINDOW}거래일 실측 상관·변동성(연환산 √252) 기준 — 과거 동행이 미래를 보장하지 않으며 " +
                    "위기 국면에선 상관이 급등하는 경향이 있음. 비중은 최근 확정 종가 기준. " +
                    "리스크 기여도는 분산 기여(합 100%)로 비중과 다를 수 있음." +
                    (if (excluded.isNotEmpty()) " 관측 부족 제외: ${excluded.joinToString("·")}(비중 재계산됨)." else ""),
            )
        }

        // ── 통계 유틸(전부 표본 기준 n-1) ─────────────────────────────────

        internal fun sampleStd(xs: List<Double>): Double = sqrt(variance(xs))

        internal fun variance(xs: List<Double>): Double {
            if (xs.size < 2) return 0.0
            val m = xs.average()
            return xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
        }

        internal fun covariance(a: List<Double>, b: List<Double>): Double {
            if (a.size < 2 || a.size != b.size) return 0.0
            val ma = a.average(); val mb = b.average()
            return a.indices.sumOf { (a[it] - ma) * (b[it] - mb) } / (a.size - 1)
        }

        internal fun pearson(a: List<Double>, b: List<Double>): Double {
            val sa = sampleStd(a); val sb = sampleStd(b)
            if (sa < 1e-12 || sb < 1e-12) return 0.0
            return (covariance(a, b) / (sa * sb)).coerceIn(-1.0, 1.0)
        }

        private fun annualizePct(dailyVol: Double): Double = dailyVol * sqrt(ANNUALIZE) * 100

        internal fun round2(v: Double) = round(v * 100) / 100.0
    }
}
