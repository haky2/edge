package com.haky.edge

import com.haky.edge.ai.AnalogService
import com.haky.edge.ai.AnalogValidationService
import com.haky.edge.kis.DailyBar
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ②-2a Analog 캘리브레이션 실증 — replay 범위·버킷·채점 순수 함수. */
class AnalogValidationTest {

    // ── replayIndices ──────────────────────────────────────────────

    @Test
    fun `replayIndices - 스펙 범위(MIN_HISTORY+60 ~ n-1-60) 5거래일 간격`() {
        val idx = AnalogValidationService.replayIndices(750)
        assertEquals(AnalogService.MIN_HISTORY + 60, idx.first())   // 312
        assertTrue(idx.last() <= 750 - 1 - 60)                       // ≤689
        assertTrue(idx.zipWithNext().all { (a, b) -> b - a == 5 })
    }

    @Test
    fun `replayIndices - 이력 부족이면 빈 리스트`() {
        assertTrue(AnalogValidationService.replayIndices(AnalogService.MIN_HISTORY + 120).isEmpty())
        assertEquals(1, AnalogValidationService.replayIndices(AnalogService.MIN_HISTORY + 121).size)
    }

    // ── bucketLabel ────────────────────────────────────────────────

    @Test
    fun `bucketLabel - 경계값(45 포함·60 포함)`() {
        assertEquals("<45", AnalogValidationService.bucketLabel(44.9))
        assertEquals("45~60", AnalogValidationService.bucketLabel(45.0))
        assertEquals("45~60", AnalogValidationService.bucketLabel(60.0))
        assertEquals(">60", AnalogValidationService.bucketLabel(60.1))
    }

    // ── collectSamples ─────────────────────────────────────────────

    private fun syntheticBars(n: Int): List<DailyBar> = (0 until n).map { i ->
        val close = (100_000 + 20_000 * sin(i / 17.0) + i * 15).toLong()
        DailyBar(
            date = "%08d".format(20230101 + i),
            open = close, high = close + 500, low = close - 500, close = close,
            volume = 100_000L + (i % 37) * 3_000L,
        )
    }

    @Test
    fun `collectSamples - 실현 수익률이 t+h 종가와 일치(look-ahead 없는 예측 + 사후 채점)`() {
        val asc = syntheticBars(500)
        val closes = asc.map { it.close.toDouble() }
        val samples = AnalogValidationService.collectSamples(asc)
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val t = asc.indexOfFirst { it.date == s.date }
            assertTrue(t >= AnalogService.MIN_HISTORY + 60)
            assertEquals((closes[t + 5] / closes[t] - 1) * 100, s.realized.getValue(5), 1e-9)
            assertEquals((closes[t + 20] / closes[t] - 1) * 100, s.realized.getValue(20), 1e-9)
            assertEquals((closes[t] / closes[t - 20] - 1) * 100, s.ret20, 1e-9)
            assertTrue(s.matchedN > 0)
        }
    }

    // ── scoreHorizon ───────────────────────────────────────────────

    private fun sample(winRate: Double, median: Double, realized: Double, ret20: Double) =
        AnalogValidationService.Companion.ReplaySample(
            date = "20240101", matchedN = 20,
            predicted = mapOf(5 to (winRate to median)),
            realized = mapOf(5 to realized),
            ret20 = ret20,
        )

    @Test
    fun `scoreHorizon - 버킷 실현 양수율·단조 판정·n15 침묵`() {
        val samples =
            List(20) { sample(70.0, 2.0, +1.0, +1.0) } +   // >60 → 전부 양수
            List(20) { sample(30.0, -2.0, -1.0, -1.0) } +  // <45 → 전부 음수
            List(5) { sample(50.0, 0.5, +1.0, +1.0) }      // 45~60 → n<15 침묵
        val hv = AnalogValidationService.scoreHorizon(samples, 5)

        assertEquals(45, hv.n)
        val byLabel = hv.buckets.associateBy { it.label }
        assertEquals(100.0, byLabel.getValue(">60").realizedPosRatePct)
        assertEquals(0.0, byLabel.getValue("<45").realizedPosRatePct)
        assertTrue(byLabel.getValue("45~60").silenced)
        assertTrue(!byLabel.getValue(">60").silenced)
        assertEquals(true, hv.monotonic)                    // 0%(<45) ≤ 100%(>60)
        assertTrue(hv.spearmanMedianVsRealized > 0.9)       // 예측·실현 완전 단조
        assertEquals(100.0, hv.analogSignAccuracyPct)
        assertEquals(100.0, hv.naiveSignAccuracyPct)
    }

    @Test
    fun `scoreHorizon - 역캘리브레이션이면 monotonic false, 판정 버킷 부족이면 null`() {
        val inverted =
            List(20) { sample(70.0, 2.0, -1.0, +1.0) } +   // >60인데 전부 음수
            List(20) { sample(30.0, -2.0, +1.0, -1.0) }    // <45인데 전부 양수
        assertEquals(false, AnalogValidationService.scoreHorizon(inverted, 5).monotonic)

        val tiny = List(5) { sample(70.0, 2.0, 1.0, 1.0) }
        assertEquals(null, AnalogValidationService.scoreHorizon(tiny, 5).monotonic)
    }

    @Test
    fun `scoreHorizon - 부호 비교는 예측·나이브·실현 전부 비0인 공통 표본만`() {
        val samples =
            List(10) { sample(70.0, 2.0, +1.0, +1.0) } +
            List(5) { sample(70.0, 0.0, +1.0, +1.0) } +    // 예측 median 0 → 제외
            List(5) { sample(70.0, 2.0, +1.0, 0.0) }       // ret20 0 → 제외
        val hv = AnalogValidationService.scoreHorizon(samples, 5)
        assertEquals(10, hv.signN)
    }
}
