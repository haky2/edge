package com.haky.edge.slack

/**
 * Slack 발송 텍스트 정규화. **모든 Slack 발송은 SlackClient에서 이 함수를 거친다** —
 * 새 발송 지점을 추가해도 SlackClient를 쓰는 한 자동 적용된다.
 *
 * 마크다운 볼드 처리(Claude 코멘트는 `**굵게**`, Slack mrkdwn은 `*굵게*`):
 *  - Slack은 닫는 `*` **뒤에 공백·줄바꿈·문장부호**가 와야 볼드로 인식한다. 여는 `*`도 앞이 그래야 한다.
 *    그래서 `**최근 흐름**\n`(뒤 줄바꿈)·`**+4.88%** 급등`(뒤 공백)은 정상 볼드 렌더된다.
 *    하지만 한국어 조사가 바로 붙는 `**338,250원**에`·`**+12.7%**의` 는 페어링이 깨져 별표가 노출된다.
 *  - 괄호류도 예외다: 닫는 `*` 바로 뒤에 여는 괄호(`**9,650원**(-2.28%)` → `*9,650원*(`)가 오면
 *    Slack은 볼드로 렌더하지 않고 별표를 노출한다. 여는 `*` 바로 앞의 닫는 괄호도 마찬가지.
 *    → 이 경계는 조사처럼 마커를 떼어 평문으로 만든다.
 *  - 따라서 **경계가 안전하면 `*굵게*`로 변환, 깨지는 경우만 마커를 떼고 평문**으로 만든다.
 *    → 정상 작동하던 부제목·공백 뒤 볼드는 유지하고, 조사 붙은 깨진 볼드만 정리한다.
 *  - 우리가 직접 쓰는 헤더(예: `*종목명*`)는 별 1개라 이 변환 대상이 아니고 그대로 렌더된다.
 */
object SlackFormat {
    private val headerRegex = Regex("(?m)^#{1,6}\\s*")
    private val boldRegex = Regex("""\*\*(.+?)\*\*""")  // **굵게** 한 쌍 (비탐욕, 한 줄 내)
    private val OPENING_BRACKETS = setOf('(', '[', '{', '<', '（', '［', '｛', '〈', '《', '「', '『')
    private val CLOSING_BRACKETS = setOf(')', ']', '}', '>', '）', '］', '｝', '〉', '》', '」', '』')

    /** 마크다운 헤더(#) 제거 + 볼드(**굵게**) → Slack mrkdwn(경계 안전 시 *굵게*, 아니면 평문). */
    fun sanitize(text: String): String = convertBold(text.replace(headerRegex, ""))

    private fun convertBold(text: String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in boldRegex.findAll(text)) {
            sb.append(text, last, m.range.first)
            val inner = m.groupValues[1]
            val before = m.range.first.takeIf { it > 0 }?.let { text[it - 1] }
            val afterIdx = m.range.last + 1
            val after = afterIdx.takeIf { it < text.length }?.let { text[it] }
            // Slack 볼드는 여는 별표 앞/닫는 별표 뒤가 비단어(공백·문장부호·경계)여야 인식된다.
            // 단 괄호류는 문장부호여도 페어링을 깨므로(닫는 `*` 뒤 여는 괄호, 여는 `*` 앞 닫는 괄호) 제외한다.
            val safeOpen = before == null || (!before.isLetterOrDigit() && before !in CLOSING_BRACKETS)
            val safeClose = after == null || (!after.isLetterOrDigit() && after !in OPENING_BRACKETS)
            if (safeOpen && safeClose) sb.append('*').append(inner).append('*')
            else sb.append(inner)  // 한글 조사 등으로 깨질 경계 → 마커 제거(평문)
            last = afterIdx
        }
        sb.append(text, last, text.length)
        // 잘림 등으로 남은 짝 없는 `**` 제거(예: 길이 컷에 걸려 닫는 쌍이 사라진 경우).
        return sb.toString().replace("**", "")
    }
}
