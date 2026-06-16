package com.haky.edge

import com.haky.edge.ai.BacktestService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BacktestMathTest {

    // ── pearson ───────────────────────────────────────────────────────────

    @Test fun `완전 양의 상관(r=1)`() {
        // ys = xs * 2 → 선형 비례, r = 1.0
        val xs = listOf(1.0, 2.0, 3.0)
        val ys = listOf(2.0, 4.0, 6.0)
        assertEquals(1.0, BacktestService.pearson(xs, ys), 1e-9)
    }

    @Test fun `완전 음의 상관(r=-1)`() {
        val xs = listOf(1.0, 2.0, 3.0)
        val ys = listOf(3.0, 2.0, 1.0)
        assertEquals(-1.0, BacktestService.pearson(xs, ys), 1e-9)
    }

    @Test fun `산포 없는 y → 상관 계산 불가, 0 반환`() {
        // dy = 0 → denom < 1e-10 → 0.0
        val xs = listOf(1.0, 2.0, 3.0)
        val ys = listOf(5.0, 5.0, 5.0)
        assertEquals(0.0, BacktestService.pearson(xs, ys), 1e-9)
    }

    @Test fun `n=1 → 0 반환`() {
        assertEquals(0.0, BacktestService.pearson(listOf(1.0), listOf(2.0)), 1e-9)
    }

    @Test fun `n=0 → 0 반환`() {
        assertEquals(0.0, BacktestService.pearson(emptyList(), emptyList()), 1e-9)
    }

    @Test fun `부분 양의 상관 — 범위 내 값`() {
        // [1,2,3,4] vs [2,4,5,4]: 계산 결과 약 0.72
        val xs = listOf(1.0, 2.0, 3.0, 4.0)
        val ys = listOf(2.0, 4.0, 5.0, 4.0)
        val r = BacktestService.pearson(xs, ys)
        assertTrue(r in 0.6..0.9, "부분 양의 상관 r=$r")
    }

    @Test fun `결과는 항상 -1 ~ 1 범위`() {
        // 부동소수점 오차 방지용 coerceIn 확인
        val xs = listOf(1.0, 1.0 + 1e-15, 1.0 + 2e-15)
        val ys = listOf(1.0, 1.0 + 1e-15, 1.0 + 2e-15)
        val r = BacktestService.pearson(xs, ys)
        assertTrue(r in -1.0..1.0, "범위 초과: r=$r")
    }

    // ── corrLabel ─────────────────────────────────────────────────────────

    @Test fun `r=0_0 → 거의 무관`() {
        assertEquals("거의 무관", BacktestService.corrLabel(0.0))
    }

    @Test fun `r=0_05 → 거의 무관`() {
        assertEquals("거의 무관", BacktestService.corrLabel(0.05))
    }

    @Test fun `r=0_1 → 양의 약한 상관`() {
        assertEquals("양의 약한 상관", BacktestService.corrLabel(0.1))
    }

    @Test fun `r=0_3 → 양의 중간 상관`() {
        assertEquals("양의 중간 상관", BacktestService.corrLabel(0.3))
    }

    @Test fun `r=0_5 → 양의 강한 상관`() {
        assertEquals("양의 강한 상관", BacktestService.corrLabel(0.5))
    }

    @Test fun `r=1_0 → 양의 강한 상관`() {
        assertEquals("양의 강한 상관", BacktestService.corrLabel(1.0))
    }

    @Test fun `r=-0_1 → 음의 약한 상관`() {
        assertEquals("음의 약한 상관", BacktestService.corrLabel(-0.1))
    }

    @Test fun `r=-0_3 → 음의 중간 상관`() {
        assertEquals("음의 중간 상관", BacktestService.corrLabel(-0.3))
    }

    @Test fun `r=-0_5 → 음의 강한 상관`() {
        assertEquals("음의 강한 상관", BacktestService.corrLabel(-0.5))
    }

    @Test fun `r=-1_0 → 음의 강한 상관`() {
        assertEquals("음의 강한 상관", BacktestService.corrLabel(-1.0))
    }
}
