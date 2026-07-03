package com.haky.edge

import com.haky.edge.ai.AnalysisService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "### 핵심 요약" 가격류 환각 가드(suspiciousSummaryPrices) 회귀 테스트.
 * 실사고: 2026-06-15 삼성전자 공격 모드 요약에 학습 프라이어 주가 "53,700원"이 누출
 * (facts 현재가 337,000원). 이 케이스가 반드시 잡혀야 한다.
 */
class SummaryPriceGuardTest {

    private val facts = """
        종목: 삼성전자 (005930)
        현재가: 337000원 (전일대비 +14500, +4.5%)
        52주: 최고 370000 / 최저 56900 (현재 위치 90%, 고점 대비 -8.9%)
        애널리스트 컨센서스 목표주가: 437,500원 (현재가 대비 +29.8%)
        회사 재무(DART 연결 사업보고서 2025년, 단위 억원):
          매출액 3,336,059억 (전년 3,009,067억, YoY +10.9%)
    """.trimIndent()

    @Test fun `실사고 케이스 - 프라이어 주가 53,700원 검출`() {
        val summary = "삼성전자는 2거래일 만에 +12.4% 급등하며 **53,700원(+4.5%)**에 마감했으나, 컨센서스 목표주가 **437,500원**이 있다."
        val suspicious = AnalysisService.suspiciousSummaryPrices(facts, summary)
        assertTrue(53700.0 in suspicious, "facts에 없는 53,700이 검출돼야 함: $suspicious")
        assertTrue(437500.0 !in suspicious, "facts에 있는 437,500은 통과해야 함")
    }

    @Test fun `facts 수치만 쓴 정상 요약은 통과`() {
        val summary = "삼성전자는 **337,000원**에 마감했고 52주 최고 **370,000원** 대비 -8.9%, 목표주가 **437,500원**까지 +29.8% 여력이 있다."
        assertEquals(emptyList(), AnalysisService.suspiciousSummaryPrices(facts, summary))
    }

    @Test fun `퍼센트·배수 등 1000 미만 수치는 검사 제외 - 가공 표현 오탐 방지`() {
        // +12.4%(모델 계산), PER 43.4배 같은 소수치는 facts에 없어도 잡지 않는다.
        val summary = "전일 대비 **+12.4%** 올랐고 PER은 **43.4배** 수준이다. 주가는 **337,000원**."
        assertEquals(emptyList(), AnalysisService.suspiciousSummaryPrices(facts, summary))
    }

    @Test fun `±5퍼센트 이내 라운드 표현은 통과`() {
        // "약 34만원" → 340000, facts 337000 대비 0.9% 차이 → 정당한 라운드로 본다.
        val summary = "주가는 약 **34만**원 수준에서 마감했다."
        assertEquals(emptyList(), AnalysisService.suspiciousSummaryPrices(facts, summary))
    }

    @Test fun `한국어 복합 단위 파싱 - 만 단위 환각 검출`() {
        // facts에 없는 "50만 3,000원" → 503000 검출.
        val summary = "주가가 **50만 3,000원**을 돌파했다."
        val suspicious = AnalysisService.suspiciousSummaryPrices(facts, summary)
        assertTrue(503000.0 in suspicious)
    }

    @Test fun `요약이 null이거나 비면 빈 결과`() {
        assertEquals(emptyList(), AnalysisService.suspiciousSummaryPrices(facts, null))
        assertEquals(emptyList(), AnalysisService.suspiciousSummaryPrices(facts, " "))
    }
}
