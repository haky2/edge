package com.haky.edge.slack

import com.haky.edge.ai.ClaudeClient
import com.haky.edge.ai.ModelRouter
import com.haky.edge.ai.StanceEntry
import com.haky.edge.ai.StanceLog
import com.haky.edge.ai.StanceStatsService
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.KisClient
import com.haky.edge.macro.EventSyncService
import com.haky.edge.macro.MarketMoodLogService
import com.haky.edge.macro.MoodLogEntry
import com.haky.edge.master.StockMaster
import com.haky.edge.news.TargetPriceLogService
import com.haky.edge.util.writeTextAtomic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** 주간 회고 1주치 영속 기록 — 스케줄러 재시도·수동 재호출에도 Opus 재생성/중복 발송을 막는다. */
@Serializable
data class WeeklyReviewRecord(
    val weekFriday: String,   // 회고 대상 주의 금요일(YYYY-MM-DD) = 주 식별자
    val report: String,       // Slack 발송 전문(계산 요약 + Claude 회고)
    val sentAt: String? = null,
)

/**
 * B 주간 회고 리포트 — 토요일 아침, 지난 한 주(월~금)의 서버 측 기록을 회고한다.
 * 재료는 전부 기존 축적 데이터(새 수집 없음, 관심종목 주간 등락만 일봉 재사용):
 *   - MarketMoodLog : 일별 코스피 방향 예측·실제·적중
 *   - StanceLog/StanceStats : 이번 주 AI 스탠스 생성분·전환 + 누적 채점 적중률
 *   - TargetPriceLog : 관심종목 목표가 주간 상향/하향
 *   - KisClient 일봉 : 관심종목 주간 등락(금요일 종가 기준)
 *   - EventSync : 다음 주 이벤트
 * 원칙: 수치·집계는 전부 계산(우리), Claude(Opus, WEEKLY_REVIEW 트리거)는 한 주 패턴 해석만.
 * 포트폴리오·매매로그는 앱 로컬 데이터라 여기 없음 — 개인 회고는 B2(앱 합류)에서.
 */
