package com.haky.edge.slack

import com.haky.edge.ai.ClaudeUsageTracker
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 매일 저녁 21:00 KST — #ops-배포-비용 채널에 당일 Claude 사용량 요약을 발송한다.
 * Sonnet 4.6 공식 가격 기준 추정 비용 포함. 요청이 없는 날(0건)은 발송 안 함.
 *
 * 가격 (Sonnet 4.6, 2025년 기준):
 *   input $3.00/MTok · output $15.00/MTok · cache_creation $3.75/MTok · cache_read $0.30/MTok
 */
class CostSummaryService(
    private val slack: SlackClient,
    private val channel: String,
    private val usageTracker: ClaudeUsageTracker,
) {
    suspend fun send() {
        if (!slack.isConfigured || channel.isBlank()) return

        val usage = withContext(Dispatchers.IO) { usageTracker.readToday() }
        if (usage.requests == 0) return

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val costUsd = (usage.inputTokens    * 3.00 +
                       usage.outputTokens   * 15.00 +
                       usage.cacheCreatedTokens * 3.75 +
                       usage.cacheReadTokens    * 0.30) / 1_000_000.0
        val costKrw = (costUsd * 1_400).toLong()

        val totalTokens = usage.inputTokens + usage.cacheCreatedTokens + usage.cacheReadTokens
        val cacheHitPct = if (totalTokens > 0) usage.cacheReadTokens * 100.0 / totalTokens else 0.0
        val text = buildString {
            appendLine("💰 *Claude 일일 사용량 — ${today.monthValue}/${today.dayOfMonth}*")
            appendLine()
            appendLine("요청 *${usage.requests}건*")
            if (usage.webSearches > 0)
                appendLine("웹검색 *${usage.webSearches}건* (별도 과금)")
            appendLine("입력 ${"%,d".format(usage.inputTokens)} tok")
            appendLine("출력 ${"%,d".format(usage.outputTokens)} tok")
            if (usage.cacheCreatedTokens > 0)
                appendLine("캐시 생성 ${"%,d".format(usage.cacheCreatedTokens)} tok")
            if (usage.cacheReadTokens > 0)
                appendLine("캐시 읽기 ${"%,d".format(usage.cacheReadTokens)} tok (적중률 ${"%.0f".format(cacheHitPct)}%)")
            appendLine()
            appendLine("추정 비용 *\$${String.format("%.4f", costUsd)}* (~${"%,d".format(costKrw)}원)")
        }.trim()

        slack.postMessage(channel, text)
    }
}
