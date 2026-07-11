package com.haky.edge

import com.haky.edge.ai.PortfolioReviewService
import com.haky.edge.ai.PortfolioReviewService.StockCalc
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.HoldingPosition
import com.haky.edge.macro.MacroImpactService.MacroGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 포트폴리오 진단(B) 집계 순수 함수 검증 — 비중·섹터·매크로 노출·밸류 분포·캐시 키. */
class PortfolioMathTest {

    private fun stock(
        code: String, name: String, value: Long,
        sector: String = "기타", groups: Set<MacroGroup> = emptySet(), vb: String? = null,
    ) = StockCalc(code, name, value, cost = value, pnlPct = 0.0, sectorLabel = sector, groups = groups, valuationLabel = vb)

    @Test
    fun `섹터 비중 - 평가금액 기준 내림차순, 종목명 포함`() {
        val stocks = listOf(
            stock("000001", "가", 600, sector = "반도체"),
            stock("000002", "나", 300, sector = "방산"),
            stock("000003", "다", 100, sector = "반도체"),
        )
        val ws = PortfolioReviewService.sectorWeights(stocks, 1000)
        assertEquals("반도체", ws[0].label)
        assertEquals(70.0, ws[0].weightPct)
        assertEquals(listOf("가", "다"), ws[0].stockNames) // 비중 큰 종목 먼저
        assertEquals(30.0, ws[1].weightPct)
    }

    @Test
    fun `매크로 노출 - 민감도 방향대로 수혜와 부담 비중 집계`() {
        // SEMICONDUCTOR: usdkrw -1(실측 교정), rate3y -1 / SHIPBUILDING: usdkrw -1, crude +1, rate3y -1
        val stocks = listOf(
            stock("000001", "가", 700, groups = setOf(MacroGroup.SEMICONDUCTOR)),
            stock("000002", "나", 300, groups = setOf(MacroGroup.SHIPBUILDING)),
        )
        val ex = PortfolioReviewService.macroExposures(stocks, 1000)
        val usdkrw = ex.first { it.label.contains("원/달러") }
        assertEquals(0.0, usdkrw.favorablePct)   // 환율 급등은 둘 다 부담(리스크오프 신호 — D2 교정)
        assertEquals(100.0, usdkrw.adversePct)
        val rate = ex.first { it.label.contains("금리") }
        assertEquals(0.0, rate.favorablePct)
        assertEquals(100.0, rate.adversePct)     // 둘 다 금리 상승 부담
        val crude = ex.first { it.label.contains("유가") }
        assertEquals(30.0, crude.favorablePct)   // 조선만 수혜
        assertEquals(70.0, crude.adversePct)     // 반도체는 유가 -1
    }

    @Test
    fun `매크로 노출 - 아무 종목도 안 걸리는 지표는 생략`() {
        val stocks = listOf(stock("000001", "가", 100, groups = setOf(MacroGroup.DEFENSE))) // usdkrw만 있음
        val ex = PortfolioReviewService.macroExposures(stocks, 100)
        assertEquals(1, ex.size)
        assertTrue(ex[0].label.contains("원/달러"))
    }

    @Test
    fun `밸류 분포 - 라벨 없는 종목은 계산 불가로 묶고 순서 고정`() {
        val stocks = listOf(
            stock("000001", "가", 500, vb = "역사적 상단권"),
            stock("000002", "나", 300, vb = null),
            stock("000003", "다", 200, vb = "역사적 하단권"),
        )
        val dist = PortfolioReviewService.valuationDist(stocks, 1000)
        assertEquals(listOf("역사적 상단권", "역사적 하단권", "밴드 계산 불가"), dist.map { it.label })
        assertEquals(50.0, dist[0].weightPct)
        assertEquals(1, dist[2].count)
    }

    @Test
    fun `캐시 키 - 포지션 순서 무관, 수량 변경 시 변경`() {
        val a = mapOf("005930" to HoldingPosition(70000.0, 10), "000660" to HoldingPosition(200000.0, 5))
        val b = mapOf("000660" to HoldingPosition(200000.0, 5), "005930" to HoldingPosition(70000.0, 10))
        val k1 = PortfolioReviewService.buildKey("2026-07-04", a, AnalysisMode.DEFENSIVE)
        val k2 = PortfolioReviewService.buildKey("2026-07-04", b, AnalysisMode.DEFENSIVE)
        assertEquals(k1, k2)
        val c = mapOf("005930" to HoldingPosition(70000.0, 11), "000660" to HoldingPosition(200000.0, 5))
        assertTrue(k1 != PortfolioReviewService.buildKey("2026-07-04", c, AnalysisMode.DEFENSIVE))
        assertTrue(k1 != PortfolioReviewService.buildKey("2026-07-04", a, AnalysisMode.AGGRESSIVE))
    }

    @Test
    fun `캐시 키 - 논지 없으면 기존 키 불변, 논지·내용 변경 시 분리`() {
        val pos = mapOf("005930" to HoldingPosition(70000.0, 10))
        val base = PortfolioReviewService.buildKey("2026-07-10", pos, AnalysisMode.DEFENSIVE)
        // 빈 맵·빈 값은 기존 키 그대로(구버전 앱 호환)
        assertEquals(base, PortfolioReviewService.buildKey("2026-07-10", pos, AnalysisMode.DEFENSIVE, emptyMap()))
        assertEquals(base, PortfolioReviewService.buildKey("2026-07-10", pos, AnalysisMode.DEFENSIVE, mapOf("005930" to " ")))
        // 논지가 있으면 분리, 내용이 다르면 다른 키, 같으면 같은 키
        val t1 = PortfolioReviewService.buildKey("2026-07-10", pos, AnalysisMode.DEFENSIVE, mapOf("005930" to "HBM 회복"))
        val t2 = PortfolioReviewService.buildKey("2026-07-10", pos, AnalysisMode.DEFENSIVE, mapOf("005930" to "밸류 저평가"))
        assertTrue(base != t1)
        assertTrue(t1 != t2)
        assertEquals(t1, PortfolioReviewService.buildKey("2026-07-10", pos, AnalysisMode.DEFENSIVE, mapOf("005930" to " HBM 회복 ")))
    }

    @Test
    fun `pct - 총액 0 방어`() {
        assertEquals(0.0, PortfolioReviewService.pct(100, 0))
    }

    @Test
    fun `프롬프트 - 모드별 스탠스 분기와 공통 가드`() {
        val d = PortfolioReviewService.promptFor(AnalysisMode.DEFENSIVE)
        val a = PortfolioReviewService.promptFor(AnalysisMode.AGGRESSIVE)
        assertTrue(d.contains("스탠스(방어 모드)"))
        assertTrue(a.contains("스탠스(공격 모드"))
        listOf(d, a).forEach {
            assertTrue(it.contains("### 핵심 요약"))
            assertTrue(it.contains("마지막 경고"))
            assertTrue(it.contains("P3.")) // 분산 설교 금지
            assertTrue(it.contains("P5.")) // 오늘 방향 예측 금지
        }
    }
}
