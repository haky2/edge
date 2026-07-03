package com.haky.edge

import com.haky.edge.dart.DartFinanceRow
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-07 감사 수정 회귀 테스트 — H1(수급 캐시 버킷)·H2(DART 누적 금액). */
class AuditFixesTest {

    // ── H1: 장마감 전/후 버킷 + post 버킷 캐시 조건 ──────────────────────

    @Test
    fun `16시 전은 pre, 후는 post`() {
        assertFalse(KisClient.isPostClose(LocalTime.of(9, 0)))
        assertFalse(KisClient.isPostClose(LocalTime.of(15, 59)))
        assertTrue(KisClient.isPostClose(LocalTime.of(16, 0)))
        assertTrue(KisClient.isPostClose(LocalTime.of(18, 0)))
    }

    private fun flow(date: String) = InvestorFlow(date = date, foreign = 100, institution = -50, individual = -50)

    @Test
    fun `pre 버킷은 항상 캐시 가능`() {
        assertTrue(KisClient.isCacheableInvestorFlows(postClose = false, flows = listOf(flow("20260703")), effectiveDate = "2026-07-06"))
    }

    @Test
    fun `post 버킷 평일 - 오늘 확정 행이 있어야 캐시`() {
        // 2026-07-06 = 월요일. 최신 행이 전 거래일(금)뿐이면 아직 미확정 → 캐시 금지
        assertFalse(KisClient.isCacheableInvestorFlows(true, listOf(flow("20260703")), "2026-07-06"))
        // 오늘 행 도착 → 캐시 허용
        assertTrue(KisClient.isCacheableInvestorFlows(true, listOf(flow("20260706"), flow("20260703")), "2026-07-06"))
        // 빈 응답도 캐시 금지(재조회 유도)
        assertFalse(KisClient.isCacheableInvestorFlows(true, emptyList(), "2026-07-06"))
    }

    @Test
    fun `post 버킷 주말은 오늘 행이 없어도 캐시`() {
        // 2026-07-04 = 토요일(일요일은 effectiveMarketDate가 토요일을 돌려줌) — 최신 행은 금요일
        assertTrue(KisClient.isCacheableInvestorFlows(true, listOf(flow("20260703")), "2026-07-04"))
    }

    // ── H2: DART 분기/반기 누적 금액 파싱 ────────────────────────────────

    @Test
    fun `반기 보고서 - add 필드가 누적, thstrm은 3개월치`() {
        // 삼성전자 2025 반기 실측값 구조: thstrm 5.1조(2Q 3개월) vs add 13.3조(상반기 누적)
        val row = DartFinanceRow(
            accountName = "당기순이익(손실)",
            thisAmount = "5,116,435,000,000",
            prevAmount = "9,841,345,000,000",
            thisAddAmount = "13,339,313,000,000",
            prevAddAmount = "16,596,053,000,000",
        )
        assertEquals(13_339_313_000_000L, row.thisCumulative())
        assertEquals(16_596_053_000_000L, row.prevCumulative())
        // 3개월치 접근도 그대로 가능(다른 소비처 하위호환)
        assertEquals(5_116_435_000_000L, row.thisAmount())
    }

    @Test
    fun `연간 보고서 - add 필드 없으면 thstrm 폴백`() {
        val row = DartFinanceRow(
            accountName = "당기순이익",
            thisAmount = "34,451,351,000,000",
            prevAmount = "15,487,100,000,000",
        )
        assertEquals(34_451_351_000_000L, row.thisCumulative())
        assertEquals(15_487_100_000_000L, row.prevCumulative())
    }

    @Test
    fun `1분기 보고서 - add와 thstrm 동일`() {
        val row = DartFinanceRow(
            accountName = "분기순이익",
            thisAmount = "47,225,272,000,000",
            thisAddAmount = "47,225,272,000,000",
            prevAmount = "8,222,878,000,000",
        )
        assertEquals(row.thisAmount(), row.thisCumulative())
    }
}
