package com.haky.edge

import com.haky.edge.ai.StanceEntry
import com.haky.edge.kis.DailyBar
import com.haky.edge.news.TargetPriceLogService
import com.haky.edge.news.TargetSnapshot
import com.haky.edge.slack.WeeklyReviewService
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B 주간 회고 — 순수 계산 함수(주간 창·주간 등락·스탠스 전환·목표가 주간 변화·복리 합성). */
class WeeklyReviewTest {

    // ── weekWindow ─────────────────────────────────────────────────────

    @Test
    fun `주간 창 - 토요일 실행은 이번 주, 주중 실행은 지난 완결 주`() {
        // 2026-07-11(토) → 7/6(월)~7/10(금)... 아니, 7/11이 토요일이면 직전 금요일은 7/10
        val (monSat, friSat) = WeeklyReviewService.weekWindow(LocalDate.parse("2026-07-11"))
        assertEquals(LocalDate.parse("2026-07-06"), monSat)
        assertEquals(LocalDate.parse("2026-07-10"), friSat)
        // 수요일(7/8) 실행 → 지난 완결 주(6/29~7/3)
        val (monWed, friWed) = WeeklyReviewService.weekWindow(LocalDate.parse("2026-07-08"))
        assertEquals(LocalDate.parse("2026-06-29"), monWed)
        assertEquals(LocalDate.parse("2026-07-03"), friWed)
        // 금요일 당일 실행 → 그 주(당일 포함)
        val (monFri, friFri) = WeeklyReviewService.weekWindow(LocalDate.parse("2026-07-10"))
        assertEquals(LocalDate.parse("2026-07-06"), monFri)
        assertEquals(LocalDate.parse("2026-07-10"), friFri)
    }

    // ── weeklyChangePct ────────────────────────────────────────────────

    private fun bar(ymd: String, close: Long) = DailyBar(ymd, close, close, close, close, 100)

    @Test
    fun `주간 등락 - 직전 금요일 종가 기준`() {
        val bars = listOf( // 최신순
            bar("20260710", 110_000), bar("20260709", 105_000), bar("20260707", 102_000),
            bar("20260703", 100_000), // 직전 주 금요일 = 기준
        )
        val pct = WeeklyReviewService.weeklyChangePct(bars, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10"))
        assertEquals(10.0, pct!!, 0.001)
    }

    @Test
    fun `주간 등락 - 직전 봉 없으면 창 내 첫 봉 기준, 창 내 봉 없으면 null`() {
        val onlyInWindow = listOf(bar("20260710", 105_000), bar("20260707", 100_000))
        val pct = WeeklyReviewService.weeklyChangePct(onlyInWindow, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10"))
        assertEquals(5.0, pct!!, 0.001)

        val outOfWindow = listOf(bar("20260703", 100_000))
        assertNull(WeeklyReviewService.weeklyChangePct(outOfWindow, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10")))
        assertNull(WeeklyReviewService.weeklyChangePct(emptyList(), LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10")))
    }

    // ── stanceTransitions ──────────────────────────────────────────────

    private fun st(code: String, date: String, stance: String, mode: String = "defensive") =
        StanceEntry(code, date, mode, stance, 100_000.0, "09:00")

    @Test
    fun `스탠스 전환 - 창 내 직전 대비 변경만, 미상 제외, 모드 분리`() {
        val all = listOf(
            st("005930", "2026-07-02", "중립"),
            st("005930", "2026-07-06", "미상"),           // 미상 → 시계열에서 제외
            st("005930", "2026-07-08", "긍정"),           // 중립→긍정 전환(창 내)
            st("000660", "2026-07-01", "긍정"),
            st("000660", "2026-07-09", "긍정"),           // 유지 → 전환 아님
            st("001440", "2026-07-07", "부정"),           // 직전 없음(첫 기록) → 전환 아님
            st("005930", "2026-07-03", "부정", mode = "aggressive"), // 다른 모드 창 밖
        )
        val ts = WeeklyReviewService.stanceTransitions(all, "2026-07-06", "2026-07-10")
        assertEquals(1, ts.size)
        assertEquals("005930", ts[0].code)
        assertEquals("중립", ts[0].from)
        assertEquals("긍정", ts[0].to)
    }

    @Test
    fun `스탠스 전환 - 같은 전환 반복은 1건으로 접힘`() {
        val all = listOf(
            st("005930", "2026-07-02", "중립"),
            st("005930", "2026-07-07", "긍정"),
            st("005930", "2026-07-08", "중립"),
            st("005930", "2026-07-09", "긍정"), // 중립→긍정 재발 — distinctBy로 1건
        )
        val ts = WeeklyReviewService.stanceTransitions(all, "2026-07-06", "2026-07-10")
        assertEquals(2, ts.size) // 중립→긍정 1 + 긍정→중립 1
        assertTrue(ts.any { it.from == "중립" && it.to == "긍정" })
        assertTrue(ts.any { it.from == "긍정" && it.to == "중립" })
    }

    // ── compoundPct ────────────────────────────────────────────────────

    @Test
    fun `복리 합성 - 일별 등락 합성, 비면 null`() {
        assertEquals(2.01, WeeklyReviewService.compoundPct(listOf(1.0, 1.0))!!, 0.001)
        assertEquals(-1.0, WeeklyReviewService.compoundPct(listOf(-1.0))!!, 0.001)
        assertNull(WeeklyReviewService.compoundPct(emptyList()))
    }

    // ── TargetPriceLogService.computeWeeklyChange ──────────────────────

    @Test
    fun `목표가 주간 변화 - 창 이전 마지막 vs 창 내 마지막`() {
        val snaps = listOf(
            TargetSnapshot("2026-07-03", 100_000),
            TargetSnapshot("2026-07-07", 105_000),
            TargetSnapshot("2026-07-10", 110_000),
        )
        val change = TargetPriceLogService.computeWeeklyChange(snaps, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10"))
        assertEquals(100_000L to 110_000L, change)
    }

    @Test
    fun `목표가 주간 변화 - 변화 없음·창 내 스냅샷 없음은 null`() {
        val same = listOf(TargetSnapshot("2026-07-03", 100_000), TargetSnapshot("2026-07-08", 100_000))
        assertNull(TargetPriceLogService.computeWeeklyChange(same, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10")))

        val outOnly = listOf(TargetSnapshot("2026-07-03", 100_000))
        assertNull(TargetPriceLogService.computeWeeklyChange(outOnly, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10")))

        // 창 내 스냅샷 1개뿐이고 이전 기록 없음 → 기준=끝 동일 객체 → null
        val singleIn = listOf(TargetSnapshot("2026-07-08", 105_000))
        assertNull(TargetPriceLogService.computeWeeklyChange(singleIn, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-10")))
    }
}
