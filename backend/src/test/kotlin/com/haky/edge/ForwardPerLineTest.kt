package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.dart.QuarterlyIncome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardPerLineTest {

    private fun q(label: String, ni: Long?) = QuarterlyIncome(label, ni, null, null)

    @Test fun `1분기 누적은 4배 연환산`() {
        // 순이익 1분기 1조, 상장주식수 1억주 → 연환산 EPS 40,000원. 주가 400,000원 → 포워드 PER 10.0
        val line = AnalysisService.forwardPerLine(400_000, q("2026년 1분기", 1_000_000_000_000), 100_000_000)!!
        assertTrue(line.contains("약 10.0배"), line)
        assertTrue(line.contains("연환산"), line)
    }

    @Test fun `반기 누적은 2배 연환산`() {
        // 반기 2조 → 연환산 4조, EPS 40,000원 → PER 10.0
        val line = AnalysisService.forwardPerLine(400_000, q("2026년 반기", 2_000_000_000_000), 100_000_000)!!
        assertTrue(line.contains("약 10.0배"), line)
    }

    @Test fun `3분기 누적은 4over3배 연환산`() {
        // 3분기 3조 → 연환산 4조 → PER 10.0
        val line = AnalysisService.forwardPerLine(400_000, q("2026년 3분기", 3_000_000_000_000), 100_000_000)!!
        assertTrue(line.contains("약 10.0배"), line)
    }

    @Test fun `적자면 null`() {
        assertNull(AnalysisService.forwardPerLine(400_000, q("2026년 1분기", -500_000_000_000), 100_000_000))
    }

    @Test fun `데이터 없으면 null`() {
        assertNull(AnalysisService.forwardPerLine(400_000, null, 100_000_000))
        assertNull(AnalysisService.forwardPerLine(400_000, q("2026년 1분기", null), 100_000_000))
        assertNull(AnalysisService.forwardPerLine(400_000, q("2026년 1분기", 1_000_000_000_000), null))
        assertNull(AnalysisService.forwardPerLine(400_000, q("2026년 1분기", 1_000_000_000_000), 0))
    }

    @Test fun `이상치 - 포워드 PER 500배 초과는 null`() {
        // 이익이 미미해 PER 폭발 → 비교 무의미, 줄 생략.
        assertNull(AnalysisService.forwardPerLine(400_000, q("2026년 1분기", 1_000_000), 100_000_000))
    }

    @Test fun `알 수 없는 라벨이면 null`() {
        assertNull(AnalysisService.forwardPerLine(400_000, q("2026년 사업보고서", 1_000_000_000_000), 100_000_000))
    }
}
