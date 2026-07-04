package com.haky.edge

import com.haky.edge.ai.EarningsPreviewService
import com.haky.edge.dart.QuarterlyIncome
import com.haky.edge.kis.DailyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F3 슬라이스 3a — 과거 발표 반응 통계 + run-rate YoY 순수 함수. */
class EarningsPreviewTest {

    /** dayOffset(0부터)씩 커지는 날짜의 오름차순 일봉. */
    private fun bars(closes: List<Long>): List<DailyBar> =
        closes.mapIndexed { i, c ->
            DailyBar(date = "%08d".format(20260101 + i), open = c, high = c, low = c, close = c, volume = 100)
        }

    // ── computeReactions ───────────────────────────────────────────────

    @Test
    fun `접수일 다음 거래일과 5거래일 수익률`() {
        // 기준봉=idx2(20260103, 100), day1=idx3(110 → +10%), day5=idx7(120 → +20%)
        val asc = bars(listOf(100, 100, 100, 110, 100, 100, 100, 120, 100, 100))
        val r = EarningsPreviewService.computeReactions(listOf("20260103"), asc)
        assertNotNull(r)
        assertEquals(1, r.n)
        assertEquals(10.0, r.day1AvgPct)
        assertEquals(20.0, r.day5AvgPct)
        assertEquals(100.0, r.day1WinRatePct)
    }

    @Test
    fun `접수일이 휴장일이면 직전 거래일이 기준봉`() {
        // 20260104가 이력에 없음(휴장, 날짜를 05부터 이어붙임) → 기준봉은 20260103(idx2, 100)
        val asc = bars(listOf(100, 100, 100)) +
            listOf(110L, 100L, 100L, 100L, 100L).mapIndexed { i, c ->
                DailyBar(date = "%08d".format(20260105 + i), open = c, high = c, low = c, close = c, volume = 100)
            }
        val r = EarningsPreviewService.computeReactions(listOf("20260104"), asc)
        assertNotNull(r)
        assertEquals(10.0, r.day1AvgPct)
    }

    @Test
    fun `여러 발표 평균과 승률`() {
        // 발표1 기준 idx1: day1 +10% / 발표2 기준 idx5: day1 -10%
        val asc = bars(listOf(100, 100, 110, 100, 100, 100, 90, 100, 100, 100, 100, 100))
        val r = EarningsPreviewService.computeReactions(listOf("20260106", "20260102"), asc)
        assertNotNull(r)
        assertEquals(2, r.n)
        assertEquals(0.0, r.day1AvgPct)      // (+10 + -10)/2
        assertEquals(50.0, r.day1WinRatePct)
    }

    @Test
    fun `이력 범위 밖 발표는 건너뜀, 전부 밖이면 null`() {
        val asc = bars(listOf(100, 110, 100, 100, 100, 100, 100))
        assertNull(EarningsPreviewService.computeReactions(listOf("20250101"), asc))
        // 하나는 범위 안 → n=1
        val r = EarningsPreviewService.computeReactions(listOf("20260101", "20250101"), asc)
        assertNotNull(r)
        assertEquals(1, r.n)
    }

    @Test
    fun `직전 발표 - 익일 봉 없으면 제외`() {
        val asc = bars(listOf(100, 100, 100, 100, 100, 100, 100))
        // 마지막 봉 날짜에 발표 → day1 없음 → 그 발표는 카운트 안 됨
        assertNull(EarningsPreviewService.computeReactions(listOf("20260107"), asc))
    }

    @Test
    fun `빈 입력 방어`() {
        assertNull(EarningsPreviewService.computeReactions(emptyList(), bars(listOf(100, 100, 100, 100, 100, 100, 100, 100))))
        assertNull(EarningsPreviewService.computeReactions(listOf("20260101"), emptyList()))
    }

    // ── runRateYoY ─────────────────────────────────────────────────────

    private fun q(label: String, ni: Long?) = QuarterlyIncome(label = label, netIncome = ni, netIncomePrev = null, yoyPct = null)

    @Test
    fun `1분기 x4, 반기 x2, 3분기 x4over3 연환산 YoY`() {
        // 1Q 300 → 연환산 1200 vs 작년 1000 → +20%
        assertEquals(20.0, EarningsPreviewService.runRateYoY(q("2026년 1분기", 300), 1000, 2025).first)
        // 반기 600 → 1200 vs 1000 → +20%
        assertEquals(20.0, EarningsPreviewService.runRateYoY(q("2026년 반기", 600), 1000, 2025).first)
        // 3Q 900 → 1200 vs 1000 → +20%
        assertEquals(20.0, EarningsPreviewService.runRateYoY(q("2026년 3분기", 900), 1000, 2025).first)
    }

