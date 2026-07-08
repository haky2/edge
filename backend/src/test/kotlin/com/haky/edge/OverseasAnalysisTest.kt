package com.haky.edge

import com.haky.edge.ai.OverseasAnalysisService
import com.haky.edge.kis.OverseasQuote
import com.haky.edge.news.NewsItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OverseasAnalysisTest {

    private fun quote(
        price: Double = 213.55,
        change: Double = 1.23,
        changeRate: Double = 0.58,
        high52w: Double = 237.23,
        low52w: Double = 164.08,
        currency: String = "USD",
    ) = OverseasQuote(
        code = "US:NAS:AAPL", symb = "AAPL", price = price,
        change = change, changeRate = changeRate,
        open = 212.10, high = 214.00, low = 211.80,
        high52w = high52w, low52w = low52w,
        volume = 43_210_000, currency = currency,
    )

    // ── priceText: 통화 기호 + 크기별 소수 자릿수 ──────────────────────

    @Test
    fun `priceText - USD는 달러 기호와 100 이상 2자리`() {
        assertEquals("\$213.55", OverseasAnalysisService.priceText(213.55, "USD"))
    }

    @Test
    fun `priceText - 10 미만은 4자리, 100 미만은 3자리`() {
        assertEquals("\$9.1235", OverseasAnalysisService.priceText(9.1235, "USD"))
        assertEquals("\$99.123", OverseasAnalysisService.priceText(99.123, "USD"))
    }

    @Test
    fun `priceText - USD 외 통화는 코드 접두`() {
        assertEquals("HKD 320.40", OverseasAnalysisService.priceText(320.40, "HKD"))
    }

    @Test
    fun `priceText - 천 단위 쉼표`() {
        assertEquals("\$1,234.56", OverseasAnalysisService.priceText(1234.56, "USD"))
    }

    // ── position52w ────────────────────────────────────────────────

    @Test
    fun `position52w - 중간 위치 계산`() {
        // (213.55-164.08)/(237.23-164.08) = 67.6% → 67
        assertEquals(67, OverseasAnalysisService.position52w(213.55, 237.23, 164.08))
    }

    @Test
    fun `position52w - 고점과 저점이 같으면(데이터 불량) null`() {
        assertNull(OverseasAnalysisService.position52w(100.0, 50.0, 50.0))
        assertNull(OverseasAnalysisService.position52w(100.0, 0.0, 0.0))
    }

    @Test
    fun `position52w - 범위 밖 가격은 0~100으로 클램프`() {
        assertEquals(100, OverseasAnalysisService.position52w(300.0, 237.23, 164.08))
        assertEquals(0, OverseasAnalysisService.position52w(100.0, 237.23, 164.08))
    }

    // ── buildFacts ─────────────────────────────────────────────────

    @Test
    fun `buildFacts - 핵심 요소 전부 포함`() {
        val facts = OverseasAnalysisService.buildFacts(
            name = "애플", nameEn = "Apple Inc", q = quote(),
            news = listOf(NewsItem("아이폰 신제품 공개", "설명", "yna.co.kr", "https://x", "Wed, 08 Jul 2026 09:30:00 +0900")),
            marketStatus = "미국 장 마감 후",
        )
        assertTrue(facts.contains("애플 (Apple Inc, AAPL"))
        assertTrue(facts.contains("미국 장 마감 후"))
        assertTrue(facts.contains("15분 지연"))
        assertTrue(facts.contains("\$213.55"))
        assertTrue(facts.contains("+0.58%"))
        assertTrue(facts.contains("52주 범위의 약 67%"))
        assertTrue(facts.contains("[yna.co.kr, 7/8] 아이폰 신제품 공개"))
    }

    @Test
    fun `buildFacts - 뉴스 없으면 없음 표기`() {
        val facts = OverseasAnalysisService.buildFacts("애플", "Apple Inc", quote(), emptyList(), "미국 장 중")
        assertTrue(facts.contains("[관련 뉴스] 없음"))
    }

    @Test
    fun `buildFacts - 하락 종목은 음수 부호 그대로`() {
        val facts = OverseasAnalysisService.buildFacts(
            "애플", "Apple Inc", quote(change = -2.50, changeRate = -1.16), emptyList(), "미국 장 중",
        )
        assertTrue(facts.contains("-2.50, -1.16%"))
        assertFalse(facts.contains("+-"))
    }

    @Test
    fun `buildFacts - 52주 데이터 불량이면 위치 문구 생략`() {
        val facts = OverseasAnalysisService.buildFacts(
            "애플", "Apple Inc", quote(high52w = 0.0, low52w = 0.0), emptyList(), "미국 장 중",
        )
        assertFalse(facts.contains("52주 범위의 약"))
    }

    // ── formatNewsDate ─────────────────────────────────────────────

    @Test
    fun `formatNewsDate - RFC1123 파싱`() {
        assertEquals("7/8", OverseasAnalysisService.formatNewsDate("Wed, 08 Jul 2026 09:30:00 +0900"))
    }

    @Test
    fun `formatNewsDate - 파싱 실패 시 빈 문자열`() {
        assertEquals("", OverseasAnalysisService.formatNewsDate("not-a-date"))
        assertEquals("", OverseasAnalysisService.formatNewsDate(""))
    }
}
