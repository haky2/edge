package com.haky.edge.ai

import com.haky.edge.slack.SlackClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

// ── DTO ───────────────────────────────────────────────────────────────────

/** 금지 패턴 매칭 1건. file은 캐시 파일명(키에 종목코드·트리거가 들어 있어 그대로 식별자). */
@Serializable
data class SmokeFinding(
    val cache: String,    // 캐시 prefix(analysis, market_mood, …)
    val file: String,     // 파일명(확장자 제외)
    val pattern: String,  // 패턴 이름
    val excerpt: String,  // 매칭 문장 발췌
)

/** GET/POST /comment-smoke 응답. */
@Serializable
data class SmokeReport(
    val date: String,
    val scannedFiles: Int,
    val findings: List<SmokeFinding> = emptyList(),
    val numberGuardFired: Int,   // NumberGuard 발동 횟수(프로세스 기동 이후 — 신규 검사 아님, 기존 로그 카운트 병기)
    val posted: Boolean = false, // Slack 발송 여부(0건이면 침묵이라 false)
)

/**
 * 산출물 금지 패턴 스모크(R4) — 당일 캐시 코멘트를 정규식으로 주기 검사. LLM 0.
 *
 * 프롬프트 조항이 늘수록 위반 감시가 수동 감사에 의존한다 — 이 스모크가 방어선을 상설화한다.
 * 검사 대상: 코멘트를 담는 FileCache들의 **당일 키 파일**(키에 날짜가 들어가 파일명으로 필터).
 * 텍스트는 JSON에서 comment/summary 필드만 추출(코드가 만든 caveat 고정 문구는 검사 제외 —
 * 면책 패턴이 우리 고정 문구에 오탐하는 것 방지).
 *
 * 금지 패턴(정의 정본은 docs/reeval-backlog-spec-2026-07.md R4 — 조정 시 스펙에 갱신):
 *  1. 이평 지지/저항 단정(C14 위반) 2. 규칙 번호 누출(계좌 슬라이스 교훈)
 *  3. 소표본 과신 — 같은 문장에 n=1~14와 과신 표현 공존(C9 위반) 4. 면책 고지 중복(UI footer 존재)
 * NumberGuard는 신규 검사 대신 기존 발동 카운터(AnalysisService, 기동 이후)만 병기.
 *
 * 발송: 발견 시에만 #알림-운영오류로 발췌 리포트(0건이면 침묵). 주 1회 토 10:00 KST 잡 + 수동 GET.
 * 오탐 관찰 기간 2주(스펙) — 패턴 조정은 스펙 문서에 반영 후 여기 반영.
 */
