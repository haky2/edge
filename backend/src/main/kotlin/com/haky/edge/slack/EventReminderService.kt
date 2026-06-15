package com.haky.edge.slack

import com.haky.edge.macro.EventSyncService
import java.time.LocalDate
import java.time.ZoneId

/**
 * 매일 아침 08:48 KST — #이벤트 채널에 당일(D-day)·전날(D-1) 임박 이벤트를 발송한다.
 * 이벤트가 없으면 조용히 종료(빈 메시지 발송 없음).
 * EventSyncService.getUpcoming(1)로 오늘~내일 이벤트만 가져온다(새 API 0).
 */
class EventReminderService(
    private val slack: SlackClient,
    private val eventChannel: String,
    private val eventSync: EventSyncService,
) {
    suspend fun send() {
        if (!slack.isConfigured || eventChannel.isBlank()) return

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val tomorrow = today.plusDays(1)

        // getUpcoming(1) = today ~ today+1일 → D-0과 D-1 이벤트만 포함됨
        val events = eventSync.getUpcoming(1)
        if (events.isEmpty()) return

        val todayEvents = events.filter {
            runCatching { LocalDate.parse(it.date) == today }.getOrDefault(false)
        }
        val tomorrowEvents = events.filter {
            runCatching { LocalDate.parse(it.date) == tomorrow }.getOrDefault(false)
        }

        val text = buildString {
            appendLine("📅 *이벤트 리마인더 — ${today.monthValue}/${today.dayOfMonth}(${korDow(today)})*")
            appendLine()

            if (todayEvents.isNotEmpty()) {
                appendLine("*오늘 D-day*")
                todayEvents.forEach { e ->
                    appendLine("${catEmoji(e.category)} ${e.title}")
                    if (e.impact.isNotBlank()) appendLine("  _${e.impact}_")
                }
                if (tomorrowEvents.isNotEmpty()) appendLine()
            }

            if (tomorrowEvents.isNotEmpty()) {
                appendLine("*내일 D-1*")
                tomorrowEvents.forEach { e ->
                    appendLine("${catEmoji(e.category)} ${e.title}")
                }
            }
        }.trim()

        slack.postMessage(eventChannel, text)
    }

    private fun catEmoji(category: String) = when (category) {
        "호재" -> "🟢"
        "주의" -> "🔴"
        else   -> "⚪"
    }

    private fun korDow(date: LocalDate) = when (date.dayOfWeek) {
        java.time.DayOfWeek.MONDAY    -> "월"
        java.time.DayOfWeek.TUESDAY   -> "화"
        java.time.DayOfWeek.WEDNESDAY -> "수"
        java.time.DayOfWeek.THURSDAY  -> "목"
        java.time.DayOfWeek.FRIDAY    -> "금"
        java.time.DayOfWeek.SATURDAY  -> "토"
        else                           -> "일"
    }
}
