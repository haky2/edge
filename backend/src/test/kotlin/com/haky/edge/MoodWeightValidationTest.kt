package com.haky.edge

import com.haky.edge.kis.MacroIndicator
import com.haky.edge.macro.MarketMoodLogService
import com.haky.edge.macro.MoodWeightValidationService
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ③ MoodLog 가중치 실측 — 정렬(T-1)·단변량 판정·재현 정확도 순수 함수. */
class MoodWeightValidationTest {

    private val d0 = LocalDate.of(2025, 1, 6) // 월요일

    // ── toChanges / alignRows ──────────────────────────────────────

    @Test
    fun `toChanges - 전일 대비 %, 첫 항목 제외`() {
        val closes = listOf(d0 to 100.0, d0.plusDays(1) to 102.0, d0.plusDays(2) to 51.0)
        val changes = MoodWeightValidationService.toChanges(closes)
        assertEquals(2, changes.size)
        assertEquals(2.0, changes[0].second, 1e-9)
        assertEquals(-50.0, changes[1].second, 1e-9)
    }

    @Test
    fun `alignRows - 지표는 T보다 앞선 마지막 봉(T-1), 코스피 당일 봉은 쓰지 않음`() {
        // 코스피 화요일(1/7) 행 — 지표는 월요일(1/6) 봉이어야 함(같은 날 1/7 봉이 있어도)
        val kospi = listOf(d0.plusDays(1) to 1.0)
        val indicator = listOf(d0 to 0.5, d0.plusDays(1) to -9.9)
        val rows = MoodWeightValidationService.alignRows(kospi, mapOf("nasdaq" to indicator))
        assertEquals(1, rows.size)
        assertEquals(0.5, rows[0].changes.getValue("nasdaq"), 1e-9)
    }

    @Test
    fun `alignRows - 갭 7일 초과 지표는 생략, 전부 없으면 행 제외`() {
        val kospi = listOf(d0.plusDays(10) to 1.0)
        val stale = listOf(d0 to 0.5) // 10일 전 — 갭 초과
        val rows = MoodWeightValidationService.alignRows(kospi, mapOf("nasdaq" to stale))
        assertTrue(rows.isEmpty())
    }

    // ── univariate ─────────────────────────────────────────────────

    private fun rows(n: Int, chg: (Int) -> Double, kospi: (Int) -> Double) = (0 until n).map { i ->
        MoodWeightValidationService.Companion.Row(
            date = d0.plusDays(i.toLong()),
            kospiChg = kospi(i),
            changes = mapOf("x" to chg(i)),
        )
    }

    @Test
    fun `univariate - 완전 동행 지표는 SUPPORTED, 완전 역행은 CONTRADICTED`() {
        // 등락 다양(노이즈 필터 통과분 확보) + 코스피가 지표를 그대로 따라감
        val follow = rows(200, { i -> (i % 7 - 3).toDouble() }, { i -> (i % 7 - 3).toDouble() })
        val s = MoodWeightValidationService.univariate(follow, "x", weight = 1.0)
        assertEquals("SUPPORTED", s.verdict)
        assertTrue(s.rho > 0.9)

        // 같은 데이터에 가중치 부호가 반대면(기대 역방향) 반증
        val c = MoodWeightValidationService.univariate(follow, "x", weight = -1.0)
        assertEquals("CONTRADICTED", c.verdict)
    }

    @Test
    fun `univariate - 무관 지표는 INCONCLUSIVE, 표본 부족은 INSUFFICIENT`() {
        // 지표는 ±1 교대, 코스피는 4주기 — 상관 0 근처
        val noise = rows(200, { i -> if (i % 2 == 0) 1.0 else -1.0 }, { i -> if (i % 4 < 2) 1.0 else -1.0 })
        assertEquals("INCONCLUSIVE", MoodWeightValidationService.univariate(noise, "x", 1.0).verdict)

        val tiny = rows(30, { 1.0 }, { 1.0 })
        assertEquals("INSUFFICIENT", MoodWeightValidationService.univariate(tiny, "x", 1.0).verdict)
    }

    // ── accuracy + 정본 파리티 ─────────────────────────────────────

    @Test
    fun `accuracy - inferDirectionWith·classifyActual 재사용 결과와 일치`() {
        val sample = listOf(
            MoodWeightValidationService.Companion.Row(d0, kospiChg = 1.0, changes = mapOf("nasdaq" to 2.0)),   // BULLISH 예측·실제 → 정답
            MoodWeightValidationService.Companion.Row(d0.plusDays(1), kospiChg = -1.0, changes = mapOf("nasdaq" to 2.0)), // BULLISH 예측·BEARISH 실제 → 오답
            MoodWeightValidationService.Companion.Row(d0.plusDays(2), kospiChg = 0.1, changes = mapOf("nasdaq" to 0.1)),  // NEUTRAL 예측·실제 → 정답
        )
        val weights = mapOf("nasdaq" to 3.0)
        assertEquals(2 * 100.0 / 3, MoodWeightValidationService.accuracy(sample, weights), 1e-9)
    }

    @Test
    fun `파리티 - inferDirectionWith는 서비스 inferDirection과 동일`() {
        val service = MarketMoodLogService()
        fun ind(key: String, rate: Double) = MacroIndicator(key, key, 100.0, 1.0, rate)
        val inds = listOf(ind("nasdaq", 1.2), ind("dxy", 0.8), ind("usdkrw", -0.3), ind("kospi", 9.9))
        val viaService = service.inferDirection(inds)
        val viaWeights = MarketMoodLogService.inferDirectionWith(
            MarketMoodLogService.LEADING_WEIGHTS,
            inds.associate { it.key to it.changeRate },
        )
        assertEquals(viaService, viaWeights)
    }
}
