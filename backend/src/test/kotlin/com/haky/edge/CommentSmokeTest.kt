package com.haky.edge

import com.haky.edge.ai.CommentSmokeService
import com.haky.edge.slack.SlackClient
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R4 금지 패턴 스모크 — 패턴별 양성/음성 + JSON 텍스트 추출 + 디렉토리 스캔. */
class CommentSmokeTest {

    private fun patterns(text: String): List<String> =
        CommentSmokeService.checkText(text).map { it.first }

    // ── 1. 이평 지지/저항 단정 ────────────────────────────────────────────

    @Test
    fun `이평 양성 - MA20 지지선, 이동평균 저항`(): Unit {
        assertTrue("이평 지지/저항 단정" in patterns("MA20이 지지선으로 작용하고 있다."))
        assertTrue("이평 지지/저항 단정" in patterns("이동평균 부근에서 저항을 받는 모습."))
    }

    @Test
    fun `이평 음성 - 위치 서술·회복 표현`(): Unit {
        assertTrue("이평 지지/저항 단정" !in patterns("현재가는 MA20 위에 있다."))
        assertTrue("이평 지지/저항 단정" !in patterns("이평선을 회복하며 추세가 개선됐다."))
    }

    // ── 2. 규칙 번호 누출 ─────────────────────────────────────────────────

    @Test
    fun `규칙 누출 양성 - 조항 참조·단독 괄호`(): Unit {
        assertTrue("규칙 번호 누출" in patterns("C14 조항에 따라 이평 단정을 피한다."))
        assertTrue("규칙 번호 누출" in patterns("장기 관점(C17)으로 본다."))   // 계좌 슬라이스 실사고 형태
        assertTrue("규칙 번호 누출" in patterns("규칙 9)를 지켜 소표본을 과신하지 않는다."))
    }

    @Test
    fun `규칙 누출 음성 - 무관한 영숫자 조합`(): Unit {
        assertTrue("규칙 번호 누출" !in patterns("C-130 수송기 수출 계약이 체결됐다."))
        assertTrue("규칙 번호 누출" !in patterns("비타민C 20mg과는 무관한 문장."))
    }

    // ── 3. 소표본 과신 ────────────────────────────────────────────────────

    @Test
    fun `소표본 양성 - 같은 문장에 n과 과신 표현`(): Unit {
        assertTrue("소표본 과신" in patterns("n=8이지만 뚜렷한 상승 신호다."))
        assertTrue("소표본 과신" in patterns("표본 n=12로 유의미한 차이가 확인된다."))
    }

    @Test
    fun `소표본 음성 - 다른 문장 분리·n 15이상`(): Unit {
        assertTrue("소표본 과신" !in patterns("n=8이라 참고 수준이다. 거시 흐름은 뚜렷하다."))  // 문장 분리
        assertTrue("소표본 과신" !in patterns("n=20으로 뚜렷한 경향이 보인다."))               // n≥15
        assertTrue("소표본 과신" !in patterns("n=140일 관측에서 뚜렷한 추세."))                // 140은 1~14 아님
    }

    // ── 4. 면책 고지 중복 ─────────────────────────────────────────────────

    @Test
    fun `면책 양성 - 투자 판단·참고용입니다`(): Unit {
        assertTrue("면책 고지 중복" in patterns("최종 투자 판단은 본인 책임입니다."))
        assertTrue("면책 고지 중복" in patterns("이 분석은 참고용입니다."))
    }

    @Test
    fun `면책 음성 - 유사 표현`(): Unit {
        assertTrue("면책 고지 중복" !in patterns("판단 재료를 정리하면 다음과 같다."))
        assertTrue("면책 고지 중복" !in patterns("과거 통계를 참고하면 반등 확률이 높았다."))
    }

    // ── JSON 텍스트 추출 ──────────────────────────────────────────────────

    @Test
    fun `comment·summary만 추출 - caveat 등 고정 문구 제외`(): Unit {
        val el = Json.parseToJsonElement(
            """{"comment":"본문","summary":"요약","caveat":"참고용입니다","nested":{"comment":"중첩 본문"},"list":[{"summary":"배열 요약"}]}"""
        )
        val texts = CommentSmokeService.extractTextFields(el)
        assertEquals(setOf("본문", "요약", "중첩 본문", "배열 요약"), texts.toSet())
        // caveat의 "참고용입니다"는 추출되지 않아 면책 패턴이 오탐하지 않는다.
    }

    // ── 디렉토리 스캔 e2e ─────────────────────────────────────────────────

    @Test
    fun `당일 파일만 스캔하고 발견을 보고한다`(): Unit {
        val dir = kotlin.io.path.createTempDirectory("smoke-test").toFile()
        try {
            val analysisDir = File(dir, "analysis").also { it.mkdirs() }
            // 당일 파일(위반 포함) — 파일명에 날짜.
            File(analysisDir, "2026-07-20_005930.json")
                .writeText("""{"comment":"MA20이 지지선으로 작용 중이다.","summary":"정상 요약"}""")
            // 전일 파일(위반 있어도 스캔 제외).
            File(analysisDir, "2026-07-19_000660.json")
                .writeText("""{"comment":"이동평균이 저항으로 작용."}""")
            // 검사 대상 아닌 prefix 디렉토리는 무시.
            File(dir, "investor").also { it.mkdirs() }
                .resolve("2026-07-20_x.json").writeText("""{"comment":"이평 지지"}""")

            val svc = CommentSmokeService(SlackClient(""), "", cacheDir = dir.absolutePath)
            val report = svc.scan(date = "2026-07-20")
            assertEquals(1, report.scannedFiles)
            assertEquals(1, report.findings.size)
            assertEquals("이평 지지/저항 단정", report.findings[0].pattern)
            assertEquals("analysis", report.findings[0].cache)
            assertTrue("2026-07-20_005930" in report.findings[0].file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `위반 없으면 발견 0건`(): Unit {
        val dir = kotlin.io.path.createTempDirectory("smoke-clean").toFile()
        try {
            File(dir, "analysis").also { it.mkdirs() }
                .resolve("2026-07-20_005930.json")
                .writeText("""{"comment":"현재가는 MA20 위에 있고 수급이 양호하다.","summary":"수급 양호"}""")
            val svc = CommentSmokeService(SlackClient(""), "", cacheDir = dir.absolutePath)
            val report = svc.scan(date = "2026-07-20")
            assertEquals(1, report.scannedFiles)
            assertEquals(0, report.findings.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
