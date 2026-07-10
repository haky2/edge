package com.haky.edge

import com.haky.edge.ai.TradeReviewService
import com.haky.edge.kis.DailyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TradeReviewService의 가격 경로 계산·캐시 키 — 전부 룰 계산이라 순수 유닛으로 검증. */
class TradeReviewTest {

    /** 최신이 앞(서비스 입력과 동일). 6/2~6/13 사이 8거래일. */
    private fun bars(): List<DailyBar> = listOf(
        bar("20260613", 120),
        bar("20260612", 110),
        bar("20260611", 105),
        bar("20260610", 130),  // 구간 최고
        bar("20260609", 100),
        bar("20260605", 90),   // 구간 최저
        bar("20260604", 95),
        bar("20260602", 100),
    )

    private fun bar(date: String, close: Long) = DailyBar(date, close, close + 5, close - 5, close, 1000)

    @Test
    fun `구간 수익률과 보유 거래일`() {
        val p = TradeReviewService.computePath(bars(), "2026-06-02", 100.0, "2026-06-10", 130.0)
        assertEquals(30.0, p.realizedPct, 0.01)
        assertEquals(5, p.holdingTradingDays)   // 구간 [0602,0610] = 6/2·4·5·9·10 5봉
    }

    @Test
    fun `구간 최고·최저 종가와 날짜`() {
        val p = TradeReviewService.computePath(bars(), "2026-06-02", 100.0, "2026-06-13", 120.0)
        assertEquals(130L, p.highClose)
        assertEquals("2026-06-10", p.highDate)
        assertEquals(90L, p.lowClose)
        assertEquals("2026-06-05", p.lowDate)
        // 매도가 120 vs 최고 종가 130 → -7.7%
        assertEquals(-7.69, p.sellVsHighPct!!, 0.01)
    }

    @Test
    fun `매도 후 추이 - 5거래일 미만이면 20일은 null`() {
        // 매도 6/9 → 이후 봉 4개(6/10,11,12,13) — 5거래일째 없음
        val p = TradeReviewService.computePath(bars(), "2026-06-02", 100.0, "2026-06-09", 100.0)
        assertNull(p.after5dPct)
        assertNull(p.after20dPct)
        assertEquals(4, p.afterAvailableDays)
    }

    @Test
    fun `매도 후 5거래일 추이 계산`() {
        // 매도 6/2(100원) → 이후 7봉, 5거래일째 = 6/11(105) → +5%
        val p = TradeReviewService.computePath(bars(), "2026-06-02", 100.0, "2026-06-02", 100.0)
        assertEquals(5.0, p.after5dPct!!, 0.01)
        assertNull(p.after20dPct)
    }

    @Test
    fun `비거래일 기록은 구간 안 거래일만 잡힌다`() {
        // 6/6(토)~6/8(일) 기록 — 구간 내 거래일 0 → 고저 null, 크래시 없음
        val p = TradeReviewService.computePath(bars(), "2026-06-06", 100.0, "2026-06-08", 101.0)
        assertEquals(0, p.holdingTradingDays)
        assertNull(p.highClose)
        assertNull(p.sellVsHighPct)
        assertEquals(1.0, p.realizedPct, 0.01)  // 수익률은 기록가 기준이라 그대로
    }

    @Test
    fun `이력 범위 밖 매수일은 partialHistory`() {
        val p = TradeReviewService.computePath(bars(), "2026-01-05", 80.0, "2026-06-13", 120.0)
        assertTrue(p.partialHistory)
        assertEquals(8, p.holdingTradingDays)   // 잡힌 구간(전체 8봉)만
    }

    @Test
    fun `캐시 키 - 사유·논지 변경 시 분리, 동일 입력은 동일`() {
        fun key(buyReason: String?, thesis: String?) = TradeReviewService.buildKey(
            "2026-07-10", "005930", "2026-06-02", 100.0, "2026-06-10", 130.0, 10L, buyReason, "익절", thesis,
        )
        assertEquals(key("수급", null), key("수급", null))
        assertNotEquals(key("수급", null), key("실적", null))
        assertNotEquals(key("수급", null), key("수급", "논지"))
        assertTrue(key("수급", null).startsWith("005930:2026-07-10:r"))
    }

    @Test
    fun `facts에 사후 추이 미완·주관 기록 라벨 포함`() {
        val p = TradeReviewService.computePath(bars(), "2026-06-02", 100.0, "2026-06-09", 100.0)
        val facts = TradeReviewService.buildFacts(
            "005930", "삼성전자", "2026-06-02", 100.0, "2026-06-09", 100.0, 10L,
            "외인 연속 순매수", "목표가 도달", "AI 수요로 메모리 슈퍼사이클", p,
        )
        assertTrue("주관이며 사실 아님" in facts)
        assertTrue("20거래일은 아직" in facts || "아직 없음" in facts)
        assertTrue("메모리 슈퍼사이클" in facts)
        assertTrue("보유 4거래일" in facts)      // 구간 [0602,0609] = 6/2·4·5·9 4봉
    }
}
