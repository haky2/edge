package com.haky.edge

import com.haky.edge.ai.CatalystImpactService
import com.haky.edge.ai.DailyHistoryService
import com.haky.edge.kis.DailyBar
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 2026-07-07 신규분 감사 수정 검증 — M3(장중 오늘봉 제외)·M4(이벤트 날짜 정규화). */
class AuditFixes2Test {

    private fun bar(date: String, close: Long = 100) =
        DailyBar(date = date, open = close, high = close, low = close, close = close, volume = 1)

    // ── M3: DailyHistoryService.dropUnconfirmedToday ──────────────────────

    @Test
    fun `장마감 전에는 오늘 봉 제거, 과거 봉은 유지`() {
        val bars = listOf(bar("20260707"), bar("20260706"), bar("20260703"))
        val midSession = LocalDateTime.of(2026, 7, 7, 10, 30)
        assertEquals(listOf("20260706", "20260703"),
            DailyHistoryService.dropUnconfirmedToday(bars, midSession).map { it.date })
    }

    @Test
    fun `장마감(16시) 후에는 오늘 봉 유지`() {
        val bars = listOf(bar("20260707"), bar("20260706"))
        val afterClose = LocalDateTime.of(2026, 7, 7, 16, 0)
        assertEquals(2, DailyHistoryService.dropUnconfirmedToday(bars, afterClose).size)
    }

    @Test
    fun `오늘 봉이 없으면(장 전·휴장) 그대로`() {
        val bars = listOf(bar("20260706"), bar("20260703"))
        val preOpen = LocalDateTime.of(2026, 7, 7, 8, 45)
        assertEquals(2, DailyHistoryService.dropUnconfirmedToday(bars, preOpen).size)
    }

    // ── M4: CatalystImpactService.normalizeDate ───────────────────────────

    @Test
    fun `공시 날짜(YYYYMMDD)는 그대로`() {
        assertEquals("20260707", CatalystImpactService.normalizeDate("20260707"))
    }

    @Test
    fun `뉴스 RFC-1123 발행 표기는 KST YYYYMMDD로`() {
        // 운영 catalyst_events.jsonl 실물 형식
        assertEquals("20260707", CatalystImpactService.normalizeDate("Tue, 07 Jul 2026 07:52:00 +0900"))
        // UTC 표기는 KST로 환산(+9h → 날짜 넘어감)
        assertEquals("20260708", CatalystImpactService.normalizeDate("Tue, 07 Jul 2026 20:00:00 GMT"))
    }

    @Test
    fun `파싱 불가 날짜는 null(통계·n 모두 제외)`() {
        assertNull(CatalystImpactService.normalizeDate("어제"))
        assertNull(CatalystImpactService.normalizeDate(""))
    }
}
