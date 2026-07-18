package com.haky.edge

import com.haky.edge.ai.JudgmentComparisonService
import com.haky.edge.ai.JudgmentTrade
import com.haky.edge.ai.StanceEntry
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.IndexPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 판단 대조 — 내 매매 vs AI 스탠스 반사실 채점 순수 함수(score). */
class JudgmentComparisonTest {

    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    private val start = LocalDate.parse("2026-06-01")

    /** 2026-06-01부터 30일 연속 봉. closeAt(i)로 가격 경로 지정. */
    private fun bars(closeAt: (Int) -> Long): List<DailyBar> = (0..29).map { i ->
        val d = start.plusDays(i.toLong()).format(ymd)
        val c = closeAt(i)
        DailyBar(d, c, c, c, c, 100)
    }

    private fun kospi(closeAt: (Int) -> Double): List<IndexPoint> = (0..29).map { i ->
        IndexPoint(start.plusDays(i.toLong()).format(ymd), closeAt(i))
    }

    // A: +1/일(20일 후 +20%), B: -1/일(-20%), C: 1000+1/일(+2%)
    private val barsByCode = mapOf(
        "AAAAA1" to bars { 100L + it },
        "BBBBB1" to bars { 100L - it },
        "CCCCC1" to bars { 1000L + it },
    )
    private val kospiFlat = kospi { 1000.0 }

    private fun stance(code: String, date: String, s: String, mode: String = "defensive") =
        StanceEntry(code = code, date = date, mode = mode, stance = s, priceAtGen = 100.0)

    @Test
    fun `매수 채점 - 초과수익 양수 적중, 음수 실패`() {
        val r = JudgmentComparisonService.score(
            listOf(
                JudgmentTrade("AAAAA1", "buy", "2026-06-01"),   // +20% → win
                JudgmentTrade("BBBBB1", "buy", "2026-06-01"),   // -20% → loss
            ),
            emptyList(), barsByCode, kospiFlat, "2026-07-18")
        val buy = assertNotNull(r.myBuy)
        assertEquals(2, buy.n)
        assertEquals(1, buy.wins)
        assertEquals(50.0, buy.winRatePct)
        assertEquals(0.0, buy.avgExcessPct, 0.01)  // +20, -20 평균
    }

    @Test
    fun `기저율 통제 - 시장이 더 오르면 원수익 양수여도 실패`() {
        // C +2%인데 코스피 +10% → excess -8 → 매수 실패 (상승장에서 아무거나 사도 맞는 착시 차단)
        val kospiRising = kospi { 1000.0 + it * 5 }
        val r = JudgmentComparisonService.score(
            listOf(JudgmentTrade("CCCCC1", "buy", "2026-06-01")),
            emptyList(), barsByCode, kospiRising, "2026-07-18")
        val buy = assertNotNull(r.myBuy)
        assertEquals(0, buy.wins)
        assertTrue(buy.avgRawPct > 0)       // 원수익은 양수
        assertTrue(buy.avgExcessPct < 0)    // 초과수익은 음수
    }

    @Test
    fun `매도 채점 - 판 뒤 초과 하락이면 적중`() {
        val r = JudgmentComparisonService.score(
            listOf(JudgmentTrade("BBBBB1", "sell", "2026-06-01")),
            emptyList(), barsByCode, kospiFlat, "2026-07-18")
        val sell = assertNotNull(r.mySell)
        assertEquals(1, sell.wins)
    }

