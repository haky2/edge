package com.haky.edge

import com.haky.edge.slack.SlackFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackFormatTest {

    // ── 헤더 제거 ──────────────────────────────────────────────────────────

    @Test fun `# 헤더 제거`() {
        assertEquals("제목", SlackFormat.sanitize("# 제목"))
        assertEquals("제목", SlackFormat.sanitize("## 제목"))
        assertEquals("제목", SlackFormat.sanitize("### 제목"))
    }

    @Test fun `헤더가 없으면 그대로`() {
        val text = "일반 텍스트"
        assertEquals(text, SlackFormat.sanitize(text))
    }

    // ── 볼드 변환: 안전한 경계 ──────────────────────────────────────────────

    @Test fun `볼드 앞뒤 공백 → Slack 볼드`() {
        assertEquals("*삼성전자* 상승", SlackFormat.sanitize("**삼성전자** 상승"))
    }

    @Test fun `볼드 문자열 맨 앞 → Slack 볼드`() {
        assertEquals("*삼성전자* 상승", SlackFormat.sanitize("**삼성전자** 상승"))
    }

    @Test fun `볼드 문자열 맨 끝 → Slack 볼드`() {
        // 닫는 ** 뒤가 문자열 끝(null) → 안전
        assertEquals("상승 *삼성전자*", SlackFormat.sanitize("상승 **삼성전자**"))
    }

    @Test fun `줄바꿈 뒤 볼드 → Slack 볼드`() {
        val input = "요약\n**핵심 포인트** 내용"
        val expected = "요약\n*핵심 포인트* 내용"
        assertEquals(expected, SlackFormat.sanitize(input))
    }

    @Test fun `볼드 뒤 줄바꿈 → Slack 볼드`() {
        val input = "**수급 동향**\n외인 순매수"
        val expected = "*수급 동향*\n외인 순매수"
        assertEquals(expected, SlackFormat.sanitize(input))
    }

    @Test fun `볼드 뒤 마침표 → Slack 볼드`() {
        assertEquals("*강세*.", SlackFormat.sanitize("**강세**."))
    }

    // ── 볼드 변환: 한국어 조사로 깨지는 경계 → 평문 ──────────────────────────

    @Test fun `볼드 뒤 한국어 조사 에 → 평문`() {
        // **338,250원**에 → 338,250원에 (별표 노출 방지)
        assertEquals("338,250원에", SlackFormat.sanitize("**338,250원**에"))
    }

    @Test fun `볼드 뒤 한국어 조사 의 → 평문`() {
        assertEquals("+12.7%의", SlackFormat.sanitize("**+12.7%**의"))
    }

    @Test fun `볼드 뒤 한국어 조사 로 → 평문`() {
        assertEquals("강세로", SlackFormat.sanitize("**강세**로"))
    }

    @Test fun `볼드 뒤 한국어 조사 은 → 평문`() {
        assertEquals("주가는", SlackFormat.sanitize("**주가**는"))
    }

    // ── 짝 없는 ** 정리 ────────────────────────────────────────────────────

    @Test fun `짝 없는 ** 제거`() {
        // 잘린 텍스트에 닫는 ** 없는 경우
        assertEquals("내용 시작", SlackFormat.sanitize("내용 시작**"))
    }

    @Test fun `빈 문자열 통과`() {
        assertEquals("", SlackFormat.sanitize(""))
    }

    // ── 헤더 + 볼드 복합 ────────────────────────────────────────────────────

    @Test fun `헤더 제거 후 볼드 변환`() {
        val input = "### 핵심 포인트\n**외인 순매수** 지속"
        val expected = "핵심 포인트\n*외인 순매수* 지속"
        assertEquals(expected, SlackFormat.sanitize(input))
    }

    @Test fun `여러 볼드 혼합`() {
        // 앞뒤 공백 있는 건 변환, 조사 붙은 건 평문
        val input = "**강세** 전환. **주가**는 반등"
        val expected = "*강세* 전환. 주가는 반등"
        assertEquals(expected, SlackFormat.sanitize(input))
    }
}