class WeeklyReviewService(
    private val slack: SlackClient,
    private val channel: String,
    private val kis: KisClient,
    private val master: StockMaster,
    private val codes: List<String>,
    private val stanceLog: StanceLog,
    private val stanceStats: StanceStatsService,
    private val moodLog: MarketMoodLogService,
    private val targetPriceLog: TargetPriceLogService,
    private val eventSync: EventSyncService,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
) {
    private val dataDir = File(System.getenv("DATA_DIR") ?: ".data").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 리포트 생성(주 1회 캐시) + Slack 발송. 이미 발송된 주면 재발송하지 않는다(force로 우회).
     * [dryRun]=true 면 생성·캐시만 하고 발송하지 않는다(로컬 검증용).
     */
    suspend fun send(force: Boolean = false, dryRun: Boolean = false): WeeklyReviewRecord {
        val (monday, friday) = weekWindow(LocalDate.now(SEOUL))
        val file = File(dataDir, "weekly_review_$friday.json")
        var record = runCatching { json.decodeFromString(WeeklyReviewRecord.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { !force }
        if (record == null) {
            record = WeeklyReviewRecord(friday.toString(), buildReport(monday, friday))
            runCatching { file.writeTextAtomic(json.encodeToString(WeeklyReviewRecord.serializer(), record)) }
        }
        if (dryRun || record.sentAt != null) return record
        if (!slack.isConfigured || channel.isBlank()) return record
        val ok = slack.postMessage(channel, record.report)
        if (ok) {
            record = record.copy(sentAt = java.time.ZonedDateTime.now(SEOUL).toString())
            runCatching { file.writeTextAtomic(json.encodeToString(WeeklyReviewRecord.serializer(), record)) }
        }
        return record
    }

    /** 계산 요약(우리) + Claude 회고(해석)를 합친 Slack 전문을 만든다. */
    private suspend fun buildReport(monday: LocalDate, friday: LocalDate): String = coroutineScope {
        val mondayStr = monday.toString()
        val fridayStr = friday.toString()

        // ① 코스피 주간 — MoodLog 일별 기록 재사용(새 API 호출 없음)
        val moodEntries = moodLog.getAccuracyReport()
        val weekMoods = moodEntries.recentEntries
            .filter { it.date in mondayStr..fridayStr }
            .sortedBy { it.date }
        val kospiWeekPct = compoundPct(weekMoods.mapNotNull { it.kospiChange })

        // ② 관심종목 주간 등락 — 일봉 10개면 충분(주 5거래일 + 직전 종가)
        val weekly = codes.map { code ->
            async {
                val bars = runCatching { kis.getDailyChart(code, bars = 10) }.getOrElse { emptyList() }
                val name = runCatching { master.findByCode(code)?.name }.getOrNull() ?: code
                Triple(code, name, weeklyChangePct(bars, monday, friday))
            }
        }.awaitAll().filter { it.third != null }.sortedByDescending { it.third!! }

        // ③ 이번 주 AI 스탠스 + 전환
        val allStances = stanceLog.readAll()
        val weekStances = allStances.filter { it.date in mondayStr..fridayStr && it.stance != "미상" }
        val transitions = stanceTransitions(allStances, mondayStr, fridayStr)
        val stats = runCatching { stanceStats.stats() }.getOrNull()

        // 이름 해석은 suspend라 여기서 한 번에(joinToString 등 비인라인 람다 안에서는 호출 불가).
        val nameOf: Map<String, String> = (codes + weekStances.map { it.code } + transitions.map { it.code })
            .distinct()
            .associateWith { c -> runCatching { master.findByCode(c)?.name }.getOrNull() ?: c }

        // ④ 목표가 주간 변화
        val targetChanges = codes.mapNotNull { code ->
            targetPriceLog.weeklyChange(code, monday, friday)?.let { (from, to) ->
                Triple(nameOf[code] ?: code, from, to)
            }
        }

        // ⑤ 다음 주 이벤트(9일 = 다음 주 금요일까지)
        val upcoming = runCatching { eventSync.getUpcoming(9) }.getOrElse { emptyList() }

        // ── 계산 요약 블록(사실, 우리가 직접 조립) ─────────────────────────
        val header = "📒 *주간 회고 ${monday.monthValue}/${monday.dayOfMonth}(월) ~ ${friday.monthValue}/${friday.dayOfMonth}(금)*"
        val statLines = buildString {
            if (kospiWeekPct != null) {
                val correct = weekMoods.count { it.isCorrect == true }
                val scoredCnt = weekMoods.count { it.isCorrect != null }
                append("*코스피 주간* ${fmtPct(kospiWeekPct)}")
                if (scoredCnt > 0) append(" · 방향예측 $correct/${scoredCnt} 적중")
                if (moodEntries.total > 0) append(" (누적 ${moodEntries.correct}/${moodEntries.total})")
                appendLine()
            }
            if (weekly.isNotEmpty()) {
                val top = weekly.take(2).joinToString(" · ") { "${it.second} ${fmtPct(it.third!!)}" }
                val bottom = weekly.takeLast(2).reversed().joinToString(" · ") { "${it.second} ${fmtPct(it.third!!)}" }
                appendLine("*관심종목 주간* 상위: $top / 하위: $bottom")
            }
            if (weekStances.isNotEmpty()) {
                val byStance = weekStances.groupingBy { it.stance }.eachCount()
                val parts = listOf("긍정", "중립", "부정").mapNotNull { s -> byStance[s]?.let { "$s $it" } }
                append("*AI 판단* 생성 ${weekStances.size}건(${parts.joinToString(" · ")})")
                if (transitions.isNotEmpty()) {
                    append(" · 전환 ${transitions.size}건: ")
                    append(transitions.joinToString(", ") { t -> "${nameOf[t.code] ?: t.code} ${t.from}→${t.to}" })
                }
                stats?.overall?.let { append(" · 누적 적중 ${it.correct}/${it.n}") }
                appendLine()
            }
            if (targetChanges.isNotEmpty()) {
                appendLine("*목표가* " + targetChanges.joinToString(" · ") { (name, from, to) ->
                    val dir = if (to > from) "상향" else "하향"
                    "$name ${fmtWon(from)}→${fmtWon(to)}($dir)"
                })
            }
            if (upcoming.isNotEmpty()) {
                appendLine("*다음 주 이벤트* " + upcoming.take(4).joinToString(" · ") { e ->
                    val d = runCatching { LocalDate.parse(e.date) }.getOrNull()
                    val md = d?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: e.date
                    "$md ${e.title}"
                })
            }
        }.trimEnd()

        // ── Claude 회고(해석만) ────────────────────────────────────────────
        val facts = buildFacts(monday, friday, kospiWeekPct, weekMoods, moodEntries.total, moodEntries.correct,
            weekly, weekStances, transitions, stats?.overall?.let { Triple(it.n, it.correct, it.accuracyPct) }, targetChanges, upcoming, nameOf)
        val model = modelRouter.modelFor(ModelRouter.WEEKLY_REVIEW)
        val comment = runCatching { claude.complete(WEEKLY_PROMPT, facts, maxTokens = 1500, modelOverride = model) }
            .getOrElse { "" }

        buildString {
            appendLine(header)
            appendLine()
            appendLine(statLines)
            if (comment.isNotBlank()) {
                appendLine()
                appendLine("────────")
                appendLine(comment.trim())
            }
        }.trim()
    }

    private fun buildFacts(
        monday: LocalDate, friday: LocalDate,
        kospiWeekPct: Double?, weekMoods: List<MoodLogEntry>, moodTotal: Int, moodCorrect: Int,
        weekly: List<Triple<String, String, Double?>>,
        weekStances: List<StanceEntry>, transitions: List<StanceTransition>,
        stanceOverall: Triple<Int, Int, Double>?,
        targetChanges: List<Triple<String, Long, Long>>,
        upcoming: List<com.haky.edge.macro.MarketEvent>,
        nameOf: Map<String, String>,
    ): String = buildString {
        appendLine("회고 대상 주간: $monday(월) ~ $friday(금)")
        appendLine()
        if (weekMoods.isNotEmpty()) {
            appendLine("[코스피 주간]")
            kospiWeekPct?.let { appendLine("- 주간 등락(일별 합성): ${fmtPct(it)}") }
            weekMoods.forEach { m ->
                val actual = m.kospiChange?.let { fmtPct(it) } ?: "판정 보류"
                val hit = when (m.isCorrect) { true -> "적중"; false -> "빗나감"; null -> "보류" }
                // 내부 라벨(BULLISH 등)은 한국어로 — 그대로 주입하면 모델이 코멘트에 영어를 복사한다.
                appendLine("- ${koreanDate(m.date)}: 예측 ${directionKo(m.direction)} / 실제 $actual ($hit)")
            }
            if (moodTotal > 0) appendLine("- 방향 예측 누적: $moodCorrect/$moodTotal 적중")
            appendLine()
        }
        if (weekly.isNotEmpty()) {
            appendLine("[관심종목 주간 등락] (금요일 종가 기준, 주간 변화율)")
            weekly.forEach { (_, name, pct) -> appendLine("- $name: ${fmtPct(pct!!)}") }
            appendLine()
        }
        if (weekStances.isNotEmpty()) {
            appendLine("[이번 주 AI 스탠스 생성분]")
            weekStances.sortedBy { it.date }.forEach { e ->
                appendLine("- ${koreanDate(e.date)} ${nameOf[e.code] ?: e.code}(${modeKo(e.mode)}): ${e.stance}")
            }
            if (transitions.isNotEmpty()) {
                appendLine("- 스탠스 전환:")
                transitions.forEach { t ->
                    appendLine("  · ${nameOf[t.code] ?: t.code}(${modeKo(t.mode)}): ${t.from} → ${t.to} (${koreanDate(t.date)})")
                }
            }
            stanceOverall?.let { (n, correct, pct) ->
                appendLine("- 스탠스 누적 채점(20거래일 후 수익률 대조): $correct/$n 적중(${"%.0f".format(pct)}%)")
            }
            appendLine()
        }
        if (targetChanges.isNotEmpty()) {
            appendLine("[컨센서스 목표가 주간 변화]")
            targetChanges.forEach { (name, from, to) ->
                appendLine("- $name: ${fmtWon(from)} → ${fmtWon(to)} (${if (to > from) "상향" else "하향"})")
            }
            appendLine()
        }
        if (upcoming.isNotEmpty()) {
            appendLine("[다음 주 이벤트]")
            upcoming.forEach { e -> appendLine("- ${koreanDate(e.date)} ${e.title} (${e.category}) — ${e.impact}") }
        }
    }

    data class StanceTransition(val code: String, val mode: String, val from: String, val to: String, val date: String)

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")

        /**
         * 회고 대상 주간 [월요일, 금요일]. 기준 금요일 = 오늘 포함 직전 금요일 —
         * 토요일 아침 실행이면 어제(이번 주), 주중 수동 실행이면 지난 완결 주를 회고한다.
         */
        internal fun weekWindow(today: LocalDate): Pair<LocalDate, LocalDate> {
            val friday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            return friday.minusDays(4) to friday
        }

        /**
         * 주간 등락률. 일봉(최신순)에서 창 내 마지막 종가 vs 기준(창 이전 마지막 종가,
         * 없으면 창 내 첫 봉 종가 — 그 주 내 변화만). 창 내 봉이 없으면 null.
         */
        internal fun weeklyChangePct(barsDesc: List<DailyBar>, monday: LocalDate, friday: LocalDate): Double? {
            val fmt = DateTimeFormatter.BASIC_ISO_DATE
            val dated = barsDesc
                .mapNotNull { b -> runCatching { LocalDate.parse(b.date, fmt) }.getOrNull()?.let { it to b } }
                .sortedBy { it.first }
            val inWindow = dated.filter { !it.first.isBefore(monday) && !it.first.isAfter(friday) }
            val end = inWindow.lastOrNull()?.second ?: return null
            val base = dated.lastOrNull { it.first.isBefore(monday) }?.second ?: inWindow.first().second
            if (base === end || base.close <= 0) return null
            return (end.close - base.close).toDouble() / base.close * 100
        }

        /**
         * 이번 주 스탠스 전환 추출. 종목·모드별 유효(미상 제외) 시계열에서, 창 내 항목이
         * 직전 항목과 다른 스탠스면 전환 1건. 같은 (종목·모드·from·to)는 1건으로 접는다.
         */
        internal fun stanceTransitions(all: List<StanceEntry>, mondayStr: String, fridayStr: String): List<StanceTransition> {
            return all.asSequence()
                .filter { it.stance != "미상" }
                .groupBy { it.code to it.mode }
                .flatMap { (key, entries) ->
                    entries.zipWithNext().mapNotNull { (prev, cur) ->
                        if (cur.date in mondayStr..fridayStr && prev.stance != cur.stance)
                            StanceTransition(key.first, key.second, prev.stance, cur.stance, cur.date)
                        else null
                    }
                }
                .distinctBy { listOf(it.code, it.mode, it.from, it.to) }
        }

        /** 일별 등락률 목록을 복리 합성한 주간 등락률(%). 비면 null. */
        internal fun compoundPct(dailyPcts: List<Double>): Double? {
            if (dailyPcts.isEmpty()) return null
            return (dailyPcts.fold(1.0) { acc, p -> acc * (1 + p / 100) } - 1) * 100
        }

        private fun fmtPct(p: Double): String = (if (p >= 0) "+%.2f%%" else "%.2f%%").format(p)

        private fun fmtWon(v: Long): String = "%,d원".format(v)

        /** 내부 방향 라벨 → 한국어. 원문 그대로 주입하면 모델이 영어(BULLISH 등)를 코멘트에 복사한다. */
        internal fun directionKo(direction: String): String = when (direction) {
            "BULLISH" -> "상승 우위"
            "BEARISH" -> "하락 우위"
            "NEUTRAL" -> "중립"
            else -> direction
        }

        /** 내부 모드 라벨 → 한국어(공격/방어). */
        internal fun modeKo(mode: String): String = when (mode) {
            "aggressive" -> "공격 모드"
            "defensive" -> "방어 모드"
            else -> mode
        }

        /** "2026-07-07" → "7/7(화)" — 모델이 요일 서사를 정확히 쓰게 요일까지 계산해 준다. */
        internal fun koreanDate(iso: String): String {
            val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
            val dow = when (d.dayOfWeek) {
                DayOfWeek.MONDAY -> "월"; DayOfWeek.TUESDAY -> "화"; DayOfWeek.WEDNESDAY -> "수"
                DayOfWeek.THURSDAY -> "목"; DayOfWeek.FRIDAY -> "금"; DayOfWeek.SATURDAY -> "토"; DayOfWeek.SUNDAY -> "일"
            }
            return "${d.monthValue}/${d.dayOfMonth}($dow)"
        }

        // 주간 회고 프롬프트 — 계산 사실은 위 블록이 이미 보여주므로 Claude는 "패턴 해석"만.
        private val WEEKLY_PROMPT = """
            너는 한국 주식 투자 보조 앱의 주간 회고 작성자다. 아래 사실 데이터는 지난 한 주(월~금)의
            기록이다 — 시장 방향 예측 채점, 관심종목 주간 등락, 이 앱 AI의 종목 판단(스탠스)과 전환,
            컨센서스 목표가 변화, 다음 주 일정. 이를 회고하는 코멘트 2~3문단만 작성하라.

            규칙(반드시 지킬 것):
            W1. 사실 데이터에 있는 값만 근거로 쓴다. 거기 없는 수치·사건을 지어내지 마라.
            W2. 잘 맞은 판단과 틀린 판단을 같은 무게로 다뤄라. AI 판단(스탠스·방향 예측)이 실제 흐름과
                어긋난 지점이 있으면 에두르지 말고 정면으로 짚어라 — 이 회고의 가치는 자화자찬이 아니라
                어긋남의 발견에 있다.
            W3. 적중률·통계는 표본 수를 함께 표기하고, 표본이 15 미만이면 "참고 수준"으로 한정하라.
                주간 등락만으로 종목의 추세 전환을 단정하지 마라.
            W4. "다음 주 이벤트"가 있으면 마지막 문단에서 무엇을 지켜봐야 하는지 조건부로만 짚어라.
                이벤트 결과나 시장 방향을 단정하지 마라.
            W5. 매매 지시 금지. 격려·덕담·사과·인사말 금지 — 기록과 해석만 남겨라.
            W6. 형식: 소제목·불릿 없이 흐르는 문단 2~3개. 문단 사이 빈 줄 하나. 핵심 수치는 **굵게**.
                모든 표현은 한국어로 — 영어 약어·시스템 내부 라벨을 그대로 옮기지 마라.

            마지막 경고: 너의 학습 지식 속 주가·지수·수치는 낡아서 틀렸다. 위 사실 데이터의 값만 그대로
            복사해 쓰라.
        """.trimIndent()
    }
}
