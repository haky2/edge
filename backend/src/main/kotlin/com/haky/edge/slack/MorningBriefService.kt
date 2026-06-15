package com.haky.edge.slack

import com.haky.edge.macro.EventSyncService
import com.haky.edge.macro.MarketMoodLogService
import com.haky.edge.macro.MarketMoodService
import java.time.LocalDate
import java.time.ZoneId

/**
 * 매일 아침 08:50 KST — #아침브리핑 채널에 시장 방향 브리핑을 보낸다.
 * 모든 데이터는 기존 서비스에서 재사용(새 API 호출 없음):
 *   - MarketMoodService : 코스피 방향 Claude 코멘트 (캐시 있으면 즉시, 없으면 생성)
 *   - MarketMoodLogService : 오늘 방향 예측(BULLISH/BEARISH/NEUTRAL) + 최근 적중률
 *   - EventSyncService : 임박 이벤트 D-day 목록 (7일 이내)
 */
class MorningBriefService(
    private val slack: SlackClient,
    private val briefingChannel: String,
    private val marketMood: MarketMoodService,
    private val moodLog: MarketMoodLogService,
    private val eventSync: EventSyncService,
) {
    suspend fun send() {
        if (!slack.isConfigured || briefingChannel.isBlank()) return

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        // 1. 코스피 방향 예측(선행신호 가중합 계산, Claude 호출 없음)
        val direction = moodLog.inferDirection(
            // MarketMoodService가 캐시하는 지표 목록을 직접 가져오는 대신,
            // MoodLog에 오늘 예측이 이미 들어 있으면 그걸 쓴다(prewarm이 08:45에 채워둠).
            emptyList() // 아래서 moodLog 직접 조회
        ).let {
            // prewarm이 이미 addOrUpdateEntry 했을 가능성이 높으니 로그에서 읽는다.
            val report = moodLog.getAccuracyReport()
            report.recentEntries.firstOrNull { it.date == today.toString() }?.direction ?: it
        }

        // 2. 시장 분위기 Claude 코멘트 (캐시 있으면 0ms, 없으면 생성)
        val mood = runCatching { marketMood.get() }.getOrNull()

        // 3. 임박 이벤트(7일 이내)
        val upcoming = eventSync.getUpcoming(7)

        // ── 메시지 조립 ──────────────────────────────────────────────────
        val text = buildString {
            // 헤더
            val directionEmoji = when (direction) {
                "BULLISH"  -> "📈"
                "BEARISH"  -> "📉"
                else       -> "➡️"
            }
            val directionLabel = when (direction) {
                "BULLISH"  -> "상승 우위"
                "BEARISH"  -> "하락 우위"
                else       -> "중립"
            }
            appendLine("$directionEmoji *${today.monthValue}/${today.dayOfMonth} 아침 브리핑 — 코스피 선행신호: $directionLabel*")
            appendLine()

            // 시장 분위기 코멘트 (첫 단락만, 너무 길면 잘라냄)
            if (mood != null) {
                val firstPara = mood.comment
                    .split("\n\n").firstOrNull()?.trim()
                    ?.replace(Regex("^#+\\s*"), "")  // 마크다운 헤더 제거
                    ?.take(300)
                if (!firstPara.isNullOrBlank()) {
                    appendLine(firstPara)
                    appendLine()
                }
            }

            // 임박 이벤트
            if (upcoming.isNotEmpty()) {
                appendLine("*📅 이번 주 주요 이벤트*")
                upcoming.take(5).forEach { e ->
                    val dday = runCatching {
                        java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(e.date)).toInt()
                    }.getOrNull()
                    val ddayStr = when {
                        dday == null -> ""
                        dday <= 0    -> " · *D-day*"
                        dday == 1    -> " · D-1"
                        else         -> " · D-$dday"
                    }
                    val catEmoji = when (e.category) {
                        "호재" -> "🟢"
                        "주의" -> "🔴"
                        else   -> "⚪"
                    }
                    appendLine("$catEmoji ${e.date}$ddayStr ${e.title}")
                }
            }
        }.trim()

        slack.postMessage(briefingChannel, text)
    }
}
