package com.haky.edge

import com.haky.edge.slack.SignalService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R2 — 리밸런싱 signals-scan 통합: 쿨다운·신선도 게이트 순수 함수 검증. */
class RebalanceSignalTest {

    // ── withinCooldown (SignalService 기존 공용 함수) ──────────────────────

    @Test
    fun `쿨다운 - 발화일 없으면 발화 허용`() {
        assertFalse(SignalService.withinCooldown(null, "2026-07-08", 14))
        assertFalse(SignalService.withinCooldown("", "2026-07-08", 14))
    }

    @Test
    fun `쿨다운 - 14일 이내면 재발화 금지`() {
        assertTrue(SignalService.withinCooldown("2026-07-01", "2026-07-08", 14))  // 7일 경과
        assertTrue(SignalService.withinCooldown("2026-06-25", "2026-07-08", 14)) // 13일 경과
        assertTrue(SignalService.withinCooldown("2026-06-24", "2026-07-08", 14)) // 14일 경과(경계 포함)
    }

    @Test
    fun `쿨다운 - 14일 초과하면 재발화 허용`() {
        assertFalse(SignalService.withinCooldown("2026-06-23", "2026-07-08", 14)) // 15일 경과
        assertFalse(SignalService.withinCooldown("2026-06-01", "2026-07-08", 14)) // 37일 경과
    }

    @Test
    fun `쿨다운 - REBALANCE_COOLDOWN_DAYS 상수가 14일`() {
        assertTrue(SignalService.REBALANCE_COOLDOWN_DAYS == 14)
    }

    @Test
    fun `쿨다운 - 날짜 파싱 실패는 발화 허용(방어)`() {
        assertFalse(SignalService.withinCooldown("not-a-date", "2026-07-08", 14))
    }
}
