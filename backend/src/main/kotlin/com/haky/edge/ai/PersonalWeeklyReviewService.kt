package com.haky.edge.ai

import com.haky.edge.kis.KisClient
import com.haky.edge.macro.EventSyncService
import com.haky.edge.macro.HoldingPosition
import com.haky.edge.master.StockMaster
import com.haky.edge.news.TargetPriceLogService
import com.haky.edge.slack.WeeklyReviewService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.time.LocalDate

// ── 입력(앱이 POST로 보내는 로컬 데이터) ──────────────────────────────────

/** 이번 주 매매 1건 — 앱 action_log에서 주간 창(월~금) 안의 buy/sell만 추린 것. */
@Serializable
data class WeeklyTrade(
    val code: String,
    val name: String? = null,
    val action: String,          // "buy" | "sell" (interest는 앱이 제외)
    val reason: String? = null,
    val price: Long? = null,     // 기록 시점 현재가(원)
    val date: String,            // YYYY-MM-DD (로컬 날짜)
)

/** 이번 주 논지 변경 1건 — 앱 thesis_history에서 주간 창 안의 스냅샷. */
@Serializable
data class WeeklyThesisChange(
    val code: String,
    val thesis: String,
    val changedOn: String,       // YYYY-MM-DD
)

// ── 출력 DTO ──────────────────────────────────────────────────────────────

/**
 * 개인 주간 회고 응답. factLines(계산 사실, 우리가 조립)와 comment/summary(Opus 해석)를 분리한다 —
 * 앱은 사실 블록과 해석을 다른 스타일로 렌더한다(B1 Slack 회고와 같은 사실/해석 분리 원칙).
 */
@Serializable
data class PersonalWeeklyReview(
    val weekStart: String,       // 회고 대상 월요일 YYYY-MM-DD
    val weekEnd: String,         // 회고 대상 금요일 YYYY-MM-DD
    val factLines: String,       // 계산 요약 블록(사실, 매매·스탠스·논지) — 앱이 그대로 표시
    val holdingMoves: List<HoldingMove> = emptyList(), // 보유 종목 주간 등락 — 앱이 정렬된 행으로 렌더
    val comment: String,         // Opus 해석 본문
    val summary: String? = null, // "### 핵심 요약" 파싱(분석 코멘트와 동일 계약)
    val generatedAt: String = "",
    val tradeCount: Int = 0,
)

/** 보유 종목 1건의 주간 등락 — 앱이 종목명(좌)·등락률(우, 색상)로 정렬 렌더. */
@Serializable
data class HoldingMove(
    val name: String,
    val changePct: Double,
)

/**
 * B2 개인 주간 회고 — B1(#아침브리핑 시장 회고)의 개인판. 포트폴리오·매매로그·논지 변화는 앱 로컬
 * SQLite에만 있어 앱이 POST로 보내고, 서버는 여기에 서버 측 기록(보유 종목 주간 등락·AI 스탠스·목표가·
 * 다음 주 이벤트)을 합쳐 한 주를 회고한다.
 *
 * 원칙(B1과 동일): 수치·집계는 전부 계산(우리), Claude(Opus, WEEKLY_REVIEW 트리거)는 패턴 해석만.
 * 매매 결정은 결과편향·사후확신을 경계하며 과정과 결과를 분리해 회고한다(TradeReview 톤).
 *
 * 캐시: 입력이 개인 데이터라 개인별 — (주 금요일 + 포지션·매매·논지 해시). force 재생성엔 5분 쿨다운.
 */
