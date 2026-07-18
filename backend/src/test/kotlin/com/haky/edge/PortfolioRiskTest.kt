package com.haky.edge

import com.haky.edge.ai.PortfolioRiskService
import com.haky.edge.ai.RiskHolding
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.IndexPoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 포트폴리오 리스크 엔진 — 순수 함수(compute) 검증.
 * 합성 로그수익률 계열로 포트폴리오 이론 항등식을 확인한다:
 *  ρ=1  → σp = Σwσ (분산효과 0), ρ=-1 → σp = |w₁σ₁-w₂σ₂|, ρ=0 → σp² = Σw²σ².
 * 패턴: s2(+,-반복, 주기2)와 s4(+,+,-,-, 주기4)는 60관측(공배수)에서 직교(상관 0).
 */
class PortfolioRiskTest {

    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    private val start = LocalDate.parse("2026-01-01")

    private fun s2(i: Int) = if (i % 2 == 0) 1.0 else -1.0
    private fun s4(i: Int) = if (i % 4 < 2) 1.0 else -1.0

    /** 로그수익률 계열 → 일봉(오래된 순, 61봉). 첫 봉 base, 이후 C_t = C_{t-1}·e^r. */
    private fun bars(returns: List<Double>, base: Double = 1_000_000.0): List<DailyBar> {
        var c = base
        val out = mutableListOf(bar(0, base.toLong()))
        returns.forEachIndexed { i, r ->
            c *= exp(r)
            out += bar(i + 1, Math.round(c))
        }
        return out
    }

    private fun bar(i: Int, close: Long): DailyBar {
        val d = start.plusDays(i.toLong()).format(ymd)
        return DailyBar(d, close, close, close, close, 100)
    }

    private fun kospi(returns: List<Double>, base: Double = 2500.0): List<IndexPoint> {
        var c = base
        val out = mutableListOf(IndexPoint(start.format(ymd), base))
        returns.forEachIndexed { i, r ->
            c *= exp(r)
            out += IndexPoint(start.plusDays(i.toLong() + 1).format(ymd), c)
        }
        return out
    }

    private fun rets(n: Int = 60, f: (Int) -> Double): List<Double> = (0 until n).map(f)

    private fun compute(
        holdings: List<RiskHolding>,
        barsByCode: Map<String, List<DailyBar>>,
        kospiAsc: List<IndexPoint> = emptyList(),
    ) = PortfolioRiskService.compute(holdings, barsByCode, kospiAsc, "2026-07-18")

