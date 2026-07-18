package com.haky.edge

import com.haky.edge.ai.leadingIndicatorsText
import com.haky.edge.dart.DartAllAcntRow
import com.haky.edge.dart.LeadingIndicatorMath
import com.haky.edge.dart.LeadingIndicators
import com.haky.edge.dart.LeadingQuarter
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 수주·재고 선행지표 — 분기 시퀀스·계정 추출·facts 렌더 순수 함수 검증.
 * 계약부채(수주잔고 근사) 유동+비유동 합산, 선수금 폴백, 결측 생략 규칙이 핵심.
 */
class LeadingIndicatorsTest {

    // ── 분기 시퀀스(법정 제출 기한 게이트) ─────────────────────────────────────

    @Test
    fun `시퀀스 - 7월이면 당해 1Q까지, 오래된 순 5개`() {
        val seq = LeadingIndicatorMath.periodSequence(LocalDate.parse("2026-07-18"))
        assertEquals(listOf(2025 to 1, 2025 to 2, 2025 to 3, 2025 to 4, 2026 to 1), seq)
    }

    @Test
    fun `시퀀스 - 2월이면 전년 3Q가 최신(사업보고서 미제출 기간)`() {
        val seq = LeadingIndicatorMath.periodSequence(LocalDate.parse("2026-02-10"))
        assertEquals(2025 to 3, seq.last())
        assertEquals(2024 to 3, seq.first())
    }

    @Test
    fun `시퀀스 - 4월이면 전년 사업보고서(4Q)가 최신`() {
        assertEquals(2025 to 4, LeadingIndicatorMath.periodSequence(LocalDate.parse("2026-04-10")).last())
    }

    @Test
    fun `시퀀스 - 11월이면 당해 3Q가 최신`() {
        assertEquals(2026 to 3, LeadingIndicatorMath.periodSequence(LocalDate.parse("2026-11-20")).last())
    }

    @Test
    fun `reprtCode 매핑`() {
        assertEquals("11013", LeadingIndicatorMath.reprtCode(1))
        assertEquals("11012", LeadingIndicatorMath.reprtCode(2))
        assertEquals("11014", LeadingIndicatorMath.reprtCode(3))
        assertEquals("11011", LeadingIndicatorMath.reprtCode(4))
    }

    // ── 계정 추출 ─────────────────────────────────────────────────────────────

    private fun bs(name: String, amount: String, id: String = "-표준계정코드 미사용-") =
        DartAllAcntRow(sjDiv = "BS", accountId = id, accountName = name, thisAmount = amount)

    @Test
    fun `추출 - 계약부채 유동+비유동 합산, 확정계약부채(헤지 항목)는 제외`() {
        val q = LeadingIndicatorMath.extract(listOf(
            bs("유동 계약부채", "1,000"),
            bs("비유동 계약부채", "500"),
            bs("확정계약부채", "999", id = "dart_CurrentFirmCommitmentLiabilities"),
            bs("재고자산", "300"),
        ), 2026, 1)
        assertEquals(1500L, q.contractLiabilities)
        assertFalse(q.contractLiabIsAdvance)
        assertEquals(300L, q.inventories)
    }

    @Test
    fun `추출 - 계약부채 없으면 선수금 폴백 + 플래그, 선수수익은 미매칭`() {
        val q = LeadingIndicatorMath.extract(listOf(
            bs("선수금", "700"),
            bs("선수수익", "999"),
        ), 2026, 1)
        assertEquals(700L, q.contractLiabilities)
        assertTrue(q.contractLiabIsAdvance)
    }

    @Test
    fun `추출 - 재고자산 정확 일치 우선(하위 항목 오매칭 방지)`() {
        val q = LeadingIndicatorMath.extract(listOf(
            bs("재고자산평가손실충당금", "-50"),
            bs("재고자산", "800"),
        ), 2026, 1)
        assertEquals(800L, q.inventories)
    }

    @Test
    fun `추출 - 매출채권 변형 계정명 contains 매칭`() {
        val q = LeadingIndicatorMath.extract(listOf(bs("매출채권 및 기타유동채권", "1,234")), 2026, 1)
        assertEquals(1234L, q.tradeReceivables)
    }

