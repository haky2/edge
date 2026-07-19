package com.haky.edge

import com.haky.edge.ai.PositionSizingService
import com.haky.edge.kis.DailyBar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 포지션 사이징 — 순수 함수(compute) 검증.
 * 핵심 항등식: 이분 탐색으로 찾은 w*에서 후보 리스크 기여 ≈ 상한.
 * 단조성: 상한을 올리면 허용 주수도 늘어난다. peer 폴백: 관측<40이면 peer σ로 근사·플래그.
 */
class PositionSizingTest {

    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    private val start = LocalDate.parse("2026-01-01")

    private fun s2(i: Int) = if (i % 2 == 0) 1.0 else -1.0
    private fun s4(i: Int) = if (i % 4 < 2) 1.0 else -1.0

    /** 로그수익률 계열 → 일봉(오래된 순). scale로 변동성 크기 조절. */
    private fun bars(n: Int, base: Double = 1_000_000.0, f: (Int) -> Double): List<DailyBar> {
        var c = base
        val out = mutableListOf(bar(0, base.toLong()))
        (0 until n).forEach { i ->
            c *= exp(f(i))
            out += bar(i + 1, Math.round(c))
        }
        return out
    }

    private fun bar(i: Int, close: Long): DailyBar {
        val d = start.plusDays(i.toLong()).format(ymd)
        return DailyBar(d, close, close, close, close, 100)
    }

    private fun compute(
        candidateBars: List<DailyBar>,
        cap: Double,
        existing: List<Triple<String, Long, List<DailyBar>>>,
        peerBars: Map<String, List<DailyBar>> = emptyMap(),
    ) = PositionSizingService.compute(
        "CCCCC1", "후보", candidateBars, candidateBars.last().close.toDouble(), cap, "2026-07-18",
        existing, peerBars,
    )

    @Test
    fun `이분 탐색 항등식 - w에서 리스크 기여가 상한에 수렴`() {
        // 기존 1종목(s2, 변동성 0.02), 후보(s4, 변동성 0.02) — 60관측 공배수라 상관 0에 가깝다.
        val existing = listOf(Triple("AAAAA1", 10L, bars(60) { 0.02 * s2(it) }))
        val cand = bars(60) { 0.02 * s4(it) }
        val r = compute(cand, cap = 15.0, existing = existing)
        // 달성 기여가 상한과 사실상 일치(이분 탐색 수렴).
        assertTrue(abs(r.atRiskContributionPct - 15.0) < 0.1, "달성 기여 ${r.atRiskContributionPct} ≠ 상한 15")
        assertTrue(r.maxShares > 0, "허용 주수가 양수여야 함")
        assertFalse(r.approxByPeer, "관측 충분 → peer 근사 아님")
    }

    @Test
    fun `단조성 - 상한을 올리면 허용 주수가 늘어난다`() {
        val existing = listOf(Triple("AAAAA1", 10L, bars(60) { 0.02 * s2(it) }))
        val cand = bars(60) { 0.025 * s4(it) }
        val low = compute(cand, cap = 10.0, existing = existing)
        val high = compute(cand, cap = 25.0, existing = existing)
        assertTrue(high.maxShares > low.maxShares, "상한↑ → 주수↑ (10%:${low.maxShares} < 25%:${high.maxShares})")
        assertTrue(high.targetWeightPct > low.targetWeightPct)
    }

    @Test
    fun `고변동 후보는 같은 상한에서 더 적게 담긴다`() {
        val existing = listOf(Triple("AAAAA1", 10L, bars(60) { 0.02 * s2(it) }))
        val calm = compute(bars(60) { 0.01 * s4(it) }, cap = 15.0, existing = existing)
        val wild = compute(bars(60) { 0.04 * s4(it) }, cap = 15.0, existing = existing)
        assertTrue(wild.targetWeightPct < calm.targetWeightPct, "고변동 후보 비중이 더 낮아야 함")
        assertTrue(wild.sigmaPct > calm.sigmaPct)
    }

    @Test
    fun `peer 폴백 - 관측 부족 후보는 peer 평균 시그마로 근사하고 플래그`() {
        val existing = listOf(Triple("AAAAA1", 10L, bars(60) { 0.02 * s2(it) }))
        val cand = bars(20) { 0.02 * s4(it) }              // 20관측 < 40 → 근사
        val peers = mapOf("PPPPP1" to bars(60) { 0.03 * s4(it) })
        val r = compute(cand, cap = 15.0, existing = existing, peerBars = peers)
        assertTrue(r.approxByPeer, "관측 부족 → approxByPeer=true")
        // peer σ(0.03 계열) 연환산 ≈ sigmaPct. 대략 0.03·√252·100 ≈ 47.6%
        assertTrue(r.sigmaPct > 40.0, "peer 변동성 반영(${r.sigmaPct})")
        assertTrue(r.maxShares > 0)
    }

    @Test
    fun `peer 없으면 기존 비중가중 시그마로 최후 근사`() {
        val existing = listOf(Triple("AAAAA1", 10L, bars(60) { 0.02 * s2(it) }))
        val cand = bars(20) { 0.02 * s4(it) }
        val r = compute(cand, cap = 15.0, existing = existing, peerBars = emptyMap())
        assertTrue(r.approxByPeer)
        // 기존 σ(0.02 계열) 연환산 ≈ 31.7% 근처.
        assertTrue(r.sigmaPct in 25.0..38.0, "기존 비중가중 σ 근사(${r.sigmaPct})")
    }

    @Test
    fun `관측 부족 기존 종목은 제외하고 재정규화`() {
        val existing = listOf(
            Triple("AAAAA1", 10L, bars(60) { 0.02 * s2(it) }),
            Triple("BBBBB1", 10L, bars(20) { 0.02 * s4(it) }),  // 20관측 → 제외
        )
        val cand = bars(60) { 0.02 * s4(it) }
        val r = compute(cand, cap = 15.0, existing = existing)
        assertEquals(listOf("BBBBB1"), r.excluded)
        assertTrue(r.maxShares > 0)
    }
}