    @Test
    fun `완전 동행 - 상관 1, 분산효과 없음, 클러스터 1개`() {
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 1), RiskHolding("BBBBB1", "나", 1)),
            mapOf(
                "AAAAA1" to bars(rets { 0.01 * s2(it) }),
                "BBBBB1" to bars(rets { 0.02 * s2(it) }),
            ))
        val corr = assertNotNull(r.avgCorr)
        assertEquals(1.0, corr, 0.01)
        // σp = w₁σ₁ + w₂σ₂ (Cauchy 등호) → DR = 1
        assertEquals(1.0, r.diversificationRatio, 0.01)
        assertEquals(r.weightedAvgVolPct, r.portfolioVolPct, 0.05)
        assertEquals(1, r.clusters.size)
        assertEquals(setOf("가", "나"), r.clusters[0].names.toSet())
        assertEquals(100.0, r.clusters[0].weightPct, 0.1)
        assertEquals(1, r.topPairs.size)
    }

    @Test
    fun `완전 역행 - 상관 -1, 변동성 상쇄`() {
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 1), RiskHolding("BBBBB1", "나", 1)),
            mapOf(
                "AAAAA1" to bars(rets { 0.01 * s2(it) }),
                "BBBBB1" to bars(rets { -0.02 * s2(it) }),
            ))
        assertEquals(-1.0, assertNotNull(r.avgCorr), 0.01)
        // σp = |0.5σ₁ - 0.5σ₂| → 가중평균의 1/3 → DR ≈ 3
        assertEquals(3.0, r.diversificationRatio, 0.05)
        assertTrue(r.clusters.isEmpty())
        assertTrue(r.topPairs.isEmpty())
    }

    @Test
    fun `무상관 - 분산 합산, 리스크 기여도는 비중과 다르다`() {
        // 동일 비중인데 나(σ 2배) → 분산 기여 = 4σ²/(σ²+4σ²) = 80%
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 1), RiskHolding("BBBBB1", "나", 1)),
            mapOf(
                "AAAAA1" to bars(rets { 0.01 * s2(it) }),
                "BBBBB1" to bars(rets { 0.02 * s4(it) }),
            ))
        assertEquals(0.0, assertNotNull(r.avgCorr), 0.05)
        val byName = r.stocks.associateBy { it.name }
        assertEquals(50.0, byName.getValue("나").weightPct, 0.5)
        assertEquals(80.0, byName.getValue("나").riskContribPct, 3.0)
        assertEquals(100.0, r.stocks.sumOf { it.riskContribPct }, 0.5)
        // σp² = w²(σ₁²+σ₂²) 확인: σp = 0.5·√(σ₁²+σ₂²) = 0.5·√5·σ₁
        val expected = 0.5 * Math.sqrt(5.0) * (byName.getValue("가").volPct)
        assertEquals(expected, r.portfolioVolPct, expected * 0.02)
    }

    @Test
    fun `베타 - 코스피 2배 민감 종목`() {
        val kospiRets = rets { 0.01 * s2(it) }
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 1)),
            mapOf("AAAAA1" to bars(rets { 0.02 * s2(it) })),
            kospi(kospiRets))
        assertEquals(2.0, assertNotNull(r.stocks[0].beta), 0.03)
        assertEquals(2.0, assertNotNull(r.portfolioBeta), 0.03)
        assertNotNull(r.kospiVolPct)
    }

    @Test
    fun `클러스터 전이성 - A-B, B-C 간선만으로 3종목 묶임`() {
        // A=s2, B=1.2·s2+s4, C=0.5·s2+s4 → corr(A,B)=0.77, corr(B,C)=0.92, corr(A,C)=0.45(<0.7)
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 1), RiskHolding("BBBBB1", "나", 1), RiskHolding("CCCCC1", "다", 1)),
            mapOf(
                "AAAAA1" to bars(rets { 0.01 * s2(it) }),
                "BBBBB1" to bars(rets { 0.01 * (1.2 * s2(it) + s4(it)) }),
                "CCCCC1" to bars(rets { 0.01 * (0.5 * s2(it) + s4(it)) }),
            ))
        assertEquals(1, r.clusters.size)
        assertEquals(setOf("가", "나", "다"), r.clusters[0].names.toSet())
        assertEquals(2, r.topPairs.size)  // A-C(0.45)는 간선 아님
        assertTrue(r.topPairs.all { it.corr >= 0.7 })
    }

    @Test
    fun `관측 부족 종목 제외 - 비중 재정규화`() {
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 1), RiskHolding("BBBBB1", "신규상장", 1)),
            mapOf(
                "AAAAA1" to bars(rets { 0.01 * s2(it) }),
                "BBBBB1" to bars(rets(20) { 0.01 * s2(it) }),  // 20관측 < 40
            ))
        assertEquals(listOf("신규상장"), r.excluded)
        assertEquals(1, r.stocks.size)
        assertEquals(100.0, r.stocks[0].weightPct, 0.01)
        assertTrue(r.caveat.contains("신규상장"))
    }

    @Test
    fun `집중도 - HHI와 상위2 비중`() {
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 1), RiskHolding("BBBBB1", "나", 1)),
            mapOf(
                "AAAAA1" to bars(rets { 0.01 * s2(it) }),
                "BBBBB1" to bars(rets { 0.01 * s4(it) }),
            ))
        // 같은 base·수량·전액 사이클 복귀 → 50:50
        assertTrue(abs(r.hhi - 5000) <= 10, "hhi=${r.hhi}")
        assertEquals(100.0, r.top2WeightPct, 0.01)
    }

    @Test
    fun `단일 종목 - 자기 변동성, 기여 100, 상관 없음`() {
        val r = compute(
            listOf(RiskHolding("AAAAA1", "가", 3)),
            mapOf("AAAAA1" to bars(rets { 0.01 * s2(it) })))
        assertEquals(1, r.stocks.size)
        assertEquals(100.0, r.stocks[0].weightPct, 0.01)
        assertEquals(100.0, r.stocks[0].riskContribPct, 0.01)
        assertEquals(r.stocks[0].volPct, r.portfolioVolPct, 0.01)
        assertEquals(1.0, r.diversificationRatio, 0.01)
        assertNull(r.avgCorr)
        assertNull(r.portfolioBeta)  // 코스피 미제공
        // 일간 ±1% 교대 → 연환산 ≈ 16% 부근(정합성 새니티)
        assertEquals(16.0, r.portfolioVolPct, 1.0)
    }
}
