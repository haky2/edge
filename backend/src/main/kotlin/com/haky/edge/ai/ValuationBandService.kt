package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.kis.KisClient
import com.haky.edge.util.KST
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 앱에 내려주는 밸류에이션 히스토리 밴드 DTO. */
@Serializable
data class ValuationBand(
    val code: String,
    val perCurrent: Double,
    val perMin: Double,
    val perMax: Double,
    val perMedian: Double,
    val perPercentile: Int,   // 0~100, -1=계산 불가
    val perLabel: String,
    val pbrCurrent: Double,
    val pbrMin: Double,
    val pbrMax: Double,
    val pbrMedian: Double,
    val pbrPercentile: Int,
    val pbrLabel: String,
    val yearsUsed: Int,       // 실제 계산에 사용된 연수
)

/**
 * PER/PBR 현재값을 과거 N년 연간 말 실제 PER/PBR과 비교해 밴드·백분위를 계산한다.
 *
 * 알고리즘:
 *  - DART 사업보고서(연간): 각 연도 당기순이익·자본총계
 *  - KIS 월봉: 해당 연도 12월 종가(수정주가)
 *  - 상장주식수: KIS inquire-price lstn_stcn(현재 기준, 근사치)
 *  - EPS = 당기순이익 / 상장주식수, BPS = 자본총계 / 상장주식수
 *  - 역사적 PER = 12월 종가 / EPS, 역사적 PBR = 12월 종가 / BPS
 *  - 현재 PER/PBR 백분위 = (과거 값 중 현재보다 낮은 것의 수 / 전체) × 100
 *
 * 주의: 상장주식수는 현재 시점 기준으로 과거 계산에도 동일하게 사용(변동 무시), 근사치임.
 */
class ValuationBandService(
    private val kis: KisClient,
    private val dart: DartClient,
) {
    private val fileCache = FileCache("valuation-band", ValuationBand.serializer())

    suspend fun getValuationBand(code: String): ValuationBand? {
        // 캐시 키는 KST 거래일 기준(effectiveMarketDate) — FileCache의 KST 검증과 통일(서버 UTC라 오전 미스 방지).
        val today = effectiveMarketDate()
        val cacheKey = "$code:$today"
        fileCache.get(cacheKey)?.let { return it }

        val listedShares = runCatching { kis.getListedShares(code) }.getOrNull() ?: return null
        if (listedShares <= 0) return null

        // 월봉 5년치(65개월 여유)와 과거 5년 DART 재무를 병렬로 가져온다.
        val monthlyBars = runCatching { kis.getMonthlyChart(code, months = 65) }.getOrElse { emptyList() }
        if (monthlyBars.isEmpty()) return null

        val currentPrice = runCatching { kis.getPrice(code).price.toDouble() }.getOrNull() ?: return null

        val thisYear = LocalDate.now(KST).year
        // 과거 5년 + 현재(가장 최근 연간보고서)
        val targetYears = ((thisYear - 1) downTo (thisYear - 5)).toList()

        val financialsByYear = coroutineScope {
            targetYears.map { year ->
                year to async { runCatching { dart.getFinancialsForYear(code, year) }.getOrNull() }
            }.associate { (year, deferred) -> year to deferred.await() }
        }

        // 현재 PER/PBR: 가장 최근 DART 연간보고서 + 오늘 현재가 (역사 데이터와 동일한 기준)
        val latestFin = targetYears.firstNotNullOfOrNull { financialsByYear[it] }
        val currentPer = if (latestFin?.netIncome != null && latestFin.netIncome > 0) {
            val eps = latestFin.netIncome.toDouble() / listedShares
            if (eps > 0) currentPrice / eps else 0.0
        } else 0.0

        val currentPbr = if (latestFin?.equity != null && latestFin.equity > 0) {
            val bps = latestFin.equity.toDouble() / listedShares
            if (bps > 0) currentPrice / bps else 0.0
        } else 0.0

        if (currentPer <= 0 && currentPbr <= 0) return null

        // 역사적 PER/PBR: 각 연도 12월말 종가 + 해당 연도 DART 재무 (동일 기준)
        val perHistory = mutableListOf<Double>()
        val pbrHistory = mutableListOf<Double>()

        for (year in targetYears) {
            val fin = financialsByYear[year] ?: continue
            val decPrice = yearEndPrice(monthlyBars, year) ?: continue
            val price = decPrice.toDouble()

            if (fin.netIncome != null && fin.netIncome > 0) {
                val eps = fin.netIncome.toDouble() / listedShares
                if (eps > 0) perHistory.add(price / eps)
            }
            if (fin.equity != null && fin.equity > 0) {
                val bps = fin.equity.toDouble() / listedShares
                if (bps > 0) pbrHistory.add(price / bps)
            }
        }

        val result = buildBand(code, currentPer, currentPbr, perHistory, pbrHistory) ?: return null
        fileCache.put(cacheKey, result)
        return result
    }

    private fun yearEndPrice(bars: List<com.haky.edge.kis.DailyBar>, year: Int): Long? {
        // 월봉의 날짜는 해당 월 마지막 거래일. 12월봉을 찾아 최신(마지막 달) 사용.
        val decBars = bars.filter { it.date.startsWith("${year}12") }
        return decBars.maxByOrNull { it.date }?.close
    }

    private fun buildBand(
        code: String,
        currentPer: Double,
        currentPbr: Double,
        perHistory: List<Double>,
        pbrHistory: List<Double>,
    ): ValuationBand? {
        if (perHistory.isEmpty() && pbrHistory.isEmpty()) return null

        val perSorted = perHistory.sorted().filter { it in 0.5..200.0 } // 이상치 제외
        val pbrSorted = pbrHistory.sorted().filter { it in 0.1..50.0 }

        fun percentile(sorted: List<Double>, current: Double): Int {
            if (sorted.isEmpty() || current <= 0) return -1
            return sorted.count { it < current } * 100 / sorted.size
        }

        fun median(sorted: List<Double>): Double =
            if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]

        // 판정("저평가/고평가")이 아니라 밴드 내 위치만 중립적으로 표기한다.
        // 리레이팅(반도체·AI)·이익 점프(조선·방산) 국면에선 상단=고평가가 아니므로,
        // 가치 판단은 Claude가 실적 방향·목표가·표본과 함께 해석하도록 facts/프롬프트에 위임.
        fun label(pct: Int): String = when {
            pct < 0  -> "계산 불가"
            pct < 25 -> "역사적 하단권"
            pct < 65 -> "역사적 중간권"
            else     -> "역사적 상단권"
        }

        val perPct = percentile(perSorted, currentPer)
        val pbrPct = percentile(pbrSorted, currentPbr)

        return ValuationBand(
            code = code,
            perCurrent  = currentPer,
            perMin      = perSorted.firstOrNull() ?: 0.0,
            perMax      = perSorted.lastOrNull()  ?: 0.0,
            perMedian   = median(perSorted),
            perPercentile = perPct,
            perLabel    = label(perPct),
            pbrCurrent  = currentPbr,
            pbrMin      = pbrSorted.firstOrNull() ?: 0.0,
            pbrMax      = pbrSorted.lastOrNull()  ?: 0.0,
            pbrMedian   = median(pbrSorted),
            pbrPercentile = pbrPct,
            pbrLabel    = label(pbrPct),
            yearsUsed   = maxOf(perHistory.size, pbrHistory.size),
        )
    }
}
