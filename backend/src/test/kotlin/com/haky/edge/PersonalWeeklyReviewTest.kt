package com.haky.edge

import com.haky.edge.ai.PersonalWeeklyReviewService
import com.haky.edge.ai.WeeklyThesisChange
import com.haky.edge.ai.WeeklyTrade
import com.haky.edge.macro.HoldingPosition
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** B2 개인 주간 회고 — 순수 함수(캐시 키 안정성·action 라벨). facts 조립은 WeeklyReviewTest가 커버. */
class PersonalWeeklyReviewTest {

    private val friday = LocalDate.parse("2026-07-10")
    private val positions = mapOf(
        "005930" to HoldingPosition(72_000.0, 10),
        "000660" to HoldingPosition(180_000.0, 5),
    )
    private val trades = listOf(
        WeeklyTrade("005930", "삼성전자", "buy", "수주 모멘텀", 72_000, "2026-07-07"),
        WeeklyTrade("066570", "LG전자", "sell", "차익 실현", 95_000, "2026-07-09"),
    )
    private val theses = listOf(WeeklyThesisChange("005930", "HBM 사이클 지속", "2026-07-08"))

    @Test
    fun `캐시 키 - 입력 순서가 달라도 같은 키`() {
        val a = PersonalWeeklyReviewService.buildKey(friday, positions, trades, theses)
        val b = PersonalWeeklyReviewService.buildKey(
            friday,
            positions.entries.reversed().associate { it.key to it.value },
            trades.reversed(),
            theses,
        )
        assertEquals(a, b)
    }

    @Test
    fun `캐시 키 - 포지션·매매·논지가 바뀌면 다른 키`() {
        val base = PersonalWeeklyReviewService.buildKey(friday, positions, trades, theses)
        // 수량 변경
        val diffQty = PersonalWeeklyReviewService.buildKey(
            friday, positions + ("005930" to HoldingPosition(72_000.0, 20)), trades, theses)
        assertNotEquals(base, diffQty)
        // 매매 추가
        val diffTrade = PersonalWeeklyReviewService.buildKey(
            friday, positions, trades + WeeklyTrade("000660", "SK하이닉스", "buy", null, 180_000, "2026-07-10"), theses)
        assertNotEquals(base, diffTrade)
        // 논지 텍스트 변경
        val diffThesis = PersonalWeeklyReviewService.buildKey(
            friday, positions, trades, listOf(WeeklyThesisChange("005930", "논지 수정됨", "2026-07-08")))
        assertNotEquals(base, diffThesis)
        // 다른 주(금요일)면 다른 키
        val diffWeek = PersonalWeeklyReviewService.buildKey(
            LocalDate.parse("2026-07-17"), positions, trades, theses)
        assertNotEquals(base, diffWeek)
    }

    @Test
    fun `action 라벨 한국어화`() {
        assertEquals("매수", PersonalWeeklyReviewService.actionKo("buy"))
        assertEquals("매도", PersonalWeeklyReviewService.actionKo("sell"))
        assertEquals("관심", PersonalWeeklyReviewService.actionKo("interest"))
        assertEquals("unknown", PersonalWeeklyReviewService.actionKo("unknown"))
    }
}
