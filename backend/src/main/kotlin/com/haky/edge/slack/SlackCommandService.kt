package com.haky.edge.slack

import com.haky.edge.ai.Analysis
import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.Comparison
import com.haky.edge.ai.ComparisonService
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.macro.EventSyncService
import com.haky.edge.macro.MarketEvent
import com.haky.edge.macro.MarketMood
import com.haky.edge.macro.MarketMoodService
import com.haky.edge.master.StockMaster
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Slack 슬래시 명령 처리(S7 기반, S8 라운지 명령어 추가).
 *
 * 지원 명령어:
 *   /edge 종목명        → AI 분석 코멘트 (S7 기존)
 *   /edge 시황          → 오늘 시장 분위기 코멘트
 *   /edge 이벤트        → 거시 이벤트 캘린더 (30일)
 *   /edge 비교 A B      → 두 종목 나란히 비교
 *   /edge 신호 종목명   → 외인/기관 수급 신호 확인
 *
 * 모든 응답은 ephemeral(나만 보임) + [채널에 공유] Block Kit 버튼. 버튼 클릭 →
 * handleInteraction() → 동일 내용을 in_channel 로 재게시 + "OO님이 공유했어요" 헤더.
 *
 * 프라이버시 원칙: 종목 조회는 공개 분석(position=null)만. 워치리스트·포지션은 DM 전용.
 */