class CommentSmokeService(
    private val slack: SlackClient,
    private val opsChannel: String,
    private val cacheDir: String = System.getenv("CACHE_DIR") ?: ".cache",
) {

    /** 당일 캐시 스캔(발송 없음 — 수동 GET용). */
    fun scan(date: String = effectiveMarketDate()): SmokeReport {
        val findings = mutableListOf<SmokeFinding>()
        var scanned = 0
        for (prefix in COMMENT_CACHES) {
            val dir = File(cacheDir, prefix)
            if (!dir.isDirectory) continue
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name.contains(date) }
                ?: continue
            for (f in files) {
                scanned++
                val texts = runCatching {
                    extractTextFields(parser.parseToJsonElement(f.readText()))
                }.getOrElse { emptyList() }
                for (text in texts) {
                    for ((patternName, excerpt) in checkText(text)) {
                        findings += SmokeFinding(prefix, f.name.removeSuffix(".json"), patternName, excerpt)
                    }
                }
            }
        }
        return SmokeReport(
            date = date,
            scannedFiles = scanned,
            findings = findings.take(MAX_FINDINGS),
            numberGuardFired = AnalysisService.numberGuardFired.get(),
        )
    }

    /** 스캔 + 발견 시에만 Slack 발송(0건 침묵) — 주 1회 잡용. */
    suspend fun scanAndReport(): SmokeReport {
        val report = scan()
        if (report.findings.isEmpty()) return report
        val posted = slack.postMessage(opsChannel, formatMessage(report))
        return report.copy(posted = posted)
    }

    companion object {
        private val parser = Json { ignoreUnknownKeys = true }

        /** 코멘트(comment/summary)를 담는 FileCache prefix들 — 캐시 신설 시 여기 추가. */
        val COMMENT_CACHES = listOf(
            "analysis", "overseas_analysis", "portfolio-review", "market_mood",
            "sector_briefing", "macro_impact", "comparison", "deep_research",
            "trade_review", "weekly-review-personal",
        )

        /** JSON에서 검사할 텍스트 필드 — LLM 산출물만(caveat 등 고정 문구 제외). */
        private val TEXT_FIELDS = setOf("comment", "summary")

        internal const val MAX_FINDINGS = 50   // 폭주 방어(잘리면 리포트에 count로 드러남)
        private const val MAX_SLACK_LINES = 20 // Slack 도배 방지
        private const val EXCERPT_LEN = 140

        // ── 금지 패턴(스펙 R4 정본의 정규식 그대로) ──────────────────────────
        private val MA_SUPPORT = Regex("""(MA\d+|이동평균|이평)[^.\n]{0,20}(지지|저항|버팀)""")
        private val RULE_LEAK = Regex("""\(?[CDQPW]-?\d{1,2}\)?\s*(조항|규칙)|규칙\s?\d+\)""")
        private val RULE_PAREN = Regex("""\((C1[0-9]|C20)\)""")   // 단독 괄호 표기(계좌 슬라이스 실사고 형태)
        private val SMALL_N = Regex("""n=([1-9]|1[0-4])\b""")
        private val OVERCONFIDENT = Regex("""의미 있|뚜렷|유의미|강한 신호""")
        private val DISCLAIMER = Regex("""투자 판단|투자의 책임|참고용입니다""")

        /** 텍스트 1건 검사(순수 함수) — (패턴 이름, 발췌) 목록. */
        internal fun checkText(text: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            MA_SUPPORT.find(text)?.let { out += "이평 지지/저항 단정" to excerpt(text, it.range) }
            (RULE_LEAK.find(text) ?: RULE_PAREN.find(text))?.let { out += "규칙 번호 누출" to excerpt(text, it.range) }
            // 소표본 과신은 "같은 문장" 공존 조건 — 문장 단위로 두 패턴을 함께 검사.
            for (sentence in splitSentences(text)) {
                if (SMALL_N.containsMatchIn(sentence) && OVERCONFIDENT.containsMatchIn(sentence)) {
                    out += "소표본 과신" to sentence.trim().take(EXCERPT_LEN)
                    break   // 텍스트당 1건이면 충분(리포트 목적)
                }
            }
            DISCLAIMER.find(text)?.let { out += "면책 고지 중복" to excerpt(text, it.range) }
            return out
        }

        /** JSON 트리에서 TEXT_FIELDS 이름의 문자열 값을 전부 수집(중첩 안전). */
        internal fun extractTextFields(el: JsonElement): List<String> = buildList {
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) ->
                        if (k in TEXT_FIELDS && v is JsonPrimitive && v.isString) add(v.content)
                        else walk(v)
                    }
                    is JsonArray -> e.forEach { walk(it) }
                    else -> {}
                }
            }
            walk(el)
        }

        /** 매칭 지점을 포함한 문장 발췌(앞뒤 문장 경계까지, 상한 EXCERPT_LEN). */
        internal fun excerpt(text: String, range: IntRange): String {
            val boundary = charArrayOf('.', '\n', '!', '?')
            val start = text.lastIndexOfAny(boundary, range.first).let { if (it < 0) 0 else it + 1 }
            val end = text.indexOfAny(boundary, range.last).let { if (it < 0) text.length else it + 1 }
            return text.substring(start, end).trim().take(EXCERPT_LEN)
        }

        private fun splitSentences(text: String): List<String> =
            text.split(Regex("""(?<=[.!?])\s+|\n"""))

        internal fun formatMessage(r: SmokeReport): String = buildString {
            appendLine("🧪 *코멘트 금지 패턴 스모크* (${r.date})")
            appendLine("검사 ${r.scannedFiles}파일 · 발견 *${r.findings.size}건* · NumberGuard 발동 ${r.numberGuardFired}건(기동 이후)")
            appendLine()
            r.findings.take(MAX_SLACK_LINES).forEach { f ->
                appendLine("• [${f.cache}/${f.file}] *${f.pattern}*")
                appendLine("  > ${f.excerpt}")
            }
            if (r.findings.size > MAX_SLACK_LINES) appendLine("… 외 ${r.findings.size - MAX_SLACK_LINES}건")
            append("_패턴 정본: docs/reeval-backlog-spec-2026-07.md R4 · 오탐이면 스펙 갱신 후 조정_")
        }
    }
}
