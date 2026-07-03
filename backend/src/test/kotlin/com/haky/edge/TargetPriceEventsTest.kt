package com.haky.edge

import com.haky.edge.news.TargetPriceLogService
import com.haky.edge.news.TargetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.time.LocalDate

class TargetPriceEventsTest {

    private val today = LocalDate.parse("2026-07-03")

    @Test fun `상향·하향 이벤트 집계 - ±1퍼센트 임계`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-01", 400000),
            TargetSnapshot("2026-06-08", 420000),  // +5% 상향
            TargetSnapshot("2026-06-15", 421000),  // +0.2% → 노이즈, 미집계
            TargetSnapshot("2026-06-22", 410000),  // -2.6% 하향
            TargetSnapshot("2026-06-29", 440000),  // +7.3% 상향
        )
        val e = TargetPriceLogService.computeEvents(snaps, today)!!
        assertEquals(2, e.raisesIn90d)
        assertEquals(1, e.cutsIn90d)
        assertEquals(0, e.breakthroughDays)
        assertNull(e.avgRaiseGapDays)
        assertEquals(5, e.snapshotCount)
    }

    @Test fun `돌파 관측과 돌파-상향 간격`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-01", 400000, price = 380000),
            TargetSnapshot("2026-06-05", 400000, price = 405000),  // 돌파 관측
            TargetSnapshot("2026-06-11", 430000, price = 410000),  // +7.5% 상향 (돌파 6일 후)
            TargetSnapshot("2026-06-20", 430000, price = 435000),  // 돌파 관측
            TargetSnapshot("2026-06-24", 460000, price = 440000),  // +7.0% 상향 (돌파 4일 후)
        )
        val e = TargetPriceLogService.computeEvents(snaps, today)!!
        assertEquals(2, e.raisesIn90d)
        assertEquals(2, e.breakthroughDays)
        assertEquals(5, e.avgRaiseGapDays) // (6+4)/2
    }

    @Test fun `상향이 먼저고 이후 상향 없는 돌파는 간격 미집계`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-01", 400000, price = 380000),
            TargetSnapshot("2026-06-08", 420000, price = 400000),  // 상향
            TargetSnapshot("2026-06-20", 420000, price = 425000),  // 돌파 — 이후 상향 없음
        )
        val e = TargetPriceLogService.computeEvents(snaps, today)!!
        assertEquals(1, e.raisesIn90d)
        assertEquals(1, e.breakthroughDays)
        assertNull(e.avgRaiseGapDays)
    }

    @Test fun `90일 밖 스냅샷은 제외`() {
        val snaps = listOf(
            TargetSnapshot("2026-01-05", 300000),  // 창 밖
            TargetSnapshot("2026-02-01", 350000),  // 창 밖 (+16%지만 미집계)
            TargetSnapshot("2026-06-01", 400000),
            TargetSnapshot("2026-06-15", 410000),  // +2.5% 상향
        )
        val e = TargetPriceLogService.computeEvents(snaps, today)!!
        assertEquals(1, e.raisesIn90d)
        assertEquals(2, e.snapshotCount)
    }

    @Test fun `스냅샷 2개 미만이면 null`() {
        assertNull(TargetPriceLogService.computeEvents(emptyList(), today))
        assertNull(TargetPriceLogService.computeEvents(listOf(TargetSnapshot("2026-07-01", 400000)), today))
    }

    @Test fun `price 없는 구 스냅샷 하위호환 - 돌파만 0`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-01", 400000),
            TargetSnapshot("2026-06-10", 450000),
        )
        val e = TargetPriceLogService.computeEvents(snaps, today)!!
        assertEquals(1, e.raisesIn90d)
        assertEquals(0, e.breakthroughDays)
    }
}
