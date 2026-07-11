package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import com.haky.edge.util.DayScopedCache
import com.haky.edge.util.KST
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** 한 주체(외인/기관)의 순매수량 vs 당일 등락률 Pearson 상관 결과. */
@Serializable
data class FlowCorrelation(
    val investor: String,    // "외인" / "기관"
    val r: Double,           // Pearson r (-1.0~1.0, 소수점 2자리)
    val label: String,       // "양의 중간 상관" 등
    val n: Int,              // 매칭된 표본일수
    val confident: Boolean,  // n >= MIN_SAMPLE
)

/** 한 종목의 수급-가격 민감도. GET /flow-sensitivity/{code} 응답 DTO. */
@Serializable
data class FlowSensitivity(
    val code: String,
    val items: List<FlowCorrelation>,
)

/** 단일 신호의 익일 성과 집계 결과. */
@Serializable
data class SignalResult(
    val signal: String,        // "외인 순매수" 등 표시명
    val n: Int,                // 신호 발생 표본수
    val winRate: Int,          // 익일 상승 확률 % (0~100), n==0이면 -1
    val avgReturn: Double,     // 익일 평균 수익률 % (close[T]→close[T+1])
    val edge: Double,          // avgReturn - 평소(전체일) 평균수익률, %p
    val confident: Boolean,    // n >= MIN_SAMPLE 일 때만 신뢰 가능
)

/** 한 종목의 신호 백테스트 결과 DTO. */
@Serializable
data class Backtest(
    val code: String,
    val tradingDays: Int,          // 분석에 쓴 거래일 수(일봉 개수)
    val flowDays: Int,             // 수급 표본 일수
    val baselineWinRate: Int,      // 평소(전체일) 익일 상승 확률 %
    val baselineAvgReturn: Double, // 평소 익일 평균 수익률 %
    val signals: List<SignalResult>,
)

/**
 * 기존 일봉 + 수급 데이터만으로 신호의 실제 익일 적중률을 측정한다. 새 데이터 0.
 *
 * 측정 신호:
 *  - 외인 순매수일 / 기관 순매수일 (수급은 장후 확정값)
 *  - 거래량 급증일 (직전 20일 평균 대비 2배↑)
 *
 * Lookahead bias 방지:
 *  - 모든 신호는 T일 종가 시점에야 확정적으로 알 수 있다(수급=장후 확정, 거래량=마감 후).
 *    따라서 성과는 진입 가능한 close[T]→close[T+1] 수익률로만 측정한다.
 *  - 거래량 급증 임계(20일 평균)는 T일 *이전* 데이터로만 계산해 미래 정보를 섞지 않는다.
 *  - "승"의 정의: 익일 종가가 당일 종가보다 높으면 승(>0).
 *
 * 정직성:
 *  - 각 신호의 표본수 n을 항상 노출하고, n < MIN_SAMPLE 이면 confident=false로 표시.
 *  - edge = 신호일 평균수익률 - 전체일 평균수익률(평소). 신호의 *초과* 성과만 의미.
 *
 * KIS 한계: 수급은 약 30거래일, 일봉은 최대 120거래일까지만 제공 → 표본이 작을 수 있음.
 */
