package com.haky.edge

import com.haky.edge.macro.SensitivityValidationService
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

/** SensitivityValidationService의 순수 계산 — 시계열 변환·페어링·통계·판정. */
class SensitivityValidationTest {

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    // ── toChangeSeries ────────────────────────────────────────────

    @Test
    fun `값 시계열을 전일 대비 퍼센트로 변환 - 첫 항목 제외`() {
        val series = SensitivityValidationService.toChangeSeries(listOf(
            d("2026-07-01") to 100.0,
            d("2026-07-02") to 102.0,
            d("2026-07-03") to 100.98,
        ))
        assertEquals(2, series.size)
        assertEquals(d("2026-07-02"), series[0].first)
        assertEquals(2.0, series[0].second, 1e-9)
        assertEquals(-1.0, series[1].second, 1e-9)
    }

    // ── dailyReturns ──────────────────────────────────────────────

    @Test
    fun `일봉은 최신이 앞이어도 날짜 오름차순 수익률로 정렬된다`() {
        val returns = SensitivityValidationService.dailyReturns(listOf(
            "20260703" to 110L,   // 최신이 앞(KIS 순서)
            "20260702" to 100L,
            "20260701" to 100L,
        ))
        assertEquals(2, returns.size)
        assertEquals(0.0, returns.getValue(d("2026-07-02")), 1e-9)
        assertEquals(10.0, returns.getValue(d("2026-07-03")), 1e-9)
    }

    @Test
    fun `가격 0 이하 봉은 무시된다`() {
        val returns = SensitivityValidationService.dailyReturns(listOf(
            "20260703" to 110L,
            "20260702" to 0L,      // 불량 봉
            "20260701" to 100L,
        ))
        // 0 제거 후 07-01(100)→07-03(110) = +10%
        assertEquals(10.0, returns.getValue(d("2026-07-03")), 1e-9)
    }

    // ── averageReturns ────────────────────────────────────────────

    @Test
    fun `동일가중 평균 - 종목 절반 미만인 날은 제외`() {
        val a = mapOf(d("2026-07-01") to 1.0, d("2026-07-02") to 2.0)
        val b = mapOf(d("2026-07-01") to 3.0)
        val c = mapOf(d("2026-07-01") to 5.0)
        val avg = SensitivityValidationService.averageReturns(listOf(a, b, c))
        assertEquals(3.0, avg.getValue(d("2026-07-01")), 1e-9)  // (1+3+5)/3
        // 07-02는 1/3 종목만 존재(minCount=2 미달) → 제외
        assertTrue(d("2026-07-02") !in avg)
    }

    // ── pairSeries ────────────────────────────────────────────────

    @Test
    fun `lag0은 동일 날짜만 페어링`() {
        val indicator = listOf(d("2026-07-01") to 1.5, d("2026-07-04") to -0.5)
        val basket = mapOf(d("2026-07-01") to 0.8, d("2026-07-02") to -0.3)
        val pairs = SensitivityValidationService.pairSeries(indicator, basket, basket.keys.sorted(), lag = 0)
        assertEquals(listOf(1.5 to 0.8), pairs)
    }

    @Test
    fun `lag1은 지표 날짜보다 뒤 첫 거래일 - 주말 건너뜀`() {
        // 금요일 지표 → 월요일 바스켓
        val indicator = listOf(d("2026-07-03") to 2.0)   // 금
        val basket = mapOf(d("2026-07-03") to 0.1, d("2026-07-06") to 1.2)  // 금, 월
        val pairs = SensitivityValidationService.pairSeries(indicator, basket, basket.keys.sorted(), lag = 1)
        assertEquals(listOf(2.0 to 1.2), pairs)
    }

    @Test
    fun `lag1 갭이 7일 초과면 페어링하지 않는다`() {
        val indicator = listOf(d("2026-07-01") to 2.0)
        val basket = mapOf(d("2026-07-20") to 1.2)
        val pairs = SensitivityValidationService.pairSeries(indicator, basket, basket.keys.sorted(), lag = 1)
        assertTrue(pairs.isEmpty())
    }

    // ── computeStats ──────────────────────────────────────────────

