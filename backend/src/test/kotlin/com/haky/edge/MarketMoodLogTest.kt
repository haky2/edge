package com.haky.edge

import com.haky.edge.kis.MacroIndicator
import com.haky.edge.macro.MarketMoodLogService
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketMoodLogTest {

    private val service = MarketMoodLogService()

    private fun ind(key: String, changeRate: Double) =
        MacroIndicator(key = key, label = key, value = 0.0, change = 0.0, changeRate = changeRate)

    // ── 단일 지표 방향 ──────────────────────────────────────────────────────

    @Test fun `나스닥 강세 → BULLISH`() {
        // LEADING_WEIGHTS["nasdaq"] = +3.0, composite = 2.0 * 3.0 / 3.0 = 2.0 > 0.5
        val r = service.inferDirection(listOf(ind("nasdaq", +2.0)))
        assertEquals("BULLISH", r)
    }

    @Test fun `나스닥 약세 → BEARISH`() {
        val r = service.inferDirection(listOf(ind("nasdaq", -2.0)))
        assertEquals("BEARISH", r)
    }

    @Test fun `DXY 강세(달러 강세) → BEARISH (음수 가중치)`() {
        // LEADING_WEIGHTS["dxy"] = -2.0, composite = 2.0 * (-2.0) / 2.0 = -2.0 < -0.5
        val r = service.inferDirection(listOf(ind("dxy", +2.0)))
        assertEquals("BEARISH", r)
    }

    @Test fun `DXY 약세(달러 약세) → BULLISH`() {
        val r = service.inferDirection(listOf(ind("dxy", -2.0)))
        assertEquals("BULLISH", r)
    }

    @Test fun `원달러 환율 상승 → BEARISH (음수 가중치)`() {
        // usdkrw weight = -2.0
        val r = service.inferDirection(listOf(ind("usdkrw", +2.0)))
        assertEquals("BEARISH", r)
    }

    @Test fun `EWY 강세 → BULLISH`() {
        // LEADING_WEIGHTS["ewy"] = +3.0
        val r = service.inferDirection(listOf(ind("ewy", +1.0)))
        assertEquals("BULLISH", r)
    }

    // ── 복합 지표 ─────────────────────────────────────────────────────────

    @Test fun `나스닥 상승 + DXY 상승 → 상쇄되어 NEUTRAL`() {
        // nasdaq +2.0 * 3 = +6, dxy +2.0 * (-2) = -4, totalWeight = 5
        // composite = (6 + -4) / 5 = 0.4 → NEUTRAL(-0.5 < 0.4 < 0.5)
        val r = service.inferDirection(listOf(ind("nasdaq", +2.0), ind("dxy", +2.0)))
        assertEquals("NEUTRAL", r)
    }

    @Test fun `나스닥+sp500 동반 상승 → BULLISH`() {
        val r = service.inferDirection(listOf(
            ind("nasdaq", +1.5),
            ind("sp500",  +1.2),
        ))
        assertEquals("BULLISH", r)
    }

    @Test fun `나스닥+sp500 동반 하락 → BEARISH`() {
        val r = service.inferDirection(listOf(
            ind("nasdaq", -1.5),
            ind("sp500",  -1.5),
        ))
        assertEquals("BEARISH", r)
    }

    // ── 엣지 케이스 ───────────────────────────────────────────────────────

    @Test fun `지표 없음 → NEUTRAL`() {
        assertEquals("NEUTRAL", service.inferDirection(emptyList()))
    }

    @Test fun `알 수 없는 지표 키만 있으면 → NEUTRAL`() {
        // LEADING_WEIGHTS에 없는 키는 합산에서 제외 → totalWeight=0 → NEUTRAL
        assertEquals("NEUTRAL", service.inferDirection(listOf(ind("kospi", +1.0))))
    }

    @Test fun `등락률 0이면 NEUTRAL`() {
        // 모든 가중합이 0
        val r = service.inferDirection(listOf(
            ind("nasdaq", 0.0),
            ind("sp500",  0.0),
        ))
        assertEquals("NEUTRAL", r)
    }

    @Test fun `임계값 정확히 0_5 → NEUTRAL`() {
        // composite > 0.5만 BULLISH. 정확히 0.5는 NEUTRAL.
        // nasdaq only: composite = changeRate * 3 / 3 = changeRate
        // changeRate = 0.5 → composite = 0.5 → 조건은 > 0.5 이므로 NEUTRAL
        val r = service.inferDirection(listOf(ind("nasdaq", +0.5)))
        assertEquals("NEUTRAL", r)
    }

    @Test fun `임계값 0_5 초과하면 BULLISH`() {
        val r = service.inferDirection(listOf(ind("nasdaq", +0.6)))
        assertEquals("BULLISH", r)
    }
}
