package com.haky.edge

import com.haky.edge.ai.RebalanceService
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 리밸런싱 트리거(R1) 순수 함수 검증 — 비중·드리프트·상단권 합산·거래일 신선도. */
class RebalanceTest {

    // ── weightsOf ──────────────────────────────────────────────────────────

    @Test
    fun `비중 - 평가금액 비례, 합계 100`() {
        val w = RebalanceService.weightsOf(mapOf("A" to 600L, "B" to 300L, "C" to 100L))
        assertEquals(60.0, w["A"]!!, 1e-9)
        assertEquals(30.0, w["B"]!!, 1e-9)
        assertEquals(10.0, w["C"]!!, 1e-9)
        assertEquals(100.0, w.values.sum(), 1e-9)
    }

    @Test
    fun `비중 - 합계 0이면 빈 맵(비중 정의 불가)`() {
        assertTrue(RebalanceService.weightsOf(mapOf("A" to 0L)).isEmpty())
        assertTrue(RebalanceService.weightsOf(emptyMap()).isEmpty())
    }

    // ── driftEntries ───────────────────────────────────────────────────────

    @Test
    fun `드리프트 - 임계 이상만 발동, 절대값 내림차순 정렬`() {
        val drifts = RebalanceService.driftEntries(
            baseline = mapOf("A" to 50.0, "B" to 30.0, "C" to 20.0),
            current = mapOf("A" to 62.0, "B" to 25.0, "C" to 13.0),
            thresholdPp = 7.0,
        )
        assertEquals(listOf("A", "C", "B"), drifts.map { it.code })
        assertEquals(12.0, drifts[0].deltaPp, 1e-9)
        assertTrue(drifts[0].fired)                    // +12.0%p ≥ 7
        assertTrue(drifts[1].fired)                    // -7.0%p — 경계 포함
        assertFalse(drifts[2].fired)                   // -5.0%p
    }

    @Test
    fun `드리프트 - 전량 매도(기준에만 존재)는 현재 0%로 발동`() {
        val drifts = RebalanceService.driftEntries(
            baseline = mapOf("A" to 88.0, "B" to 12.0),
            current = mapOf("A" to 100.0),
            thresholdPp = 7.0,
        )
        val sold = drifts.first { it.code == "B" }
        assertEquals(0.0, sold.currentPct, 1e-9)
        assertEquals(-12.0, sold.deltaPp, 1e-9)
        assertTrue(sold.fired)
    }

    @Test
    fun `드리프트 - 신규 편입(현재에만 존재)은 기준 0%에서 계산`() {
        val drifts = RebalanceService.driftEntries(
            baseline = mapOf("A" to 100.0),
            current = mapOf("A" to 91.0, "N" to 9.0),
            thresholdPp = 7.0,
        )
        val fresh = drifts.first { it.code == "N" }
        assertEquals(9.0, fresh.deltaPp, 1e-9)
        assertTrue(fresh.fired)
    }

    @Test
    fun `드리프트 - 1종목 포트폴리오는 양쪽 100%라 발동 없음`() {
        val drifts = RebalanceService.driftEntries(
            baseline = mapOf("A" to 100.0),
            current = mapOf("A" to 100.0),
            thresholdPp = 7.0,
        )
        assertEquals(1, drifts.size)
        assertEquals(0.0, drifts[0].deltaPp, 1e-9)
        assertFalse(drifts[0].fired)
    }

    @Test
    fun `드리프트 - 이름 맵 반영, 없으면 코드 폴백`() {
        val drifts = RebalanceService.driftEntries(
            baseline = mapOf("A" to 60.0, "B" to 40.0),
            current = mapOf("A" to 60.0, "B" to 40.0),
            thresholdPp = 7.0,
            names = mapOf("A" to "삼성전자"),
        )
        assertEquals("삼성전자", drifts.first { it.code == "A" }.name)
        assertEquals("B", drifts.first { it.code == "B" }.name)
    }

    // ── topBandWeight ──────────────────────────────────────────────────────

    @Test
    fun `상단권 합산 - 상단권 라벨만 집계, 비중 내림차순 코드`() {
        val (pct, codes) = RebalanceService.topBandWeight(
            weights = mapOf("A" to 30.0, "B" to 25.0, "C" to 45.0),
            bandLabels = mapOf("A" to "역사적 상단권", "B" to "역사적 중간권", "C" to "역사적 상단권"),
        )
        assertEquals(75.0, pct, 1e-9)
        assertEquals(listOf("C", "A"), codes)
    }

    @Test
    fun `상단권 합산 - 라벨 없는 종목은 제외(밴드 계산 불가)`() {
        val (pct, codes) = RebalanceService.topBandWeight(
            weights = mapOf("A" to 50.0, "B" to 50.0),
            bandLabels = mapOf("A" to "역사적 상단권"), // B는 밴드 실패
        )
        assertEquals(50.0, pct, 1e-9)
        assertEquals(listOf("A"), codes)
    }

    // ── businessDaysBetween ────────────────────────────────────────────────

    @Test
    fun `거래일 경과 - 주말 건너뛰고 평일만 센다`() {
        val fri = LocalDate.of(2026, 7, 3)   // 금
        assertEquals(0, RebalanceService.businessDaysBetween(fri, fri))
        assertEquals(0, RebalanceService.businessDaysBetween(fri, LocalDate.of(2026, 7, 5)))  // 금→일: 평일 0
        assertEquals(1, RebalanceService.businessDaysBetween(fri, LocalDate.of(2026, 7, 6)))  // 금→월
        assertEquals(2, RebalanceService.businessDaysBetween(fri, LocalDate.of(2026, 7, 7)))  // 금→화
        assertEquals(3, RebalanceService.businessDaysBetween(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 9))) // 월→목
    }

    @Test
    fun `거래일 경과 - 역순은 0(미래 스냅샷 방어)`() {
        assertEquals(0, RebalanceService.businessDaysBetween(LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 3)))
    }
}