    @Test
    fun `완전 정방향 관계면 일치율 100퍼센트에 r 양수`() {
        // 지표 +면 바스켓 +, 지표 -면 바스켓 - (기대부호 +1)
        val pairs = (1..100).map { i ->
            val x = if (i % 2 == 0) 1.0 + i * 0.01 else -(1.0 + i * 0.01)
            x to x * 0.5
        }
        val s = SensitivityValidationService.computeStats(pairs, expectedDir = +1)
        assertEquals(1.0, s.agreeRate, 1e-9)
        assertTrue(s.r > 0.9)
        assertTrue(s.t > 2.0)
    }

    @Test
    fun `기대부호 -1이면 역방향 관계가 지지로 계산된다`() {
        // 지표 상승 → 바스켓 하락 (음의 민감도가 맞는 경우)
        val pairs = (1..100).map { i ->
            val x = if (i % 2 == 0) 1.0 + i * 0.01 else -(1.0 + i * 0.01)
            x to -x * 0.5
        }
        val s = SensitivityValidationService.computeStats(pairs, expectedDir = -1)
        assertEquals(1.0, s.agreeRate, 1e-9)
        assertTrue(s.r > 0.9)
    }

    @Test
    fun `무관한 관계면 일치율 50퍼센트 부근에 r 0 부근`() {
        // 결정적 의사 랜덤: x와 y가 독립 주기
        val pairs = (1..200).map { i ->
            val x = if (i % 2 == 0) 1.0 else -1.0
            val y = if (i % 3 == 0) 0.5 else if (i % 3 == 1) -0.5 else 0.7
            x to y
        }
        val s = SensitivityValidationService.computeStats(pairs, expectedDir = +1)
        assertTrue(abs(s.agreeRate - 0.5) < 0.15, "일치율 ${s.agreeRate}")
        assertTrue(abs(s.r) < 0.2, "r=${s.r}")
    }

    @Test
    fun `노이즈 필터 - 중앙값 미만 등락은 일치율 계산에서 제외`() {
        // 작은 움직임 100개(노이즈, 역방향) + 큰 움직임 100개(정방향)
        val small = (1..100).map { 0.01 to -0.5 }              // 필터로 제거될 것
        val big = (1..100).map { i -> (2.0 + i * 0.01) to 1.0 } // 살아남을 것
        val s = SensitivityValidationService.computeStats(small + big, expectedDir = +1)
        assertEquals(1.0, s.agreeRate, 1e-9)   // 큰 움직임만 남아 전부 일치
        assertTrue(s.nFiltered in 100..101)     // 중앙값 경계 포함 여부에 따라 ±1
    }

    // ── verdictOf ─────────────────────────────────────────────────

    private fun stats(n: Int, agree: Double, r: Double, t: Double) =
        SensitivityValidationService.Companion.CellStats(nAll = n * 2, nFiltered = n, agreeRate = agree, r = r, t = t)

    @Test
    fun `판정 - 표본 부족`() {
        assertEquals("INSUFFICIENT", SensitivityValidationService.verdictOf(stats(79, 0.9, 0.5, 5.0)))
    }

    @Test
    fun `판정 - 일치율과 상관 방향이 함께 지지`() {
        assertEquals("SUPPORTED", SensitivityValidationService.verdictOf(stats(200, 0.56, 0.08, 1.1)))
    }

    @Test
    fun `판정 - 일치율 낮아도 t가 유의하면 지지`() {
        assertEquals("SUPPORTED", SensitivityValidationService.verdictOf(stats(200, 0.51, 0.15, 2.4)))
    }

    @Test
    fun `판정 - 반증은 일치율과 유의 상관이 모두 반대일 때만`() {
        assertEquals("CONTRADICTED", SensitivityValidationService.verdictOf(stats(200, 0.42, -0.18, -2.6)))
        // 일치율만 낮고 상관 무유의 → INCONCLUSIVE (부호 반전은 강한 반증만)
        assertEquals("INCONCLUSIVE", SensitivityValidationService.verdictOf(stats(200, 0.44, -0.05, -0.8)))
    }

    @Test
    fun `판정 - 애매하면 INCONCLUSIVE`() {
        assertEquals("INCONCLUSIVE", SensitivityValidationService.verdictOf(stats(200, 0.50, 0.01, 0.2)))
        // 일치율 높아도 상관 방향 반대면 지지 아님
        assertEquals("INCONCLUSIVE", SensitivityValidationService.verdictOf(stats(200, 0.55, -0.02, -0.3)))
    }
}
