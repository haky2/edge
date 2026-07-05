package com.haky.edge

import com.haky.edge.ai.CatalystImpactService
import com.haky.edge.kis.DailyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F2 슬라이스 2-1·2-2 — 수주 공시 임팩트 통계 (LLM 0, forward return 계산). */
class CatalystImpactTest {

    // ── isOrderCategory ──────────────────────────────────────────────────────

    @Test
    fun `수주 공시는 카테고리 포함`() {
        assertTrue(CatalystImpactService.isOrderCategory("단일판매·공급계약체결"))
        assertTrue(CatalystImpactService.isOrderCategory("수주 공시"))
        assertTrue(CatalystImpactService.isOrderCategory("공급계약"))
        assertTrue(CatalystImpactService.isOrderCategory("단일판매공급계약체결"))
    }

    @Test
    fun `정정 공시는 제외`() {
        assertFalse(CatalystImpactService.isOrderCategory("수주 정정"))
        assertFalse(CatalystImpactService.isOrderCategory("단일판매·공급계약체결(정정)"))
    }

    @Test
    fun `비관련 공시는 제외`() {
        assertFalse(CatalystImpactService.isOrderCategory("사업보고서"))
        assertFalse(CatalystImpactService.isOrderCategory("현금·현물배당결정"))
        assertFalse(CatalystImpactService.isOrderCategory("유상증자결정"))
    }

    // ── computeHorizons ──────────────────────────────────────────────────────

    private fun bars(prices: List<Int>): List<DailyBar> {
        var date = 20260601
        return prices.map { price ->
            DailyBar(date = date++.toString(), open = price.toLong(), high = price.toLong(), low = price.toLong(), close = price.toLong(), volume = 1000L)
        }
    }

    @Test
    fun `1·5·20일 forward return 정확 계산`() {
        // 21개 봉 — 이벤트일=1일차(idx 0, date=20260601), base=idx 0
        // 1일 후: idx 1 / 5일 후: idx 5 / 20일 후: idx 20
        val prices = listOf(100, 110, 105, 108, 112, 115, 120, 118, 122, 125,
                            130, 128, 132, 135, 138, 140, 142, 145, 148, 150, 160)
        val barsAsc = bars(prices)
        val eventDates = listOf("20260601")  // idx=0 기준봉

        val horizons = CatalystImpactService.computeHorizons(eventDates, barsAsc)
        assertEquals(3, horizons.size)

        val h1 = horizons.first { it.days == 1 }
        assertEquals(1, h1.n)
        // (110/100 - 1) * 100 = 10.0
        assertEquals(10.0, h1.avgPct)
        assertEquals(100.0, h1.winRatePct)  // 상승

        val h5 = horizons.first { it.days == 5 }
        // (115/100 - 1) * 100 = 15.0
        assertEquals(15.0, h5.avgPct)

        val h20 = horizons.first { it.days == 20 }
        // (160/100 - 1) * 100 = 60.0
        assertEquals(60.0, h20.avgPct)
    }

    @Test
    fun `이벤트 날짜가 봉 범위 밖이면 제외`() {
        val barsAsc = bars(listOf(100, 110, 120))
        val horizons = CatalystImpactService.computeHorizons(listOf("20250101"), barsAsc)
        // 20250101은 bars 날짜(20260601~)보다 이전이라 baseIdx=-1 → 전부 제외
        horizons.forEach { assertEquals(0, it.n) }
    }

    @Test
    fun `forward 봉 부족하면 해당 horizon만 제외`() {
        // 봉 3개: idx 0~2. 이벤트=idx 0. 1일 후=OK(idx 1), 5일 후=봉 없음, 20일 후=봉 없음
        val barsAsc = bars(listOf(100, 110, 120))
        val horizons = CatalystImpactService.computeHorizons(listOf("20260601"), barsAsc)

        val h1 = horizons.first { it.days == 1 }
        assertEquals(1, h1.n)

        val h5 = horizons.first { it.days == 5 }
        assertEquals(0, h5.n)

        val h20 = horizons.first { it.days == 20 }
        assertEquals(0, h20.n)
    }

    @Test
    fun `복수 이벤트 평균`() {
        // 이벤트 2건: +10% 익일, +0% 익일 → 평균 5%, 승률 50%
        val prices = listOf(100, 110, 100, 100)
        val barsAsc = bars(prices)
        // idx 0(20260601): base=100, 1일 후=110(+10%)
        // idx 2(20260603): base=100, 1일 후=100(0%)
        val horizons = CatalystImpactService.computeHorizons(listOf("20260601", "20260603"), barsAsc)

        val h1 = horizons.first { it.days == 1 }
        assertEquals(2, h1.n)
        assertEquals(5.0, h1.avgPct)
        assertEquals(50.0, h1.winRatePct)
    }

    // ── buildCaveat ──────────────────────────────────────────────────────────

    @Test
    fun `표본 적으면 경고 포함`() {
        assertTrue(CatalystImpactService.buildCaveat(5).contains("불안정"))
        assertTrue(CatalystImpactService.buildCaveat(15).contains("참고"))
        assertFalse(CatalystImpactService.buildCaveat(30).contains("건 —"))
    }

    @Test
    fun `모든 caveat에 보장 문구 포함`() {
        listOf(0, 5, 15, 30, 100).forEach { n ->
            assertTrue(CatalystImpactService.buildCaveat(n).contains("보장하지 않습니다"))
        }
    }
}
