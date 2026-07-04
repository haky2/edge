package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.StanceEntry
import com.haky.edge.ai.StanceLog
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F6 슬라이스 6a — 스탠스 태그 파싱 + jsonl 로그. */
class StanceTest {

    private val body = "### 핵심 요약\n요약 문장.\n\n**최근 흐름**\n본문 문단."

    // ── parseStanceTag ─────────────────────────────────────────────────

    @Test
    fun `말미 태그 3종 파싱 + 본문에서 제거`() {
        for (s in listOf("긍정", "중립", "부정")) {
            val (stance, cleaned) = AnalysisService.parseStanceTag("$body\n\n[스탠스: $s]")
            assertEquals(s, stance)
            assertEquals(body, cleaned)
            assertFalse(cleaned.contains("스탠스"))
        }
    }

    @Test
    fun `태그 없으면 미상 + 본문 그대로`() {
        val (stance, cleaned) = AnalysisService.parseStanceTag(body)
        assertEquals("미상", stance)
        assertEquals(body, cleaned)
    }

    @Test
    fun `공백·전각 콜론 변형 허용`() {
        val (stance, cleaned) = AnalysisService.parseStanceTag("$body\n[ 스탠스 ： 긍정 ]  ")
        assertEquals("긍정", stance)
        assertEquals(body, cleaned)
    }

    @Test
    fun `허용 외 값은 미상`() {
        val (stance, _) = AnalysisService.parseStanceTag("$body\n[스탠스: 강력매수]")
        assertEquals("미상", stance)
    }

    @Test
    fun `중복 태그는 마지막 채택 + 전부 제거`() {
        val raw = "[스탠스: 긍정]\n$body\n[스탠스: 부정]"
        val (stance, cleaned) = AnalysisService.parseStanceTag(raw)
        assertEquals("부정", stance)
        assertFalse(cleaned.contains("스탠스"))
        assertTrue(cleaned.contains("핵심 요약"))
    }

    @Test
    fun `줄 중간 인라인 언급은 태그로 안 침`() {
        val raw = "$body 참고로 [스탠스: 긍정] 같은 표기는 인라인."
        val (stance, cleaned) = AnalysisService.parseStanceTag(raw)
        assertEquals("미상", stance)
        assertEquals(raw, cleaned)
    }

    // ── StanceLog ──────────────────────────────────────────────────────

    private fun tempDir(): File = File.createTempFile("stance-log", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `append-readAll 왕복 + 손상 줄 스킵`(): Unit = runBlocking {
        val dir = tempDir()
        val log = StanceLog(dir.absolutePath)
        log.append(StanceEntry("005930", "2026-07-04", "defensive", "긍정", 84_000.0, "15:00"))
        File(dir, "stance_log.jsonl").appendText("{{{bad\n")
        log.append(StanceEntry("000660", "2026-07-04", "aggressive", "미상", 291_000.0, "15:01"))

        val all = log.readAll()
        assertEquals(2, all.size)
        assertEquals("긍정", all[0].stance)
        assertEquals("aggressive", all[1].mode)
        assertEquals(291_000.0, all[1].priceAtGen)
        dir.deleteRecursively()
    }
}
