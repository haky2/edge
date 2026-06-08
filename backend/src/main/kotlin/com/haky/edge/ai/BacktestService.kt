package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.time.LocalDate

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

    suspend fun getBacktest(code: String): Backtest? {
        val today = LocalDate.now().toString()
        val cacheKey = "$code:$today"
        fileCache.get(cacheKey)?.let { return it }

        // 일봉 120(가능한 만큼) + 수급 30을 병렬로.
        val (dailyDesc, flow) = coroutineScope {
            val d = async { runCatching { kis.getDailyChart(code, bars = 120) }.getOrElse { emptyList() } }
            val f = async { runCatching { kis.getInvestorFlow(code, days = 30) }.getOrElse { emptyList() } }
            d.await() to f.await()
        }
        // 익일 수익률을 보려면 최소 2거래일 필요.
        if (dailyDesc.size < 2) return null

        // 과거→현재 오름차순으로 정렬해 인덱스 i와 i+1(익일)을 매핑.
        val bars = dailyDesc.sortedBy { it.date }
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

    companion object {
        // 이 미만이면 통계적으로 신뢰 곤란 → confident=false.
        private const val MIN_SAMPLE = 8
    }
}
