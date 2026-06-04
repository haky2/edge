package com.haky.edge.analysis

import com.haky.edge.model.InvestorFlow
import com.haky.edge.model.Quote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StockAnalysisTest {

    private fun flow(f: Long, i: Long) = InvestorFlow("2026", foreign = f, institution = i, individual = -(f + i))

    @Test
    fun streak_countsConsecutiveSameDirectionFromLatest() {
        // 최신일이 앞. 외국인: -100,-200,+300 → 순매도 2일(누적 -300)에서 끊김.
        // 기관: +50,+60,-10 → 순매수 2일(누적 +110)에서 끊김.
        val flows = listOf(flow(-100, 50), flow(-200, 60), flow(300, -10))
        val streaks = StockAnalysis.flowStreaks(flows)

        val foreign = streaks.first { it.investor == "외국인" }
        assertEquals(2, foreign.days)
        assertEquals(-300, foreign.net)
        assertFalse(foreign.buying)

        val inst = streaks.first { it.investor == "기관" }
        assertEquals(2, inst.days)
        assertEquals(110, inst.net)
        assertTrue(inst.buying)
    }

    @Test
    fun streak_zeroLatestMeansNoTrend() {
        assertNull(StockAnalysis.flowStreaks(listOf(flow(0, 100))).find { it.investor == "외국인" })
    }

    @Test
    fun priceContext_52wPositionAndDrawdown() {
        // 저점 100, 고점 200, 현재 150 → 범위 중앙(50%), 고점 대비 -25%, 저점 대비 +50%.
        val q = Quote(
            code = "000000", price = 150, change = 0, changeRate = 0.0, volume = 0,
            open = 0, high = 0, low = 0, high52w = 200, low52w = 100,
        )
        val ctx = StockAnalysis.priceContext(q)!!
        assertEquals(50.0, ctx.pctInRange52w)
        assertEquals(-25.0, ctx.pctFromHigh52w)
        assertEquals(50.0, ctx.pctFromLow52w)
    }

    @Test
    fun priceContext_nullWhenRangeInvalid() {
        val q = Quote("0", 100, 0, 0.0, 0, 0, 0, 0, high52w = 0, low52w = 0)
        assertNull(StockAnalysis.priceContext(q))
    }
}
