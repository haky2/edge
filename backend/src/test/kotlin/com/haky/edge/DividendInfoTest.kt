package com.haky.edge

import com.haky.edge.ai.dividendText
import com.haky.edge.dart.DartAlotRow
import com.haky.edge.dart.DividendInfo
import com.haky.edge.dart.DividendMath
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 배당 정보 — 최신 연도 게이트·보통주 추출(우선주/무배당 배제)·facts 렌더 순수 함수 검증.
 * 예상 수익률은 최신 주당배당금 ÷ 현재가(차기 배당 미확정)라는 계약이 핵심.
 */
class DividendInfoTest {

    // ── 최신 사업연도 게이트 ────────────────────────────────────────────────────

    @Test
    fun `최신연도 - 4월 이후면 전년(사업보고서 제출됨)`() {
        assertEquals(2025, DividendMath.latestFiledYear(LocalDate.parse("2026-07-19")))
        assertEquals(2025, DividendMath.latestFiledYear(LocalDate.parse("2026-04-01")))
    }

    @Test
    fun `최신연도 - 3월 이전이면 전전년(사업보고서 미제출 기간)`() {
        assertEquals(2024, DividendMath.latestFiledYear(LocalDate.parse("2026-03-15")))
        assertEquals(2024, DividendMath.latestFiledYear(LocalDate.parse("2026-01-02")))
    }

    // ── 추출 ────────────────────────────────────────────────────────────────────

    private fun alot(se: String, knd: String = "", th: String, fr: String = "-", lw: String = "-", stlm: String? = "2025-12-31") =
        DartAlotRow(se = se, stockKind = knd, thisTerm = th, prevTerm = fr, prev2Term = lw, settleDate = stlm)

    @Test
    fun `추출 - 보통주 주당배당금 3년 추이, 우선주 행 무시`() {
        val d = DividendMath.extract(listOf(
            alot("주당 현금배당금(원)", "보통주", "1,668", "1,446", "1,444"),
            alot("주당 현금배당금(원)", "우선주", "1,669", "1,447", "1,445"),
            alot("현금배당수익률(%)", "보통주", "2.38"),
            alot("현금배당수익률(%)", "우선주", "3.30"),
            alot("(연결)현금배당성향(%)", "", "29.2"),
            alot("(연결)주당순이익(원)", "", "6,605"),
        ))!!
        assertEquals(2025, d.fiscalYear)
        assertEquals(1668L, d.dpsThis)
        assertEquals(1446L, d.dpsPrev)
        assertEquals(1444L, d.dpsPrev2)
        assertEquals(2.38, d.yieldPctAtRecord)   // 보통주 수익률(우선주 3.30 아님)
        assertEquals(29.2, d.payoutPct)
        assertEquals(6605L, d.epsThis)
    }

    @Test
    fun `추출 - stock_knd 앞 공백 정규화`() {
        val d = DividendMath.extract(listOf(
            alot("주당 현금배당금(원)", " 보통주", "1,780"),
        ))!!
        assertEquals(1780L, d.dpsThis)
    }

    @Test
    fun `추출 - 무배당(주당배당금 대시)이면 null`() {
        assertNull(DividendMath.extract(listOf(
            alot("주당 현금배당금(원)", "보통주", "-", "-", "-"),
        )))
    }

    @Test
    fun `추출 - 보통주 주당배당금 행 없으면 null(우선주만)`() {
        assertNull(DividendMath.extract(listOf(
            alot("주당 현금배당금(원)", "우선주", "500"),
        )))
    }

    @Test
    fun `추출 - 배당성향 연결 우선(별도보다 앞)`() {
        val d = DividendMath.extract(listOf(
            alot("주당 현금배당금(원)", "보통주", "1,000"),
            alot("(별도)현금배당성향(%)", "", "50.0"),
            alot("(연결)현금배당성향(%)", "", "30.0"),
        ))!!
        assertEquals(30.0, d.payoutPct)
    }

    @Test
    fun `추출 - fiscalYear는 결산기준일에서(질의연도 아님)`() {
        val d = DividendMath.extract(listOf(
            alot("주당 현금배당금(원)", "보통주", "5,350", stlm = "2024-12-31"),
        ))!!
        assertEquals(2024, d.fiscalYear)
    }

    @Test
    fun `dpsYoyPct - 전년 있으면 증감률, 없으면 null`() {
        assertEquals(100.0, DividendInfo(2025, dpsThis = 2000, dpsPrev = 1000).dpsYoyPct)
        assertNull(DividendInfo(2025, dpsThis = 2000).dpsYoyPct)
    }

    // ── 렌더 ────────────────────────────────────────────────────────────────────

    @Test
    fun `렌더 - 추이 + YoY + 현재가 기준 예상수익률 + 참고`() {
        val text = dividendText(
            DividendInfo(
                fiscalYear = 2025, dpsThis = 1668, dpsPrev = 1446, dpsPrev2 = 1444,
                yieldPctAtRecord = 2.6, payoutPct = 29.2, epsThis = 6605, settleDate = "2025-12-31",
            ),
            currentPrice = 70_000,
        )!!
        assertTrue(text.contains("주당 현금배당금: 2023 1,444원 → 2024 1,446원 → 2025 1,668원 (전년 대비 +15.4%)"), text)
        assertTrue(text.contains("현재가(70,000원) 기준 예상 배당수익률: 2.38%"), text)  // 1668/70000
        assertTrue(text.contains("차기 배당 미확정"), text)
        assertTrue(text.contains("배당 시점 시가배당률 2.6%"), text)
        assertTrue(text.contains("현금배당성향 29.2%"), text)
        assertTrue(text.contains("결산월 12월"), text)
    }

    @Test
    fun `렌더 - 전년 없으면 YoY 생략, 추이 단축`() {
        val text = dividendText(DividendInfo(2025, dpsThis = 500, settleDate = "2025-12-31"), 10_000)!!
        assertTrue(text.contains("주당 현금배당금: 2025 500원"), text)
        assertFalse(text.contains("전년 대비"))
    }

    @Test
    fun `렌더 - null이면 null, 현재가 0이면 예상수익률 생략`() {
        assertNull(dividendText(null, 70_000))
        val text = dividendText(DividendInfo(2025, dpsThis = 1000, settleDate = "2025-12-31"), 0)!!
        assertFalse(text.contains("예상 배당수익률"))
    }

    private fun assertFalse(cond: Boolean) = assertTrue(!cond)
}
