package com.haky.edge.analysis

import com.haky.edge.model.DailyBar

/** 이평선·RSI·거래량 추세 계산 결과. null = 데이터 부족. */
data class TechnicalResult(
    val ma5: Double?,    // 5일 이동평균
    val ma20: Double?,   // 20일 이동평균
    val ma60: Double?,   // 60일 이동평균
    val rsi14: Double?,  // RSI(14일)
    val volumeRatio: Double?,  // 오늘 거래량 / 20일 평균 거래량 (1.0=평균, 2.0=2배)
)

/**
 * 기술적 지표 계산. 입력 bars 는 최신일이 앞(백엔드 /daily 응답 그대로).
 * 계산은 LLM·외부 호출 없이 즉시. 지표 자체는 사실이고, 해석은 StockAnalysis 몫.
 */
object TechnicalIndicators {

    fun calculate(bars: List<DailyBar>): TechnicalResult {
        if (bars.isEmpty()) return TechnicalResult(null, null, null, null, null)

        // bars 는 최신일이 앞 → 이평 계산은 인덱스 0~N-1(최신부터)로 단순 평균
        val closes = bars.map { it.close.toDouble() }
        val volumes = bars.map { it.volume.toDouble() }

        return TechnicalResult(
            ma5 = sma(closes, 5),
            ma20 = sma(closes, 20),
            ma60 = sma(closes, 60),
            rsi14 = rsi(closes, 14),
            volumeRatio = volumeRatio(volumes, 20),
        )
    }

    /** 단순이동평균(SMA). bars는 최신이 앞 — 가장 최근 n개의 평균. */
    internal fun sma(values: List<Double>, n: Int): Double? {
        if (values.size < n) return null
        return values.take(n).average()
    }

    /**
     * RSI(Wilder's Smoothed, period=n).
     * bars는 최신이 앞. n+1개가 최소(n일 변화분 필요).
     * Wilder 방식: 첫 RS = 단순 평균, 이후 지수평활.
     */
    internal fun rsi(closes: List<Double>, n: Int): Double? {
        if (closes.size < n + 1) return null

        // 최신이 앞 → 오래된 순으로 뒤집어 변화분 계산
        val reversed = closes.reversed()
        val changes = (1 until reversed.size).map { reversed[it] - reversed[it - 1] }
        if (changes.size < n) return null

        // 첫 평균 RS
        var avgGain = changes.take(n).filter { it > 0 }.average().takeIf { changes.take(n).any { c -> c > 0 } } ?: 0.0
        var avgLoss = changes.take(n).filter { it < 0 }.map { -it }.average().takeIf { changes.take(n).any { c -> c < 0 } } ?: 0.0

        // Wilder 지수평활로 나머지 적용
        for (i in n until changes.size) {
            val gain = if (changes[i] > 0) changes[i] else 0.0
            val loss = if (changes[i] < 0) -changes[i] else 0.0
            avgGain = (avgGain * (n - 1) + gain) / n
            avgLoss = (avgLoss * (n - 1) + loss) / n
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /** 오늘 거래량 / 최근 n일 평균 거래량. 1.0=평균, 2.0=2배 폭발. */
    internal fun volumeRatio(volumes: List<Double>, n: Int): Double? {
        if (volumes.size < n + 1) return null  // 오늘 + n일 필요
        val today = volumes[0]
        val avg = volumes.drop(1).take(n).average()
        if (avg == 0.0) return null
        return today / avg
    }
}
