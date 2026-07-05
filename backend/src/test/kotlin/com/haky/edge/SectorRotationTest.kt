package com.haky.edge

import com.haky.edge.kis.IndexPoint
import com.haky.edge.kis.SectorHistory
import com.haky.edge.macro.SectorRotationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 섹터 자금 순환(C) 순수 계산 테스트. compute()만 검증(LLM·KIS 없음). */
class SectorRotationTest {

    // 최신일이 앞. compute는 index 0/5/20 종가만 읽으므로 그 셋만 지정하고 나머지는 양수 채움.
    private fun history(label: String, c0: Double, c5: Double, c20: Double, size: Int = SectorRotationService.MIN_POINTS): SectorHistory {
        val pts = MutableList(size) { i -> IndexPoint(date = "%08d".format(20260101 + (size - i)), close = 100.0) }
        if (size > 0) pts[0] = pts[0].copy(close = c0)
        if (size > SectorRotationService.SHORT) pts[SectorRotationService.SHORT] = pts[SectorRotationService.SHORT].copy(close = c5)
        if (size > SectorRotationService.LONG) pts[SectorRotationService.LONG] = pts[SectorRotationService.LONG].copy(close = c20)
        return SectorHistory(label, pts)
    }

    @Test
    fun `뚜렷한 순환 — 단기 가속 섹터 유입·둔화 섹터 이탈`() {
        // A: 5일 +10%, 20일 +2% (가속) / B: 5일 +1%, 20일 +8% (둔화) / C: 5일 +3%, 20일 +3% (중립)
        val r = SectorRotationService.compute(
            "2026-07-05",
            listOf(
                history("기계", c0 = 110.0, c5 = 100.0, c20 = 110.0 / 1.02),
                history("전기전자", c0 = 108.0, c5 = 108.0 / 1.01, c20 = 100.0),
                history("서비스업", c0 = 103.0, c5 = 100.0, c20 = 100.0),
            ),
        )
        assertEquals(listOf("기계"), r.inflow)
        assertEquals(listOf("전기전자"), r.outflow)
        val facts = assertNotNull(r.factsText)
        assertTrue(facts.contains("자금 유입 조짐"))
        assertTrue(facts.contains("자금 이탈 조짐"))
        // 정렬은 ret5 내림차순 → 기계(+10%) 첫 번째
        assertEquals("기계", r.sectors.first().label)
    }

    @Test
    fun `순위·순위변화 계산 정확`() {
        val r = SectorRotationService.compute(
            "2026-07-05",
            listOf(
                history("기계", c0 = 110.0, c5 = 100.0, c20 = 110.0 / 1.02),   // ret5 +10, ret20 +2
                history("전기전자", c0 = 108.0, c5 = 108.0 / 1.01, c20 = 100.0), // ret5 +1, ret20 +8
                history("서비스업", c0 = 103.0, c5 = 100.0, c20 = 100.0),        // ret5 +3, ret20 +3
            ),
        )
        val m = r.sectors.associateBy { it.label }
        // ret5 순위: 기계1·서비스업2·전기전자3
        assertEquals(1, m.getValue("기계").rank5)
        assertEquals(3, m.getValue("전기전자").rank5)
        // ret20 순위: 전기전자1·서비스업2·기계3
        assertEquals(1, m.getValue("전기전자").rank20)
        assertEquals(3, m.getValue("기계").rank20)
        // rankDelta = rank20 - rank5
        assertEquals(2, m.getValue("기계").rankDelta)      // 3-1 유입
        assertEquals(-2, m.getValue("전기전자").rankDelta) // 1-3 이탈
        assertEquals(0, m.getValue("서비스업").rankDelta)
    }

    @Test
    fun `순위 변화 없으면 신호 없음 — factsText null`() {
        // 세 섹터 순위가 5일·20일 동일(A>B>C) → rankDelta 전부 0
        val r = SectorRotationService.compute(
            "2026-07-05",
            listOf(
                history("A", c0 = 105.0, c5 = 100.0, c20 = 105.0 / 1.04),  // ret5 +5, ret20 +4
                history("B", c0 = 103.0, c5 = 100.0, c20 = 103.0 / 1.02),  // ret5 +3, ret20 +2
                history("C", c0 = 101.0, c5 = 100.0, c20 = 101.0 / 1.00),  // ret5 +1, ret20 0
            ),
        )
        assertTrue(r.inflow.isEmpty())
        assertTrue(r.outflow.isEmpty())
        assertNull(r.factsText)
        assertEquals(3, r.sectors.size)
    }

    @Test
    fun `봉 부족 섹터는 제외`() {
        val r = SectorRotationService.compute(
            "2026-07-05",
            listOf(
                history("기계", c0 = 110.0, c5 = 100.0, c20 = 107.0),
                history("전기전자", c0 = 108.0, c5 = 106.0, c20 = 100.0),
                history("짧은섹터", c0 = 110.0, c5 = 100.0, c20 = 100.0, size = 10), // MIN_POINTS 미만
            ),
        )
        assertEquals(2, r.sectors.size)
        assertTrue(r.sectors.none { it.label == "짧은섹터" })
    }

    @Test
    fun `유효 섹터 2개 미만이면 빈 결과`() {
        val r = SectorRotationService.compute(
            "2026-07-05",
            listOf(
                history("기계", c0 = 110.0, c5 = 100.0, c20 = 107.0),
                history("짧은섹터", c0 = 110.0, c5 = 100.0, c20 = 100.0, size = 5),
            ),
        )
        assertTrue(r.sectors.isEmpty())
        assertNull(r.factsText)
    }

    @Test
    fun `수익률 소수 첫째자리 반올림`() {
        val r = SectorRotationService.compute(
            "2026-07-05",
            listOf(
                history("기계", c0 = 112.34, c5 = 100.0, c20 = 105.0),
                history("전기전자", c0 = 100.0, c5 = 108.0, c20 = 110.0),
            ),
        )
        val 기계 = r.sectors.first { it.label == "기계" }
        assertEquals(12.3, 기계.ret5) // 12.34% → 12.3
    }
}