class BacktestService(
    private val kis: KisClient,
) {
    private val fileCache = FileCache("backtest", Backtest.serializer())

    // getBacktest와 getFlowSensitivity가 동시에 호출될 때 KIS API 이중 호출 방지.
    private data class KisData(val bars: List<DailyBar>, val flow: List<InvestorFlow>)
    private val kisDataCache = DayScopedCache<KisData>()   // 날짜 회전 시 자동 clear(S1)

    private suspend fun fetchKisData(code: String): KisData {
        val today = effectiveMarketDate() // KST 거래일 기준
        kisDataCache.get(today, code)?.let { return it }
        val (dailyAsc, flow) = coroutineScope {
            // KIS 일봉 단일 호출은 ~100건 상한 → 2회 호출로 최근 120 거래일 확보(S7).
            val d = async {
                runCatching {
                    val dtf = DateTimeFormatter.ofPattern("yyyyMMdd")
                    val today = LocalDate.now(KST)
                    val mid = today.minusMonths(3)
                    val start = today.minusMonths(6)
                    val b1 = kis.getDailyChartRange(code, start.format(dtf), mid.format(dtf))
                    val b2 = kis.getDailyChartRange(code, mid.format(dtf), today.format(dtf))
                    (b1 + b2).distinctBy { it.date }.sortedBy { it.date }.takeLast(120)
                }.getOrElse { emptyList() }
            }
            val f = async { runCatching { kis.getInvestorFlow(code, days = 30) }.getOrElse { emptyList() } }
            d.await() to f.await()
        }
        val data = KisData(dailyAsc, flow)
        kisDataCache.put(today, code, data)
        return data
    }

    suspend fun getBacktest(code: String): Backtest? {
        val today = effectiveMarketDate() // KST 거래일 — FileCache KST 검증과 통일
        val cacheKey = "$code:$today"
        fileCache.get(cacheKey)?.let { return it }

        val (bars, flow) = fetchKisData(code).let { it.bars to it.flow }
        // 익일 수익률을 보려면 최소 2거래일 필요.
        if (bars.size < 2) return null
        val dateToIndex = bars.indices.associateBy { bars[it].date }

        // close[i] → close[i+1] 익일 수익률(%). 마지막 날(익일 없음)은 null.
        fun nextReturn(i: Int): Double? {
            if (i < 0 || i + 1 >= bars.size) return null
            val cur = bars[i].close
            val nxt = bars[i + 1].close
            if (cur <= 0) return null
            return (nxt - cur).toDouble() / cur * 100.0
        }

        // 평소(전체일) 베이스라인: 익일이 존재하는 모든 날.
        val baseline = bars.indices.mapNotNull { nextReturn(it) }
        val baselineWin = winRate(baseline)
        val baselineAvg = avg(baseline)

        val signals = mutableListOf<SignalResult>()
        signals += flowSignal("외인 순매수", flow, dateToIndex, ::nextReturn, baselineAvg) { it.foreign > 0 }
        signals += flowSignal("기관 순매수", flow, dateToIndex, ::nextReturn, baselineAvg) { it.institution > 0 }
        signals += volumeSurgeSignal(bars, ::nextReturn, baselineAvg)

        val result = Backtest(
            code = code,
            tradingDays = bars.size,
            flowDays = flow.size,
            baselineWinRate = baselineWin,
            baselineAvgReturn = baselineAvg.round2(),
            signals = signals,
        )
        fileCache.put(cacheKey, result)
        return result
    }

    /** 수급 신호: predicate 만족하는 날들의 익일 수익률 집계. */
    private fun flowSignal(
        name: String,
        flow: List<InvestorFlow>,
        dateToIndex: Map<String, Int>,
        nextReturn: (Int) -> Double?,
        baselineAvg: Double,
        predicate: (InvestorFlow) -> Boolean,
    ): SignalResult {
        val returns = flow.filter(predicate)
            .mapNotNull { dateToIndex[it.date]?.let(nextReturn) }
        return summarize(name, returns, baselineAvg)
    }

    /** 거래량 급증 신호: 직전 20일 평균 대비 2배↑인 날들의 익일 수익률 집계. */
    private fun volumeSurgeSignal(
        bars: List<DailyBar>,
        nextReturn: (Int) -> Double?,
        baselineAvg: Double,
    ): SignalResult {
        val window = 20
        val returns = mutableListOf<Double>()
        for (i in window until bars.size) {
            // 임계는 T일 이전(i-window..i-1)만 사용 → lookahead 없음.
            val prior = bars.subList(i - window, i).map { it.volume }
            val avgVol = prior.average()
            if (avgVol <= 0) continue
            if (bars[i].volume >= avgVol * 2.0) {
                nextReturn(i)?.let { returns.add(it) }
            }
        }
        return summarize("거래량 급증(2배↑)", returns, baselineAvg)
    }

    private fun summarize(name: String, returns: List<Double>, baselineAvg: Double): SignalResult {
        val n = returns.size
        val a = avg(returns)
        return SignalResult(
            signal = name,
            n = n,
            winRate = if (n == 0) -1 else winRate(returns),
            avgReturn = a.round2(),
            edge = (a - baselineAvg).round2(),
            confident = n >= MIN_SAMPLE,
        )
    }

    private fun winRate(returns: List<Double>): Int =
        if (returns.isEmpty()) -1 else returns.count { it > 0 } * 100 / returns.size

    private fun avg(returns: List<Double>): Double =
        if (returns.isEmpty()) 0.0 else returns.average()

    private fun Double.round2(): Double = Math.round(this * 100.0) / 100.0

    // ── 수급-가격 민감도 ──────────────────────────────────────────────────

    private val flowSensCache = FileCache("flow-sensitivity", FlowSensitivity.serializer())

    /**
     * 외인/기관 순매수량과 당일 등락률의 Pearson 상관계수를 측정한다.
     * "이 종목은 외인이 살수록 당일 얼마나 오르는가"를 실측.
     * 기존 일봉(120)+수급(30) 데이터를 재사용 — 새 API 호출 없음.
     */
    suspend fun getFlowSensitivity(code: String): FlowSensitivity? {
        val today = effectiveMarketDate() // KST 거래일 — FileCache KST 검증과 통일
        val cacheKey = "$code:$today"
        flowSensCache.get(cacheKey)?.let { return it }

        // fetchKisData 재사용 — getBacktest와 동시 호출 시 KIS API 이중 호출 방지
        val (bars, flow) = fetchKisData(code).let { it.bars to it.flow }
        if (bars.size < 2 || flow.isEmpty()) return null

        // 오름차순 정렬된 bars에서 zipWithNext로 당일 수익률 계산
        val dateToReturn: Map<String, Double> = bars.zipWithNext().mapNotNull { (prev, cur) ->
            if (prev.close <= 0) null
            else cur.date to (cur.close - prev.close).toDouble() / prev.close * 100.0
        }.toMap()

        val items = listOf(
            flowCorr("외인", flow, dateToReturn) { it.foreign },
            flowCorr("기관", flow, dateToReturn) { it.institution },
        ).filterNotNull()

        if (items.isEmpty()) return null
        val result = FlowSensitivity(code = code, items = items)
        flowSensCache.put(cacheKey, result)
        return result
    }

    private fun flowCorr(
        name: String,
        flow: List<InvestorFlow>,
        dateToReturn: Map<String, Double>,
        getFlow: (InvestorFlow) -> Long,
    ): FlowCorrelation? {
        val pairs = flow.mapNotNull { f ->
            val ret = dateToReturn[f.date] ?: return@mapNotNull null
            getFlow(f).toDouble() to ret
        }
        val n = pairs.size
        if (n < 2) return null
        val xs = pairs.map { it.first }
        val ys = pairs.map { it.second }
        val r = pearson(xs, ys).round2()
        return FlowCorrelation(
            investor = name,
            r = r,
            label = corrLabel(r),
            n = n,
            confident = n >= MIN_SAMPLE,
        )
    }

    private fun pearson(xs: List<Double>, ys: List<Double>): Double = Companion.pearson(xs, ys)

    private fun corrLabel(r: Double): String = Companion.corrLabel(r)

    companion object {
        // 이 미만이면 통계적으로 신뢰 곤란 → confident=false.
        private const val MIN_SAMPLE = 8

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

        internal fun corrLabel(r: Double): String {
            val absR = abs(r)
            val dir = if (r >= 0) "양의" else "음의"
            return when {
                absR < 0.1 -> "거의 무관"
                absR < 0.3 -> "${dir} 약한 상관"
                absR < 0.5 -> "${dir} 중간 상관"
                else -> "${dir} 강한 상관"
            }
        }
    }
}
