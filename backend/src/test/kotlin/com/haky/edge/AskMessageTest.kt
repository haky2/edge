package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.AskTurn
import com.haky.edge.macro.AnalysisMode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Q&A user 메시지 조립(renderAskUserMessage) + 모드별 프롬프트(askPrompt) 검증. */
class AskMessageTest {

    private val facts = "종목: 테스트 (000000)\n현재가: 10000원"

    @Test
    fun `history 없이 facts와 질문만 조립`() {
        val msg = AnalysisService.renderAskUserMessage(facts, emptyList(), "  지금 싼 편이야?  ")
        assertContains(msg, "현재가: 10000원")
        assertTrue(msg.endsWith("사용자 질문: 지금 싼 편이야?"), "질문이 트림되어 맨 끝에 와야 함")
        assertFalse(msg.contains("이전 문답"))
    }

    @Test
    fun `history는 최근 3턴만 포함`() {
        val history = (1..5).map { AskTurn("질문$it", "답변$it") }
        val msg = AnalysisService.renderAskUserMessage(facts, history, "그럼?")
        assertFalse(msg.contains("질문1"))
        assertFalse(msg.contains("질문2"))
        assertContains(msg, "질문3")
        assertContains(msg, "질문5")
        assertContains(msg, "답변5")
    }

    @Test
    fun `history 답변은 600자에서 잘리고 생략 표시가 붙는다`() {
        val longAnswer = "가".repeat(700)
        val msg = AnalysisService.renderAskUserMessage(facts, listOf(AskTurn("Q", longAnswer)), "그럼?")
        assertContains(msg, "…(생략)")
        assertFalse(msg.contains("가".repeat(601)), "600자 초과분은 잘려야 함")
    }

    @Test
    fun `모드별 프롬프트 스탠스 분기`() {
        val defensive = AnalysisService.askPrompt(AnalysisMode.DEFENSIVE)
        val aggressive = AnalysisService.askPrompt(AnalysisMode.AGGRESSIVE)
        assertContains(defensive, "스탠스(방어 모드)")
        assertFalse(defensive.contains("스탠스(공격 모드"))
        assertContains(aggressive, "스탠스(공격 모드")
        // 공통: 환각 가드(말미 재강조)와 Q&A 규칙은 두 모드 모두 포함
        assertContains(defensive, "마지막 경고")
        assertContains(aggressive, "마지막 경고")
        assertContains(aggressive, "Q1.")
    }
}
