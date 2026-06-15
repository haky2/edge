package com.haky.edge.slack

import com.haky.edge.ai.AnalysisService
import com.haky.edge.master.StockMaster

/**
 * `/edge 종목명` 슬래시 명령 처리(S7 — 양방향 조회 기반).
 *
 * 흐름: 텍스트→종목코드 해석 → AnalysisService 코멘트 → Slack mrkdwn 요약 → response_url 로 발송.
 * 3초 ack 제약 때문에 라우트가 즉시 200을 주고, 이 process()는 **비동기**로 돌며 response_url 에 결과를 보낸다.
 *
 * 프라이버시(S7 원칙): 종목명으로 **공개 분석만** 조회 — 워치리스트·포지션 안 들어감.
 * position=null 로 분석해 "내 평단 기준" 개인화는 붙이지 않는다(라운지/채널에 사적 데이터 누출 방지).
 */
class SlackCommandService(
    private val analysis: AnalysisService,
    private val master: StockMaster,
    private val slack: SlackClient,
) {
    private val codeRegex = Regex("""\d{6}""")

    /** 비동기 본체: 해석→분석→response_url 발송. 절대 예외를 밖으로 던지지 않는다(호출부가 fire-and-forget). */
    suspend fun process(rawText: String, responseUrl: String) {
        val text = rawText.trim()
        if (text.isBlank()) {
            slack.postToResponseUrl(responseUrl, "사용법: `/edge 종목명` (예: `/edge 삼성전자`)")
            return
        }
        runCatching {
            val code = resolveCode(text)
            if (code == null) {
                slack.postToResponseUrl(responseUrl, "‘$text’ 종목을 못 찾았어요. 정확한 종목명이나 6자리 코드로 다시 시도해 주세요.")
                return
            }
            val result = analysis.analyze(code)  // position=null → 공개 분석(개인화 없음)
            slack.postToResponseUrl(responseUrl, format(result))
        }.onFailure { e ->
            System.err.println("[SlackCommand] process 실패(text=$text): ${e.message}")
            slack.postToResponseUrl(responseUrl, "‘$text’ 분석 중 오류가 났어요. 잠시 후 다시 시도해 주세요.")
        }
    }

    /** 6자리 숫자면 코드로, 아니면 종목마스터 검색 첫 결과의 코드. */
    private suspend fun resolveCode(text: String): String? {
        if (codeRegex.matches(text)) return text
        return runCatching { master.search(text, limit = 1) }.getOrNull()?.firstOrNull()?.code
    }

    /** Analysis → Slack mrkdwn. **굵게(이중별표)**를 Slack용 *단일별표*로 변환하고 길이를 자른다. */
    private fun format(a: com.haky.edge.ai.Analysis): String = buildString {
        val priceStr = a.generatedPrice?.let { " · %,d원".format(it.toLong()) } ?: ""
        appendLine("*${a.name}* (${a.code})$priceStr")
        appendLine()
        val body = a.comment
            .replace("**", "*")              // mrkdwn 굵게: ** → *
            .replace(Regex("(?m)^#+\\s*"), "") // 마크다운 헤더 기호 제거
            .trim()
        if (body.length > MAX_BODY) {
            append(body.take(MAX_BODY).trimEnd())
            append("…\n\n_(전체 코멘트는 앱에서 보세요)_")
        } else {
            append(body)
        }
        append("\n\n_${a.generatedAt.ifBlank { a.date }} 기준 · 참고용_")
    }

    companion object {
        private const val MAX_BODY = 1600
    }
}
