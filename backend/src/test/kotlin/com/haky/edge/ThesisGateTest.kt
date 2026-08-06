package com.haky.edge

import com.haky.edge.slack.SignalService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 논지 재점검 게이트의 쿨다운·해시 순수 로직(SignalService.Companion). */
class ThesisGateTest {

    @Test fun `thesisHash - 같은 텍스트 안정, 편집 시 변경`() {
        assertEquals(SignalService.thesisHash("HBM 수요"), SignalService.thesisHash("HBM 수요"))
        assertEquals(SignalService.thesisHash(" HBM 수요 "), SignalService.thesisHash("HBM 수요")) // trim
        assertTrue(SignalService.thesisHash("HBM 수요") != SignalService.thesisHash("HBM 수요 둔화"))
    }

    @Test fun `쿨다운 - 같은 논지는 기간 내 재발화 금지`() {
        val hash = SignalService.thesisHash("논지 X")
        val state = "2026-08-01|$hash"
        // 14일 쿨다운 내
        assertTrue(SignalService.withinThesisCooldown(state, "2026-08-06", hash))
        assertTrue(SignalService.withinThesisCooldown(state, "2026-08-15", hash))
        // 14일 경과 → 재발화 허용
        assertTrue(!SignalService.withinThesisCooldown(state, "2026-08-16", hash))
    }

    @Test fun `쿨다운 - 논지 편집(해시 변경) 시 리셋`() {
        val oldHash = SignalService.thesisHash("논지 X")
        val newHash = SignalService.thesisHash("논지 Y")
        val state = "2026-08-05|$oldHash"
        // 같은 날이라도 논지가 바뀌면 쿨다운 없음(재점검 허용)
        assertTrue(!SignalService.withinThesisCooldown(state, "2026-08-06", newHash))
    }

    @Test fun `쿨다운 - 상태 없음·형식 불량은 발화 허용`() {
        val hash = SignalService.thesisHash("논지")
        assertTrue(!SignalService.withinThesisCooldown(null, "2026-08-06", hash))
        assertTrue(!SignalService.withinThesisCooldown("깨진값", "2026-08-06", hash))
    }
}