    @Test
    fun `매트릭스 - 동의·역행·무참조 스탠스 매칭`() {
        val r = JudgmentComparisonService.score(
            listOf(
                JudgmentTrade("AAAAA1", "buy", "2026-06-01"),   // 긍정(5/29) 매칭 → 동의
                JudgmentTrade("BBBBB1", "buy", "2026-06-01"),   // 부정(5/30) 매칭 → 역행
                JudgmentTrade("CCCCC1", "buy", "2026-06-01"),   // 스탠스 없음 → 무참조
            ),
            listOf(
                stance("AAAAA1", "2026-05-29", "긍정"),
                stance("BBBBB1", "2026-05-30", "부정"),
            ),
            barsByCode, kospiFlat, "2026-07-18")
        val labels = r.buyMatrix.associateBy { it.label }
        assertEquals(1, labels["AI 동의(긍정)"]?.n)
        assertEquals(1, labels["AI 동의(긍정)"]?.wins)     // A +20% → 동의 매수 적중
        assertEquals(1, labels["AI 역행(부정)"]?.n)
        assertEquals(0, labels["AI 역행(부정)"]?.wins)     // B -20% → 역행 매수 실패
        assertEquals(1, labels["무참조"]?.n)
    }

    @Test
    fun `매칭 창 - 7일 초과 스탠스는 무참조`() {
        val r = JudgmentComparisonService.score(
            listOf(JudgmentTrade("AAAAA1", "buy", "2026-06-01")),
            listOf(stance("AAAAA1", "2026-05-24", "긍정")),  // 8일 전 — 창 밖
            barsByCode, kospiFlat, "2026-07-18")
        assertEquals(1, r.buyMatrix.associateBy { it.label }["무참조"]?.n)
        assertNull(r.buyMatrix.associateBy { it.label }["AI 동의(긍정)"])
    }

    @Test
    fun `AI 재채점 - 같은 잣대, 중립 제외`() {
        val r = JudgmentComparisonService.score(
            listOf(JudgmentTrade("AAAAA1", "buy", "2026-06-01")),
            listOf(
                stance("AAAAA1", "2026-06-01", "긍정"),   // +20% → 적중
                stance("BBBBB1", "2026-06-01", "부정"),   // -20% → 적중
                stance("CCCCC1", "2026-06-01", "중립"),   // 방향 없음 → 제외
            ),
            barsByCode, kospiFlat, "2026-07-18")
        assertEquals(1, r.aiPositive?.n)
        assertEquals(1, r.aiPositive?.wins)
        assertEquals(1, r.aiNegative?.n)
        assertEquals(1, r.aiNegative?.wins)
    }

    @Test
    fun `관심 후 미매수 - 기회비용 관찰과 AI 긍정 매칭`() {
        val r = JudgmentComparisonService.score(
            listOf(
                JudgmentTrade("CCCCC1", "interest", "2026-06-01"),  // 이후 매수 없음, +2% → rose
                JudgmentTrade("AAAAA1", "interest", "2026-06-01"),  // 같은 날 매수 → 제외
                JudgmentTrade("AAAAA1", "buy", "2026-06-01"),
            ),
            listOf(stance("CCCCC1", "2026-06-01", "긍정")),
            barsByCode, kospiFlat, "2026-07-18")
        val m = assertNotNull(r.missedInterest)
        assertEquals(1, m.n)
        assertEquals(1, m.roseN)
        assertEquals(1, m.aiPositiveN)
        assertEquals(1, m.aiPositiveRoseN)
    }

    @Test
    fun `중복 접기와 채점 대기`() {
        val r = JudgmentComparisonService.score(
            listOf(
                JudgmentTrade("AAAAA1", "buy", "2026-06-01"),
                JudgmentTrade("AAAAA1", "buy", "2026-06-01"),   // 같은 날 분할 매수 → 1건
                JudgmentTrade("AAAAA1", "buy", "2026-06-25"),   // forward 20봉 부족 → pending
            ),
            emptyList(), barsByCode, kospiFlat, "2026-07-18")
        assertEquals(1, r.myBuy?.n)
        assertEquals(1, r.pendingTrades)
    }

    @Test
    fun `빈 결과 - 채점 불가 시 버킷 null`() {
        val r = JudgmentComparisonService.score(
            listOf(JudgmentTrade("ZZZZZ1", "buy", "2026-06-01")),  // 봉 없음
            emptyList(), emptyMap(), kospiFlat, "2026-07-18")
        assertNull(r.myBuy)
        assertEquals(1, r.pendingTrades)
    }
}
