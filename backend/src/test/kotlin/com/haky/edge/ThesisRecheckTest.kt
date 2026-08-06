package com.haky.edge

import com.haky.edge.ai.ThesisRecheckService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 논지 재점검 판정 파싱·발화 조건 순수 함수. */
class ThesisRecheckTest {

    @Test fun `정상 JSON 파싱`() {
        val raw = """{"verdict": "약화", "changedFact": "3분기 순이익 YoY -40%", "reason": "논지의 축인 실적 성장이 꺾였다."}"""
        val v = ThesisRecheckService.parseVerdict(raw)
        assertNotNull(v)
        assertEquals("약화", v.verdict)
        assertTrue(v.changedFact.contains("순이익"))
        assertTrue(v.reason.contains("실적"))
    }

    @Test fun `코드펜스·서두 방어`() {
        val raw = "결과입니다.\n```json\n{\"verdict\": \"무효\", \"changedFact\": \"수주 취소\", \"reason\": \"핵심 전제 소멸.\"}\n```"
        val v = ThesisRecheckService.parseVerdict(raw)
        assertNotNull(v)
        assertEquals("무효", v.verdict)
    }

    @Test fun `미지 verdict·형식 불일치는 null`() {
        assertNull(ThesisRecheckService.parseVerdict("""{"verdict": "강함", "reason": "x"}"""))  // 사전 밖 값
        assertNull(ThesisRecheckService.parseVerdict("논지가 여전히 좋아 보입니다"))                 // JSON 아님
        assertNull(ThesisRecheckService.parseVerdict("{}"))                                        // verdict 없음
    }

    @Test fun `changedFact·reason 없어도 verdict만 유효하면 통과`() {
        val v = ThesisRecheckService.parseVerdict("""{"verdict": "유효"}""")
        assertNotNull(v)
        assertEquals("유효", v.verdict)
        assertEquals("", v.changedFact)
    }

    @Test fun `발화 대상은 약화·무효만`() {
        assertTrue(ThesisRecheckService.isPushWorthy("약화"))
        assertTrue(ThesisRecheckService.isPushWorthy("무효"))
        assertTrue(!ThesisRecheckService.isPushWorthy("유효"))
        assertTrue(!ThesisRecheckService.isPushWorthy("판단불가"))
    }
}