class SlackCommandService(
    private val analysis: AnalysisService,
    private val master: StockMaster,
    private val slack: SlackClient,
    private val kis: KisClient,
    private val marketMood: MarketMoodService,
    private val eventSync: EventSyncService,
    private val comparison: ComparisonService,
) {
    private val codeRegex = Regex("""\d{6}""")

    /** 슬래시 명령 진입점. 서브커맨드 파싱 → 각 핸들러 위임. 절대 예외 밖으로 던지지 않는다. */
    suspend fun process(rawText: String, responseUrl: String, userId: String = "", userName: String = "") {
        val text = rawText.trim()
        if (text.isBlank()) {
            slack.postToResponseUrl(responseUrl, """사용법:
• `/edge 종목명` — AI 분석 코멘트
• `/edge 시황` — 오늘 시장 분위기
• `/edge 이벤트` — 거시 이벤트 캘린더
• `/edge 비교 종목A 종목B` — 두 종목 비교
• `/edge 신호 종목명` — 수급 신호 확인""")
            return
        }
        runCatching {
            val parts = text.split(Regex("\\s+"), limit = 3)
            val sub = parts[0]
            when {
                sub == "시황" -> handleMarketMood(responseUrl)
                sub == "이벤트" -> handleEvents(responseUrl)
                sub == "비교" && parts.size >= 3 -> handleComparison(parts[1], parts[2], responseUrl)
                sub == "비교" -> slack.postToResponseUrl(responseUrl, "사용법: `/edge 비교 종목A 종목B` (예: `/edge 비교 삼성전자 SK하이닉스`)")
                sub == "신호" && parts.size >= 2 -> handleSignal(parts[1], responseUrl)
                sub == "신호" -> slack.postToResponseUrl(responseUrl, "사용법: `/edge 신호 종목명` (예: `/edge 신호 삼성전자`)")
                else -> handleAnalysis(text, responseUrl)
            }
        }.onFailure { e ->
            System.err.println("[SlackCommand] process 실패(text=$text): ${e.message}")
            slack.postToResponseUrl(responseUrl, "처리 중 오류가 났어요. 잠시 후 다시 시도해 주세요.")
        }
    }

    /**
     * [채널에 공유] 버튼 인터랙션 핸들러. value = 원본 커맨드 텍스트.
     * 동일 커맨드를 재실행(캐시 있으면 즉시)해 in_channel로 게시한다.
     */
    suspend fun handleInteraction(
        userId: String,
        userName: String,
        actionId: String,
        value: String,
        responseUrl: String,
    ) {
        if (actionId != "share_to_channel") return
        runCatching {
            // users.info API로 표시 이름(닉네임) 조회. 실패 시 userName(Slack 계정명) 폴백.
            val displayName = slack.getUserDisplayName(userId, fallback = userName.ifBlank { "익명" })
            val content = generateContent(value)
            slack.postInChannelShared(responseUrl, content, displayName)
        }.onFailure { e ->
            System.err.println("[SlackInteraction] 처리 실패(value=$value): ${e.message}")
            slack.postToResponseUrl(responseUrl, "공유 중 오류가 났어요. 잠시 후 다시 시도해 주세요.")
        }
    }

    /** value(원본 커맨드 텍스트)를 파싱해 내용 문자열 재생성. 캐시가 있으면 빠르다. */
    private suspend fun generateContent(cmd: String): String {
        val parts = cmd.trim().split(Regex("\\s+"), limit = 3)
        val sub = parts.getOrNull(0) ?: ""
        return when {
            sub == "시황" -> formatMarketMood(marketMood.get())
            sub == "이벤트" -> formatEvents(eventSync.getUpcoming(30))
            sub == "비교" && parts.size >= 3 -> {
                val codeA = resolveCode(parts[1]) ?: error("종목 미발견: ${parts[1]}")
                val codeB = resolveCode(parts[2]) ?: error("종목 미발견: ${parts[2]}")
                formatComparison(comparison.compare(codeA, codeB))
            }
            sub == "신호" && parts.size >= 2 -> {
                val code = resolveCode(parts[1]) ?: error("종목 미발견: ${parts[1]}")
                val name = resolveStockName(code)
                formatSignalStatus(code, name, kis.getInvestorFlow(code, days = 5))
            }
            else -> {
                val code = resolveCode(cmd) ?: error("종목 미발견: $cmd")
                formatAnalysis(analysis.analyze(code))
            }
        }
    }

    // ─── 시황 ─────────────────────────────────────────────────────────────────

    private suspend fun handleMarketMood(responseUrl: String) {
        val mood = runCatching { marketMood.get() }.getOrElse {
            slack.postToResponseUrl(responseUrl, "시장 분위기 데이터를 불러올 수 없어요.")
            return
        }
        slack.postWithShareButton(responseUrl, formatMarketMood(mood), shareValue = "시황")
    }

    private fun formatMarketMood(mood: MarketMood): String = buildString {
        appendLine("*📊 오늘 시장 분위기 (${mood.date})*")
        appendLine()
        append(mood.comment.trim())
        append("\n\n_${mood.generatedAt.ifBlank { mood.date }} 기준 · 참고용_")
    }

    // ─── 이벤트 ───────────────────────────────────────────────────────────────

    private suspend fun handleEvents(responseUrl: String) {
        val events = eventSync.getUpcoming(30)
        slack.postWithShareButton(responseUrl, formatEvents(events), shareValue = "이벤트")
    }

    private fun formatEvents(events: List<MarketEvent>): String {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        if (events.isEmpty()) return "📅 *30일 내 주요 거시 이벤트*\n\n_등록된 이벤트가 없어요. `/events/sync` 로 동기화해 보세요._"
        return buildString {
            appendLine("📅 *30일 내 주요 거시 이벤트*")
            appendLine()
            events.forEach { e ->
                val dday = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(e.date)).toInt() }.getOrNull()
                val ddayStr = when {
                    dday == null -> ""
                    dday <= 0 -> " *D-day*"
                    dday == 1 -> " D-1"
                    else -> " D-$dday"
                }
                val catEmoji = when (e.category) { "호재" -> "🟢"; "주의" -> "🔴"; else -> "⚪" }
                appendLine("$catEmoji ${e.date}$ddayStr — ${e.title}")
                if (e.impact.isNotBlank()) appendLine("   _${e.impact}_")
            }
        }.trim()
    }

    // ─── 비교 ─────────────────────────────────────────────────────────────────

    private suspend fun handleComparison(textA: String, textB: String, responseUrl: String) {
        val codeA = resolveCode(textA) ?: run {
            slack.postToResponseUrl(responseUrl, "'$textA' 종목을 못 찾았어요.")
            return
        }
        val codeB = resolveCode(textB) ?: run {
            slack.postToResponseUrl(responseUrl, "'$textB' 종목을 못 찾았어요.")
            return
        }
        val cmp = comparison.compare(codeA, codeB)
        slack.postWithShareButton(responseUrl, formatComparison(cmp), shareValue = "비교 $textA $textB")
    }

    private fun formatComparison(cmp: Comparison): String = buildString {
        appendLine("*${cmp.a.name} vs ${cmp.b.name} 비교*")
        appendLine()
        appendLine("*지표 비교*")
        fun pct(v: Double) = "%+.2f%%".format(v)
        fun d1(v: Double) = "%.1f".format(v)
        fun lf(v: Long) = "%,d".format(v)
        appendLine("• 현재가: ${lf(cmp.a.price)}원 / ${lf(cmp.b.price)}원")
        appendLine("• 등락률: ${pct(cmp.a.changeRate)} / ${pct(cmp.b.changeRate)}")
        appendLine("• PER: ${d1(cmp.a.per)}배 / ${d1(cmp.b.per)}배")
        appendLine("• PBR: ${d1(cmp.a.pbr)}배 / ${d1(cmp.b.pbr)}배")
        appendLine("• 52주 위치: ${cmp.a.week52PosPct.toInt()}% / ${cmp.b.week52PosPct.toInt()}%")
        if (cmp.a.upsidePct != null || cmp.b.upsidePct != null) {
            appendLine("• 목표가 괴리: ${cmp.a.upsidePct?.let { pct(it) } ?: "-"} / ${cmp.b.upsidePct?.let { pct(it) } ?: "-"}")
        }
        cmp.a.valuationLabel?.let { appendLine("• ${cmp.a.name} 밸류: $it") }
        cmp.b.valuationLabel?.let { appendLine("• ${cmp.b.name} 밸류: $it") }
        appendLine()
        appendLine(cmp.comment.trim())
        append("\n\n_${cmp.generatedAt.ifBlank { "오늘" }} 기준 · 참고용_")
    }

    // ─── 신호 ─────────────────────────────────────────────────────────────────

    private suspend fun handleSignal(stockText: String, responseUrl: String) {
        val code = resolveCode(stockText) ?: run {
            slack.postToResponseUrl(responseUrl, "'$stockText' 종목을 못 찾았어요.")
            return
        }
        val name = resolveStockName(code)
        val flows = runCatching { kis.getInvestorFlow(code, days = 5) }.getOrElse { emptyList() }
        slack.postWithShareButton(responseUrl, formatSignalStatus(code, name, flows), shareValue = "신호 $stockText")
    }

    private fun formatSignalStatus(code: String, name: String, flows: List<InvestorFlow>): String {
        if (flows.isEmpty()) return "*$name* ($code)\n수급 데이터를 불러올 수 없어요."
        val fStreak = consecutiveStreak(flows.map { it.foreign })
        val iStreak = consecutiveStreak(flows.map { it.institution })
        return buildString {
            appendLine("*$name* ($code) — 수급 신호")
            appendLine()
            appendLine("외국인: ${streakLabel(fStreak)}")
            appendLine("기관: ${streakLabel(iStreak)}")
            appendLine()
            appendLine("*최근 ${flows.size}일 수급*")
            flows.take(5).forEach { f ->
                val d = "${f.date.substring(4, 6)}/${f.date.substring(6, 8)}"
                val fNet = if (f.foreign >= 0) "+%,d".format(f.foreign) else "%,d".format(f.foreign)
                val iNet = if (f.institution >= 0) "+%,d".format(f.institution) else "%,d".format(f.institution)
                appendLine("$d  외인 ${fNet}주  기관 ${iNet}주")
            }
            append("_참고용. 장후 확정 일별값 기준_")
        }
    }

    private fun streakLabel(streak: Int) = when {
        streak >= 3  -> "📈 *${streak}일 연속 순매수* ⭐"
        streak > 0   -> "📈 ${streak}일 연속 순매수"
        streak <= -3 -> "📉 *${-streak}일 연속 순매도* ⚠️"
        streak < 0   -> "📉 ${-streak}일 연속 순매도"
        else         -> "➡️ 보합"
    }

    /** 최신일부터 연속 방향 일수. 양수=순매수, 음수=순매도, 0=보합 */
    private fun consecutiveStreak(values: List<Long>): Int {
        if (values.isEmpty()) return 0
        val sign = when { values[0] > 0 -> 1; values[0] < 0 -> -1; else -> 0 }
        if (sign == 0) return 0
        var count = 0
        for (v in values) {
            if ((v > 0 && sign > 0) || (v < 0 && sign < 0)) count++ else break
        }
        return count * sign
    }

    // ─── 분석 (기존 S7) ───────────────────────────────────────────────────────

    private suspend fun handleAnalysis(rawText: String, responseUrl: String) {
        val code = resolveCode(rawText) ?: run {
            slack.postToResponseUrl(responseUrl, "'$rawText' 종목을 못 찾았어요. 정확한 종목명이나 6자리 코드로 다시 시도해 주세요.")
            return
        }
        val result = analysis.analyze(code)
        slack.postWithShareButton(responseUrl, formatAnalysis(result), shareValue = rawText)
    }

    private fun formatAnalysis(a: Analysis): String = buildString {
        val priceStr = a.generatedPrice?.let { " · %,d원".format(it.toLong()) } ?: ""
        appendLine("*${a.name}* (${a.code})$priceStr")
        appendLine()
        if (!a.summary.isNullOrBlank()) {
            appendLine("*📌 핵심 요약*")
            appendLine(a.summary.trim())
            appendLine()
            appendLine("───────────────")
            appendLine()
        }
        append(a.comment.trim())
        append("\n\n_${a.generatedAt.ifBlank { a.date }} 기준 · 참고용_")
    }

    // ─── 공통 헬퍼 ────────────────────────────────────────────────────────────

    /** 6자리면 코드, 아니면 종목마스터 검색 첫 결과 코드 */
    private suspend fun resolveCode(text: String): String? {
        if (codeRegex.matches(text)) return text
        return runCatching { master.search(text, limit = 1) }.getOrNull()?.firstOrNull()?.code
    }

    private suspend fun resolveStockName(code: String): String =
        runCatching { master.search(code, limit = 1).firstOrNull()?.name ?: code }.getOrElse { code }
}
