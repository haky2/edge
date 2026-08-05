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

    // ── computeCutSince (F5 target_cut 감시) ────────────────────────────

    private val created = "2026-06-10T15:00:00"  // 프리모템 생성 시점

    @Test fun `생성 이후 CUT_PCT 이상 하향 - 발화`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-05", 400000),  // 생성 이전(포함) 마지막 → 기준
            TargetSnapshot("2026-06-20", 370000),  // -7.5% → 하향 전환
        )
        val cut = TargetPriceLogService.computeCutSince(snaps, created)!!
        assertEquals(400000L, cut.fromTarget)
        assertEquals(370000L, cut.toTarget)
        assertEquals(-7.5, cut.changePct)
    }

    @Test fun `낙폭이 CUT_PCT 미만이면 null`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-05", 400000),
            TargetSnapshot("2026-06-20", 393000),  // -1.75% < 3% → 노이즈
        )
        assertNull(TargetPriceLogService.computeCutSince(snaps, created))
    }

    @Test fun `생성 이후 상향은 null`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-05", 400000),
            TargetSnapshot("2026-06-20", 440000),  // +10% 상향
        )
        assertNull(TargetPriceLogService.computeCutSince(snaps, created))
    }

    @Test fun `생성 이전만 있으면 - 스냅샷 1개 취급 null`() {
        // 기준=끝=같은 스냅샷(생성 후 갱신 없음) → 감지 불가
        val snaps = listOf(TargetSnapshot("2026-06-05", 400000))
        assertNull(TargetPriceLogService.computeCutSince(snaps, created))
    }

    @Test fun `기준이 생성 이전에 없으면 이후 첫 스냅샷 기준 - 그 내부 하락 감지`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-15", 400000),  // 전부 생성 이후 → 첫 스냅샷이 기준
            TargetSnapshot("2026-06-25", 380000),  // -5% 하향
        )
        val cut = TargetPriceLogService.computeCutSince(snaps, created)!!
        assertEquals(400000L, cut.fromTarget)
        assertEquals(380000L, cut.toTarget)
    }

    @Test fun `net 기준 - 내렸다 회복하면 하향 아님`() {
        val snaps = listOf(
            TargetSnapshot("2026-06-05", 400000),  // 기준
            TargetSnapshot("2026-06-12", 360000),  // 일시 하락
            TargetSnapshot("2026-06-25", 398000),  // 회복(-0.5% net) → null
        )
        assertNull(TargetPriceLogService.computeCutSince(snaps, created))
    }
}
