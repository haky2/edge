package com.haky.edge.analysis

import com.haky.edge.model.DailyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TechnicalIndicatorsTest {

    private fun bar(close: Long, volume: Long = 1_000_000) =
        DailyBar(date = "20260101", open = close, high = close, low = close, close = close, volume = volume)

    // ── SMA ──────────────────────────────────────────────────────────────────

    @Test
    fun sma_returnsAverageOfFirstNValues() {
        // 최신이 앞: [10, 20, 30, 40, 50]. n=3이면 최신 3개(10,20,30) 평균 = 20.
        val values = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        assertEquals(20.0, TechnicalIndicators.sma(values, 3))
    }

    @Test
    fun sma_exactlyNValues_returnsAverage() {
        val values = listOf(100.0, 200.0)
        assertEquals(150.0, TechnicalIndicators.sma(values, 2))
    }

    @Test
    fun sma_notEnoughData_returnsNull() {
        assertNull(TechnicalIndicators.sma(listOf(100.0, 200.0), 5))
    }

    // ── RSI ──────────────────────────────────────────────────────────────────

    @Test
    fun rsi_notEnoughData_returnsNull() {
        // n+1개 필요. n=14이면 15개 미만 → null.
        val closes = (1..14).map { it.toDouble() }
        assertNull(TechnicalIndicators.rsi(closes, 14))
    }

    @Test
    fun rsi_allGains_returns100() {
        // 계속 오르면 avgLoss=0 → RSI=100
        val closes = (1..20).map { it.toDouble() }.reversed()  // 최신이 앞
        val result = TechnicalIndicators.rsi(closes, 14)
        assertNotNull(result)
        assertEquals(100.0, result)
    }

    @Test
    fun rsi_allLosses_returnsZero() {
        // 계속 내리면 avgGain=0 → RSI=0
        val closes = (1..20).map { it.toDouble() }  // 최신이 앞(내림차순)
        val result = TechnicalIndicators.rsi(closes, 14)
        assertNotNull(result)
        assertTrue(result < 1.0, "all-loss RSI should be near 0, got $result")
    }

    @Test
    fun rsi_mixedData_between0And100() {
        // 오르내림 혼합 → 0~100 사이
        val closes = listOf(105.0, 100.0, 102.0, 98.0, 103.0, 97.0, 101.0,
            96.0, 104.0, 99.0, 106.0, 95.0, 108.0, 93.0, 110.0)
        val result = TechnicalIndicators.rsi(closes, 14)
        assertNotNull(result)
        assertTrue(result in 0.0..100.0, "RSI out of range: $result")
    }

    // ── VolumeRatio ───────────────────────────────────────────────────────────

    @Test
    fun volumeRatio_todayEqualsAvg_returns1() {
        // 오늘 + 과거 20일 모두 동일 거래량 → 비율 1.0
        val volumes = (0..20).map { 500_000.0 }  // [오늘, 과거20]
        val result = TechnicalIndicators.volumeRatio(volumes, 20)
        assertNotNull(result)
        assertEquals(1.0, result)
    }

    @Test
    fun volumeRatio_todayDoubleAvg_returns2() {
        val avg = 500_000.0
        val volumes = listOf(avg * 2) + (1..20).map { avg }
        val result = TechnicalIndicators.volumeRatio(volumes, 20)
        assertNotNull(result)
        assertEquals(2.0, result, 1e-9)
    }

    @Test
    fun volumeRatio_notEnoughData_returnsNull() {
        // 오늘 + 20일 = 21개 필요. 20개만 있으면 null.
        val volumes = (1..20).map { it.toDouble() }
        assertNull(TechnicalIndicators.volumeRatio(volumes, 20))
    }

    @Test
    fun volumeRatio_zeroAvg_returnsNull() {
        val volumes = listOf(1000.0) + (1..20).map { 0.0 }
        assertNull(TechnicalIndicators.volumeRatio(volumes, 20))
    }

    // ── calculate (통합) ──────────────────────────────────────────────────────

    @Test
    fun calculate_emptyList_allNull() {
        val r = TechnicalIndicators.calculate(emptyList())
        assertNull(r.ma5)
        assertNull(r.ma20)
        assertNull(r.ma60)
        assertNull(r.rsi14)
        assertNull(r.volumeRatio)
    }

    @Test
    fun calculate_enoughData_allNonNull() {
        // MA60·RSI14 모두 계산하려면 최소 61개(MA60) + 15개(RSI14) → 61개로 충분(RSI14=15<61).
        val bars = (1..62).map { bar(close = (10_000 + it * 100).toLong()) }.reversed()
        val r = TechnicalIndicators.calculate(bars)
        assertNotNull(r.ma5)
        assertNotNull(r.ma20)
        assertNotNull(r.ma60)
        assertNotNull(r.rsi14)
        assertNotNull(r.volumeRatio)
    }
}