    @Test
    fun `run-rate 하락도 대칭`() {
        assertEquals(-40.0, EarningsPreviewService.runRateYoY(q("2026년 1분기", 150), 1000, 2025).first)
    }

    @Test
    fun `적자·데이터 부족은 null`() {
        assertNull(EarningsPreviewService.runRateYoY(q("2026년 1분기", -100), 1000, 2025).first)
        assertNull(EarningsPreviewService.runRateYoY(q("2026년 1분기", 300), null, null).first)
        assertNull(EarningsPreviewService.runRateYoY(q("2026년 1분기", 300), -500, 2025).first)
        assertNull(EarningsPreviewService.runRateYoY(null, 1000, 2025).first)
    }

    @Test
    fun `라벨에 근거 표기`() {
        val (_, label) = EarningsPreviewService.runRateYoY(q("2026년 1분기", 300), 1000, 2025)
        assertEquals("2026년 1분기 누적 연환산 vs 2025년 연간", label)
    }

    @Test
    fun `caveat - 소표본이면 참고 수준 문구`() {
        val r = com.haky.edge.ai.PastReactions(n = 6, day1AvgPct = -1.2, day5AvgPct = 0.5, day1WinRatePct = 33.3)
        val c = EarningsPreviewService.buildCaveat(r)
        assertTrue(c.contains("표본 6건"))
        assertTrue(c.contains("참고 수준"))
        assertTrue(c.contains("계절성 미반영"))
    }

    // ── 3c: reviewPlan / reviewVerdict ─────────────────────────────────

    @Test
    fun `reviewPlan - 보고서별 직전 보고서와 배수 매핑`() {
        val q1 = EarningsPreviewService.reviewPlan("분기보고서 (2026.03)")
        assertNotNull(q1)
        assertEquals("11013" to "11011", q1.reprtCode to q1.priorReprtCode)
        assertEquals(2025, q1.priorYear)
        assertEquals(0.25, q1.factor)

        val h1 = EarningsPreviewService.reviewPlan("반기보고서 (2026.06)")
        assertNotNull(h1)
        assertEquals(2.0, h1.factor)
        assertEquals(2026, h1.priorYear)

        val q3 = EarningsPreviewService.reviewPlan("분기보고서 (2026.09)")
        assertNotNull(q3)
        assertEquals(1.5, q3.factor)

        val annual = EarningsPreviewService.reviewPlan("사업보고서 (2026.12)")
        assertNotNull(annual)
        assertEquals(4.0 / 3.0, annual.factor)
    }

    @Test
    fun `reviewPlan - 정정·패턴 불일치는 null`() {
        assertNull(EarningsPreviewService.reviewPlan("[기재정정]분기보고서 (2026.03)"))
        assertNull(EarningsPreviewService.reviewPlan("주요사항보고서"))
        assertNull(EarningsPreviewService.reviewPlan("분기보고서"))
    }

    @Test
    fun `reviewVerdict - 상회 부합 하회`() {
        val plan = EarningsPreviewService.reviewPlan("반기보고서 (2026.06)")!! // 예상 = 1Q × 2
        // 1Q 500억 → 예상 반기 1000억
        val prior = 500L * 100_000_000
        fun run(actualEok: Long) = EarningsPreviewService.reviewVerdict(plan, actualEok * 100_000_000, prior)

        assertEquals("상회", run(1200)!!.verdict)   // +20%
        assertEquals("부합", run(1050)!!.verdict)   // +5%
        assertEquals("하회", run(850)!!.verdict)    // -15%
        assertEquals(20.0, run(1200)!!.diffPct)
        assertEquals(1200, run(1200)!!.actualEok)
        assertEquals(1000, run(1200)!!.expectedEok)
    }

    @Test
    fun `reviewVerdict - 적자 구간은 null`() {
        val plan = EarningsPreviewService.reviewPlan("반기보고서 (2026.06)")!!
        assertNull(EarningsPreviewService.reviewVerdict(plan, -100, 1000))
        assertNull(EarningsPreviewService.reviewVerdict(plan, 1000, -100))
        assertNull(EarningsPreviewService.reviewVerdict(plan, 0, 1000))
    }
}