class PersonalWeeklyReviewService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val stanceLog: StanceLog,
    private val stanceStats: StanceStatsService,
    private val targetPriceLog: TargetPriceLogService,
    private val eventSync: EventSyncService,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
) {
    private val fileCache = FileCache("weekly-review-personal", PersonalWeeklyReview.serializer())

    suspend fun review(
        positions: Map<String, HoldingPosition>,
        trades: List<WeeklyTrade>,
        thesisChanges: List<WeeklyThesisChange>,
        force: Boolean = false,
    ): PersonalWeeklyReview {
        require(positions.isNotEmpty() || trades.isNotEmpty() || thesisChanges.isNotEmpty()) {
            "회고할 개인 데이터가 없습니다(포지션·매매·논지 모두 비어 있음)"
        }
        val (monday, friday) = WeeklyReviewService.weekWindow(LocalDate.now(SEOUL))
        val key = buildKey(friday, positions, trades, thesisChanges)

        val cached = fileCache.get(key)
        if (cached != null) {
            if (!force || !isPastMinutes(cached.generatedAt, FORCE_COOLDOWN_MINUTES)) {
                if (force) println("[ForceCooldown] weekly-review-personal: ${FORCE_COOLDOWN_MINUTES}분 내 재생성 요청 → 캐시 반환")
                return cached
            }
        }

        val result = build(monday, friday, positions, trades, thesisChanges)
        fileCache.put(key, result)
        return result
    }

    private suspend fun build(
        monday: LocalDate,
        friday: LocalDate,
        positions: Map<String, HoldingPosition>,
        trades: List<WeeklyTrade>,
        thesisChanges: List<WeeklyThesisChange>,
    ): PersonalWeeklyReview = coroutineScope {
        val mondayStr = monday.toString()
        val fridayStr = friday.toString()
        val interest = (positions.keys + trades.map { it.code } + thesisChanges.map { it.code }).distinct()

        // 이름 해석(보유·매매·논지에 등장하는 코드 전부).
        val nameOf: Map<String, String> = interest.associateWith { c ->
            runCatching { master.findByCode(c)?.name }.getOrNull() ?: c
        }

        // ① 보유 종목 주간 등락 + 현재가(매매 후 흐름 계산용). 코드별 1회만 조회.
        val quotedCodes = (positions.keys + trades.mapNotNull { it.code }).distinct()
        val priceData = quotedCodes.map { code ->
            async {
                val bars = runCatching { kis.getDailyChart(code, bars = 10) }.getOrElse { emptyList() }
                val cur = runCatching { kis.getPrice(code).price }.getOrNull()
                code to (WeeklyReviewService.weeklyChangePct(bars, monday, friday) to cur)
            }
        }.awaitAll().toMap()
        val weeklyMove: Map<String, Double?> = priceData.mapValues { it.value.first }
        val currentPrice: Map<String, Long?> = priceData.mapValues { it.value.second }

        // ② 이번 주 AI 스탠스 + 전환 (보유·매매 종목으로 한정).
        val allStances = stanceLog.readAll()
        val weekStances = allStances.filter {
            it.code in interest && it.date in mondayStr..fridayStr && it.stance != "미상"
        }
        val transitions = WeeklyReviewService.stanceTransitions(allStances, mondayStr, fridayStr)
            .filter { it.code in interest }
        val stats = runCatching { stanceStats.stats() }.getOrNull()

        // ③ 목표가 주간 변화.
        val targetChanges = interest.mapNotNull { code ->
            targetPriceLog.weeklyChange(code, monday, friday)?.let { (from, to) ->
                Triple(nameOf[code] ?: code, from, to)
            }
        }

        // ④ 다음 주 이벤트(9일 = 다음 주 금요일까지).
        val upcoming = runCatching { eventSync.getUpcoming(9) }.getOrElse { emptyList() }

        // ── 보유 종목 주간 등락(구조화) — 앱이 정렬된 색상 행으로 렌더 ────────────
        val holdingMoves = positions.keys
            .mapNotNull { c -> weeklyMove[c]?.let { HoldingMove(nameOf[c] ?: c, it) } }
            .sortedByDescending { it.changePct }

        // ── 나머지 계산 요약 블록(사실: 매매·스탠스·논지) — 앱이 그대로 렌더 ────────
        val weekTrades = trades.filter { it.date in mondayStr..fridayStr }.sortedBy { it.date }
        val factLines = buildString {
            if (weekTrades.isNotEmpty()) {
                appendLine("이번 주 매매 ${weekTrades.size}건")
                weekTrades.forEach { t ->
                    val name = t.name?.takeIf { it.isNotBlank() } ?: nameOf[t.code] ?: t.code
                    val act = actionKo(t.action)
                    val at = t.price?.let { " @${fmtWon(it)}" } ?: ""
                    appendLine("  ${WeeklyReviewService.koreanDate(t.date)} $act $name$at")
                }
            }
            if (transitions.isNotEmpty()) {
                appendLine("AI 스탠스 전환")
                transitions.forEach { t ->
                    appendLine("  ${nameOf[t.code] ?: t.code} ${t.from}→${t.to}")
                }
            }
            if (thesisChanges.isNotEmpty()) {
                appendLine("논지 변경: " + thesisChanges.joinToString(", ") { nameOf[it.code] ?: it.code })
            }
        }.trimEnd()

        // ── Claude 회고(해석만) ───────────────────────────────────────────
        val facts = buildFacts(monday, friday, positions, weekTrades, weeklyMove, currentPrice,
            weekStances, transitions, stats?.overall?.let { Triple(it.n, it.correct, it.accuracyPct) },
            thesisChanges, targetChanges, upcoming, nameOf)
        val model = modelRouter.modelFor(ModelRouter.WEEKLY_REVIEW)
        val raw = runCatching { claude.complete(PERSONAL_PROMPT, facts, maxTokens = 1800, modelOverride = model) }
            .getOrElse { "" }
        val (summary, comment) = AnalysisService.parseSummaryFromComment(raw)

        val now = java.time.LocalTime.now(SEOUL)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        PersonalWeeklyReview(
            weekStart = monday.toString(),
            weekEnd = friday.toString(),
            factLines = factLines,
            holdingMoves = holdingMoves,
            comment = comment,
            summary = summary,
            generatedAt = now,
            tradeCount = weekTrades.size,
        )
    }

    /** Claude 입력용 사실 텍스트 — 여기 있는 값만 근거로 쓰라고 프롬프트가 지시. */
    private fun buildFacts(
        monday: LocalDate,
        friday: LocalDate,
        positions: Map<String, HoldingPosition>,
        weekTrades: List<WeeklyTrade>,
        weeklyMove: Map<String, Double?>,
        currentPrice: Map<String, Long?>,
        weekStances: List<StanceEntry>,
        transitions: List<WeeklyReviewService.StanceTransition>,
        stanceOverall: Triple<Int, Int, Double>?,
        thesisChanges: List<WeeklyThesisChange>,
        targetChanges: List<Triple<String, Long, Long>>,
        upcoming: List<com.haky.edge.macro.MarketEvent>,
        nameOf: Map<String, String>,
    ): String = buildString {
        appendLine("회고 대상 주간: $monday(월) ~ $friday(금)")
        appendLine()

        if (weekTrades.isNotEmpty()) {
            appendLine("[이번 주 내 매매]")
            weekTrades.forEach { t ->
                val name = t.name?.takeIf { it.isNotBlank() } ?: nameOf[t.code] ?: t.code
                val at = t.price?.let { " ${fmtWon(it)}에" } ?: ""
                append("- ${WeeklyReviewService.koreanDate(t.date)} ${actionKo(t.action)}$at $name")
                // 매매 후 흐름(결과 회고용): 기록가 대비 현재가 변화. 결과는 사실일 뿐 판단이 아님.
                val cur = currentPrice[t.code]
                if (t.price != null && t.price > 0 && cur != null) {
                    val drift = (cur - t.price).toDouble() / t.price * 100
                    val base = if (t.action == "sell") "매도가" else "매수가"
                    append(" (현재 ${fmtWon(cur)}, $base 대비 ${fmtPct(drift)})")
                }
                t.reason?.trim()?.takeIf { it.isNotBlank() }?.let { append(" — 사유: \"$it\"") }
                appendLine()
            }
            appendLine()
        }

        val moves = positions.keys.mapNotNull { c -> weeklyMove[c]?.let { (nameOf[c] ?: c) to it } }
            .sortedByDescending { it.second }
        if (moves.isNotEmpty()) {
            appendLine("[보유 종목 주간 등락] (금요일 종가 기준, 주간 변화율)")
            moves.forEach { (name, pct) -> appendLine("- $name: ${fmtPct(pct)}") }
            appendLine()
        }

        if (weekStances.isNotEmpty() || transitions.isNotEmpty()) {
            appendLine("[보유 종목 AI 스탠스]")
            weekStances.sortedBy { it.date }.forEach { e ->
                appendLine("- ${WeeklyReviewService.koreanDate(e.date)} ${nameOf[e.code] ?: e.code}(${WeeklyReviewService.modeKo(e.mode)}): ${e.stance}")
            }
            if (transitions.isNotEmpty()) {
                appendLine("- 스탠스 전환:")
                transitions.forEach { t ->
                    appendLine("  · ${nameOf[t.code] ?: t.code}(${WeeklyReviewService.modeKo(t.mode)}): ${t.from} → ${t.to} (${WeeklyReviewService.koreanDate(t.date)})")
                }
            }
            stanceOverall?.let { (n, correct, pct) ->
                appendLine("- 스탠스 누적 채점(20거래일 후 수익률 대조): $correct/$n 적중(${"%.0f".format(pct)}%)")
            }
            appendLine()
        }

        if (thesisChanges.isNotEmpty()) {
            appendLine("[이번 주 투자 논지 변경] (사용자가 직접 기록한 보유 이유 — 가설이며 사실 아님)")
            thesisChanges.sortedBy { it.changedOn }.forEach { c ->
                appendLine("- ${WeeklyReviewService.koreanDate(c.changedOn)} ${nameOf[c.code] ?: c.code}: \"${c.thesis.trim()}\"")
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
            upcoming.forEach { e -> appendLine("- ${WeeklyReviewService.koreanDate(e.date)} ${e.title} (${e.category}) — ${e.impact}") }
        }
    }

    companion object {
        private val SEOUL = java.time.ZoneId.of("Asia/Seoul")
        private const val FORCE_COOLDOWN_MINUTES = 5L

        private fun isPastMinutes(generatedAt: String, minutes: Long): Boolean {
            if (generatedAt.isBlank()) return true
            return try {
                val genTime = java.time.LocalTime.parse(generatedAt, java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                val now = java.time.LocalTime.now(SEOUL)
                java.time.Duration.between(genTime, now).toMinutes() >= minutes
            } catch (e: Exception) { true }
        }

        private fun fmtPct(p: Double): String = (if (p >= 0) "+%.2f%%" else "%.2f%%").format(p)
        private fun fmtWon(v: Long): String = "%,d원".format(v)

        /** action_log 값 → 한국어. interest는 앱이 제외하지만 방어적으로 매핑. */
        internal fun actionKo(action: String): String = when (action) {
            "buy" -> "매수"
            "sell" -> "매도"
            "interest" -> "관심"
            else -> action
        }

        /** 캐시 키: 주 금요일 + 정렬된 포지션 + 매매 + 논지 해시. 입력이 다르면 새 키. */
        internal fun buildKey(
            friday: LocalDate,
            positions: Map<String, HoldingPosition>,
            trades: List<WeeklyTrade>,
            thesisChanges: List<WeeklyThesisChange>,
        ): String {
            val p = positions.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value.avgPrice.toLong()}:${it.value.qty}" }
            val t = trades.sortedWith(compareBy({ it.date }, { it.code }, { it.action }))
                .joinToString(",") { "${it.date}:${it.code}:${it.action}:${it.price ?: 0}" }
            val th = thesisChanges.sortedWith(compareBy({ it.changedOn }, { it.code }))
                .joinToString(",") { "${it.changedOn}:${it.code}:${it.thesis.trim()}" }
            return "$friday|${AnalysisService.shortHash("$p|$t|$th")}"
        }

        // 개인 주간 회고 프롬프트 — 계산 사실(위 factLines·facts)은 이미 보여주므로 Claude는 "패턴 해석"만.
        private val PERSONAL_PROMPT = """
            너는 한국 주식 투자 보조 앱의 개인 주간 회고 작성자다. 아래 사실 데이터는 한 사용자의 지난 한 주
            (월~금) 기록이다 — 이번 주 한 매매(매수/매도 사유·매매 후 주가 흐름), 보유 종목의 주간 등락,
            이 앱 AI가 그 종목에 낸 판단(스탠스)과 전환, 사용자가 바꾼 투자 논지, 컨센서스 목표가 변화,
            다음 주 일정. 이를 회고하는 코멘트를 작성하라.

            응답 형식(반드시):
            맨 앞에 아래 블록을 넣어라:

            ### 핵심 요약
            (2~3문장 산문. 이번 주 이 사용자의 결정과 보유 흐름에서 가장 중요한 특징을 핵심 수치와 함께.
            불릿 없이 흐르는 문장으로.)

            그 다음 빈 줄 하나 후에 이어지는 문단 2~3개로 써라.

            규칙(반드시 지킬 것):
            R1. 사실 데이터에 있는 값만 근거로 쓴다. 거기 없는 수치·사건·종목을 지어내지 마라.
            R2. 이번 주 매매가 있으면, 매수/매도 사유와 그 뒤 주가 흐름을 대조해 회고하라 — 단 매매 후
                단기 등락은 표본 1의 결과일 뿐이다. "팔고 나서 올랐다/사고 나서 빠졌다"를 정면으로 짚되,
                그것으로 "그때 판단이 틀렸다"고 단정하지 마라. **과정(사유가 근거 있었나)과 결과(단기 주가)를
                분리**해서 보여줘라. 결과가 좋아도 사유가 부실했으면 짚고, 결과가 나빠도 사유가 견고했으면 인정하라.
            R3. 투자 논지를 바꾼 종목이 있으면, 바뀐 방향이 그 주의 실제 주가 흐름·AI 스탠스와 어떻게
                맞물리는지 관찰하라. 특히 주가가 빠진 뒤 논지가 약해졌거나 목표가 낮아진 것과 겹치면,
                사후 합리화(결과에 맞춰 이유를 바꾸는 것)의 가능성을 부드럽지만 정직하게 짚어라.
            R4. AI 스탠스·적중률 통계는 표본 수를 함께 표기하고, 표본이 15 미만이면 "참고 수준"으로 한정하라.
                주간 등락만으로 종목의 추세 전환을 단정하지 마라.
            R5. "다음 주 이벤트"가 있으면 마지막 문단에서 보유 종목과 관련된 것만 무엇을 지켜볼지 조건부로 짚어라.
                이벤트 결과나 시장 방향을 단정하지 마라. 관련 없는 일정은 건너뛰어라.
            R6. 매매 지시(사라/팔라/비중 조정) 금지. 격려·덕담·사과·인사말 금지 — 기록과 해석만 남겨라.
            R7. 형식: 소제목·불릿·구분선 없이 흐르는 문단(핵심 요약 블록 제외). 문단 사이 빈 줄 하나.
                핵심 수치는 **굵게**. 모든 표현은 한국어로 — 영어 약어·시스템 내부 라벨을 그대로 옮기지 마라.

            마지막 경고: 너의 학습 지식 속 주가·지수·수치는 낡아서 틀렸다. 위 사실 데이터의 값만 그대로
            복사해 쓰라.
        """.trimIndent()
    }
}