    @Test
    fun `추출 - 매출 누적은 add 필드 우선, 없으면 thstrm 폴백`() {
        val withAdd = DartAllAcntRow(sjDiv = "IS", accountName = "매출액", thisAmount = "5,000", thisAddAmount = "13,000")
        assertEquals(13000L, LeadingIndicatorMath.extract(listOf(withAdd), 2025, 2).revenueCum)
        val noAdd = DartAllAcntRow(sjDiv = "CIS", accountName = "수익(매출액)", thisAmount = "5,000")
        assertEquals(5000L, LeadingIndicatorMath.extract(listOf(noAdd), 2025, 1).revenueCum)
    }

    @Test
    fun `추출 - BS 지표 전무하면 hasBalanceMetric=false (금융업 게이트)`() {
        val q = LeadingIndicatorMath.extract(listOf(
            DartAllAcntRow(sjDiv = "IS", accountName = "영업수익", thisAmount = "100"),
        ), 2026, 1)
        assertFalse(q.hasBalanceMetric)
    }

    // ── 렌더 ─────────────────────────────────────────────────────────────────

    private fun lq(year: Int, quarter: Int, contract: Long? = null, inv: Long? = null, recv: Long? = null, rev: Long? = null) =
        LeadingQuarter(year, quarter, inventories = inv, contractLiabilities = contract, tradeReceivables = recv, revenueCum = rev)

    @Test
    fun `렌더 - 시리즈 + 정확히 1년 차이일 때만 전년 동기 대비`() {
        val text = leadingIndicatorsText(LeadingIndicators(listOf(
            lq(2025, 1, contract = 100_0e8.toLong(), rev = 200_0e8.toLong()),
            lq(2025, 2, contract = 110_0e8.toLong()),
            lq(2025, 3, contract = 120_0e8.toLong()),
            lq(2025, 4, contract = 130_0e8.toLong()),
            lq(2026, 1, contract = 150_0e8.toLong(), rev = 260_0e8.toLong()),
        )))!!
        assertTrue(text.contains("계약부채: 2025.1Q 1,000 → 2025.2Q 1,100 → 2025.3Q 1,200 → 2025.4Q 1,300 → 2026.1Q 1,500"))
        assertTrue(text.contains("(전년 동기 대비 +50.0%)"))
        // 매출은 시리즈가 아니라 전년 동기 1점 비교
        assertTrue(text.contains("매출액(연초 누적): 2026.1Q 2,600 vs 전년 동기 2,000 (+30.0%)"))
    }

    @Test
    fun `렌더 - 첫·끝이 같은 분기 아니면 YoY 생략(계절성 오독 방지)`() {
        val text = leadingIndicatorsText(LeadingIndicators(listOf(
            lq(2025, 2, contract = 100_0e8.toLong()),
            lq(2026, 1, contract = 150_0e8.toLong()),
        )))!!
        assertFalse(text.contains("전년 동기 대비"))
    }

    @Test
    fun `렌더 - 포인트 2개 미만 지표는 줄 생략, 전부 없으면 null`() {
        val text = leadingIndicatorsText(LeadingIndicators(listOf(
            lq(2025, 4, contract = 100_0e8.toLong(), inv = 50_0e8.toLong()),
            lq(2026, 1, contract = 120_0e8.toLong()),
        )))!!
        assertTrue(text.contains("계약부채"))
        assertFalse(text.contains("재고자산")) // 1포인트 → 생략
        assertNull(leadingIndicatorsText(LeadingIndicators(listOf(lq(2026, 1, contract = 100L)))))
        assertNull(leadingIndicatorsText(null))
    }

    @Test
    fun `렌더 - 선수금 폴백이면 라벨 표기`() {
        val text = leadingIndicatorsText(LeadingIndicators(listOf(
            LeadingQuarter(2025, 4, contractLiabilities = 100_0e8.toLong(), contractLiabIsAdvance = true),
            LeadingQuarter(2026, 1, contractLiabilities = 120_0e8.toLong(), contractLiabIsAdvance = true),
        )))!!
        assertTrue(text.contains("선수금(계약부채 미표기 회사, 같은 성격)"))
    }
}
