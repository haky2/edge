package com.haky.edge

import com.haky.edge.ai.PortfolioRisk
import com.haky.edge.ai.PortfolioStressService
import com.haky.edge.ai.RiskCluster
import com.haky.edge.ai.RiskStock
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 시나리오 스트레스 — 순수 변환(build) 검증.
 * 합성 PortfolioRisk(베타·평가금액·클러스터)로 베타 전파 항등식과 클러스터 합산을 확인한다.
 */
class PortfolioStressTest {

    private fun stock(code: String, name: String, value: Long, beta: Double?, weight: Double) =
        RiskStock(code = code, name = name, weightPct = weight, volPct = 20.0, beta = beta, riskContribPct = weight, value = value)

    private fun risk(stocks: List<RiskStock>, clusters: List<RiskCluster> = emptyList(), portfolioBeta: Double? = null) =
        PortfolioRisk(
            date = "2026-07-20", windowDays = 60, stocks = stocks,
            portfolioVolPct = 20.0, weightedAvgVolPct = 22.0, diversificationRatio = 1.1,
            portfolioBeta = portfolioBeta, hhi = 5000, top2WeightPct = 100.0,
            clusters = clusters,
        )

    @Test
    fun `베타 전파 항등식 - 종목 손익 = 평가금액 × 베타 × 등락`() {
        val r = risk(listOf(
            stock("AAAAA1", "가", value = 1_000_000, beta = 1.2, weight = 50.0),
            stock("BBBBB1", "나", value = 1_000_000, beta = 0.8, weight = 50.0),
        ))
        val s = PortfolioStressService.build(r)

        val down10 = s.scenarios.first { it.kospiMovePct == -10.0 }
        val ga = down10.stocks.first { it.code == "AAAAA1" }
        val na = down10.stocks.first { it.code == "BBBBB1" }
        // 1,000,000 × 1.2 × -10 / 100 = -120,000
        assertEquals(-120_000L, ga.pnlAmount)
        assertEquals(-80_000L, na.pnlAmount)
        assertEquals(-12.0, ga.pnlPct, 1e-9)
        // 포트폴리오 = 합, pct = 합 / 총액
        assertEquals(-200_000L, down10.portfolioPnlAmount)
        assertEquals(-10.0, down10.portfolioPnlPct, 1e-9) // (베타 가중평균 1.0) × -10
    }

    @Test
    fun `선형성 - -10퍼센트 손익은 -5퍼센트의 정확히 2배, +5퍼센트는 부호 반대`() {
        val r = risk(listOf(stock("AAAAA1", "가", 2_000_000, beta = 1.5, weight = 100.0)))
        val s = PortfolioStressService.build(r)
        val d10 = s.scenarios.first { it.kospiMovePct == -10.0 }.portfolioPnlAmount
        val d5 = s.scenarios.first { it.kospiMovePct == -5.0 }.portfolioPnlAmount
        val u5 = s.scenarios.first { it.kospiMovePct == 5.0 }.portfolioPnlAmount
        assertEquals(d5 * 2, d10)
        assertEquals(-d5, u5)
        assertTrue(u5 > 0) // 양의 베타 + 코스피 상승 = 이익
    }

    @Test
    fun `베타 없는 종목은 시나리오에서 제외되고 betaExcluded에 오른다`() {
        val r = risk(listOf(
            stock("AAAAA1", "가", 1_000_000, beta = 1.0, weight = 50.0),
            stock("BBBBB1", "나", 1_000_000, beta = null, weight = 50.0),
        ))
        val s = PortfolioStressService.build(r)
        assertEquals(listOf("나"), s.betaExcluded)
        val d10 = s.scenarios.first { it.kospiMovePct == -10.0 }
        assertEquals(1, d10.stocks.size)              // 베타 있는 종목만
        assertEquals(-100_000L, d10.portfolioPnlAmount) // 가만 반영
        // pct는 총 평가금액(200만) 기준이라 희석됨 = -5%
        assertEquals(-5.0, d10.portfolioPnlPct, 1e-9)
    }

    @Test
    fun `클러스터 동반 하락 - 구성원 -10퍼센트 손익 합, 총액 대비 비중`() {
        val r = risk(
            stocks = listOf(
                stock("AAAAA1", "가", 1_000_000, beta = 1.2, weight = 40.0),
                stock("BBBBB1", "나", 1_000_000, beta = 1.3, weight = 40.0),
                stock("CCCCC1", "다", 500_000, beta = 0.5, weight = 20.0),
            ),
            clusters = listOf(RiskCluster(names = listOf("가", "나"), weightPct = 80.0, avgCorr = 0.83)),
        )
        val s = PortfolioStressService.build(r)
        assertEquals(1, s.clusters.size)
        val cl = s.clusters.first()
        // 가 -120,000 + 나 -130,000 = -250,000
        assertEquals(-250_000L, cl.combinedDropAmount)
        // 총액 250만 → -10%
        assertEquals(-10.0, cl.combinedDropPct, 1e-9)
        assertEquals(0.83, cl.avgCorr, 1e-9)
    }

    @Test
    fun `클러스터에 베타 없는 구성원만 남으면 제외`() {
        val r = risk(
            stocks = listOf(
                stock("AAAAA1", "가", 1_000_000, beta = null, weight = 50.0),
                stock("BBBBB1", "나", 1_000_000, beta = 1.0, weight = 50.0),
            ),
            clusters = listOf(RiskCluster(names = listOf("가", "나"), weightPct = 100.0, avgCorr = 0.75)),
        )
        val s = PortfolioStressService.build(r)
        // 클러스터 구성원 중 베타 있는 건 1개뿐 → 동반 하락 표기 제외
        assertTrue(s.clusters.isEmpty())
    }

    @Test
    fun `라벨 포맷 - 부호와 퍼센트`() {
        val r = risk(listOf(stock("AAAAA1", "가", 1_000_000, beta = 1.0, weight = 100.0)))
        val s = PortfolioStressService.build(r)
        assertEquals("코스피 -10%", s.scenarios.first { it.kospiMovePct == -10.0 }.label)
        assertEquals("코스피 +5%", s.scenarios.first { it.kospiMovePct == 5.0 }.label)
    }

    @Test
    fun `총 평가금액 0이면 pct는 0으로 안전 폴백`() {
        val r = risk(listOf(stock("AAAAA1", "가", 0, beta = 1.0, weight = 100.0)))
        val s = PortfolioStressService.build(r)
        val d10 = s.scenarios.first { it.kospiMovePct == -10.0 }
        assertEquals(0L, d10.portfolioPnlAmount)
        assertEquals(0.0, d10.portfolioPnlPct, 1e-9)
    }
}
