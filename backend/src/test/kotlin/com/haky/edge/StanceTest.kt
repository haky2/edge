package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.StanceEntry
import com.haky.edge.ai.StanceLog
import com.haky.edge.ai.StanceStatsService
import com.haky.edge.kis.DailyBar
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F6 슬라이스 6a(태그 파싱·로그)+6b(채점). */
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

    @Test
    fun `latestBefore - 같은 모드 직전 유효 스탠스(미상 제외, 당일 제외)`(): Unit = runBlocking {
        val dir = tempDir()
        val log = StanceLog(dir.absolutePath)
        log.append(StanceEntry("005930", "2026-07-07", "defensive", "부정", 80_000.0, "09:00", null, "요약A"))
        log.append(StanceEntry("005930", "2026-07-08", "defensive", "미상", 81_000.0, "09:00"))       // 미상 → 스킵
        log.append(StanceEntry("005930", "2026-07-09", "aggressive", "긍정", 82_000.0, "09:00"))      // 다른 모드 → 스킵
        log.append(StanceEntry("000660", "2026-07-10", "defensive", "긍정", 300_000.0, "09:00"))      // 다른 종목 → 스킵
        log.append(StanceEntry("005930", "2026-07-11", "defensive", "긍정", 84_000.0, "09:00"))       // 당일 → 제외

        val prev = log.latestBefore("005930", "defensive", "2026-07-11")
        assertEquals("2026-07-07", prev?.date)
        assertEquals("부정", prev?.stance)
        assertEquals("요약A", prev?.summary)
        assertNull(log.latestBefore("005930", "defensive", "2026-07-07")) // 그 이전 기록 없음
        dir.deleteRecursively()
    }

    @Test
    fun `summary 없는 기존 jsonl 줄도 읽힌다(하위호환)`(): Unit = runBlocking {
        val dir = tempDir()
        // summary 필드가 없던 시절의 원본 줄을 직접 기록
        File(dir, "stance_log.jsonl").writeText(
            """{"code":"005930","date":"2026-07-01","mode":"defensive","stance":"긍정","priceAtGen":83000.0,"generatedAt":"10:00"}""" + "\n"
        )
        val log = StanceLog(dir.absolutePath)
        val all = log.readAll()
        assertEquals(1, all.size)
        assertNull(all[0].summary)
        assertEquals("긍정", log.latestBefore("005930", "defensive", "2026-07-02")?.stance)
        dir.deleteRecursively()
    }

    // ── extractRegime ──────────────────────────────────────────────────

    @Test
    fun `facts에서 레짐 라벨 추출`() {
        val facts = "…\n국면 판정(계산): 리레이팅 국면(과거 밴드 기준 무력화 가능성) — 근거: 목표가 상향; YoY 급증\n…"
        assertEquals("리레이팅 국면", AnalysisService.extractRegime(facts))
        assertEquals("디레이팅 경계", AnalysisService.extractRegime("국면 판정(계산): 디레이팅 경계(밸류 함정 가능성) — 근거: x"))
        assertNull(AnalysisService.extractRegime("국면 판정 없음"))
    }

    // ── 6b: StanceStatsService.score ───────────────────────────────────

    /** i일차(1부터) 종가 close인 오름차순 30봉 이력(최신이 앞으로 뒤집어 반환). */
    private fun history(closes: List<Long>): List<DailyBar> =
        closes.mapIndexed { i, c ->
            DailyBar(date = "%08d".format(20260101 + i), open = c, high = c, low = c, close = c, volume = 100)
        }.reversed()

    private fun entry(stance: String, code: String = "005930", date: String = "2026-01-01",
                      mode: String = "defensive", price: Double = 100.0, regime: String? = null) =
        StanceEntry(code, date, mode, stance, price, "09:00", regime)

    @Test
    fun `채점 - 긍정은 상승, 부정은 하락, 중립은 밴드 내`() {
        // 기준봉 idx0(=20260101), exit idx20 종가로 채점. priceAtGen=100.
        val up = history(List(21) { if (it == 20) 110L else 100L })    // +10%
        val down = history(List(21) { if (it == 20) 90L else 100L })   // -10%
        val flat = history(List(21) { if (it == 20) 102L else 100L })  // +2% (밴드 3% 내)
        val stats = StanceStatsService.score(
            listOf(
                entry("긍정", code = "AAAAA1"), entry("부정", code = "BBBBB2"),
                entry("중립", code = "CCCCC3"), entry("긍정", code = "BBBBB2", mode = "aggressive"),
            ),
            mapOf("AAAAA1" to up, "BBBBB2" to down, "CCCCC3" to flat),
            "2026-02-10",
        )
        assertEquals(4, stats.scored)
        assertEquals(3, stats.overall!!.correct) // 긍정↑=O, 부정↓=O, 중립2%=O, 공격긍정(하락)=X
        assertEquals(75.0, stats.overall!!.accuracyPct)
        assertEquals(2, stats.byMode.size)
        assertEquals(50.0, stats.byStance.first { it.label == "긍정" }.accuracyPct)
    }

    @Test
    fun `20거래일 미경과는 pending, 미상은 unknown`() {
        val short = history(List(10) { 100L }) // 10봉뿐 → exit 미도달
        val stats = StanceStatsService.score(
            listOf(entry("긍정"), entry("미상", code = "000660")),
            mapOf("005930" to short, "000660" to short),
            "2026-01-15",
        )
        assertEquals(0, stats.scored)
        assertEquals(1, stats.pending)
        assertEquals(1, stats.unknown)
        assertNull(stats.overall)
    }

    @Test
    fun `같은 code-date-mode 중복 생성은 마지막만 채점`() {
        val up = history(List(21) { if (it == 20) 110L else 100L })
        val stats = StanceStatsService.score(
            listOf(entry("부정"), entry("긍정")), // 같은 키 — 마지막(긍정) 채택
            mapOf("005930" to up),
            "2026-02-10",
        )
        assertEquals(1, stats.scored)
        assertEquals(1, stats.overall!!.correct)
    }

    @Test
    fun `주말 생성은 다음 거래일 기준봉으로 채점`() {
        // 이력은 20260102부터 — 20260101 생성건의 기준봉이 다음 봉(20260102)으로 잡혀야 함
        val bars = (0 until 22).map { i ->
            DailyBar(date = "%08d".format(20260102 + i), open = 100, high = 100, low = 100,
                close = if (i == 20) 110L else 100L, volume = 100)
        }.reversed()
        val stats = StanceStatsService.score(listOf(entry("긍정")), mapOf("005930" to bars), "2026-02-10")
        assertEquals(1, stats.scored)
        assertEquals(1, stats.overall!!.correct)
    }

    @Test
    fun `레짐별 집계`() {
        val up = history(List(21) { if (it == 20) 110L else 100L })
        val stats = StanceStatsService.score(
            listOf(
                entry("긍정", code = "AAAAA1", regime = "리레이팅 국면"),
                entry("부정", code = "BBBBB2", regime = "리레이팅 국면"),
            ),
            mapOf("AAAAA1" to up, "BBBBB2" to up),
            "2026-02-10",
        )
        assertEquals(1, stats.byRegime.size)
        val r = stats.byRegime.first()
        assertEquals("리레이팅 국면", r.label)
        assertEquals(2, r.n)
        assertEquals(1, r.correct) // 긍정↑=O, 부정↑=X
        assertEquals(50.0, r.accuracyPct)
    }
}
