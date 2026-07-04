package com.haky.edge

import com.haky.edge.slack.SignalService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F4 슬라이스 4a — 수급 전환점 감지 순수 함수 + 재알림 쿨다운. netByDay는 최신이 앞. */
class FlowReversalTest {

    @Test
    fun `5일 연속 순매도 후 첫 순매수 - 매수 전환`() {
        val rev = SignalService.detectReversal(listOf(500L, -100, -200, -50, -300, -10))
        assertNotNull(rev)
        assertTrue(rev.toBuy)
        assertEquals(5, rev.prevStreak)
        assertEquals(500L, rev.todayQty)
    }

    @Test
    fun `대칭 - 순매수 후 첫 순매도는 매도 전환`() {
        val rev = SignalService.detectReversal(listOf(-500L, 100, 200, 50, 300, 10))
        assertNotNull(rev)
        assertFalse(rev.toBuy)
        assertEquals(5, rev.prevStreak)
    }

    @Test
    fun `직전 4일뿐이면 미달 - null`() {
        assertNull(SignalService.detectReversal(listOf(500L, -100, -200, -50, -300, 10)))
    }

    @Test
    fun `직전 6일 이상이면 streak 전체 카운트`() {
        val rev = SignalService.detectReversal(listOf(500L, -1, -1, -1, -1, -1, -1, -1))
        assertNotNull(rev)
        assertEquals(7, rev.prevStreak)
    }

    @Test
    fun `중간 보합(0)은 streak을 끊음`() {
        assertNull(SignalService.detectReversal(listOf(500L, -100, -200, 0, -300, -10, -20)))
    }

    @Test
    fun `당일 보합이면 전환 아님`() {
        assertNull(SignalService.detectReversal(listOf(0L, -100, -200, -50, -300, -10)))
    }

    @Test
    fun `데이터 부족이면 null`() {
        assertNull(SignalService.detectReversal(listOf(500L, -100, -200)))
        assertNull(SignalService.detectReversal(emptyList()))
    }

    @Test
    fun `연속이 아니라 오늘과 같은 방향이 끼면 전환 아님`() {
        assertNull(SignalService.detectReversal(listOf(500L, -100, 200, -50, -300, -10, -20)))
    }

    // ── withinCooldown ─────────────────────────────────────────────────

    @Test
    fun `쿨다운 - 7일 내 재알림 금지, 8일째 허용`() {
        assertTrue(SignalService.withinCooldown("2026-07-01", "2026-07-01", 7))
        assertTrue(SignalService.withinCooldown("2026-07-01", "2026-07-08", 7))
        assertFalse(SignalService.withinCooldown("2026-07-01", "2026-07-09", 7))
    }

    @Test
    fun `쿨다운 - 기록 없음·파싱 실패는 발화 허용`() {
        assertFalse(SignalService.withinCooldown(null, "2026-07-09", 7))
        assertFalse(SignalService.withinCooldown("", "2026-07-09", 7))
        assertFalse(SignalService.withinCooldown("garbage", "2026-07-09", 7))
    }
}
