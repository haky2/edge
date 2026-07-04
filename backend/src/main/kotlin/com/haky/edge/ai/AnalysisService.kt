package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.dart.FinancialSummary
import com.haky.edge.dart.QuarterlyIncome
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.Quote
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.KrxShortSellingClient
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.ShortSellingSummary
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NaverTargetPriceClient
import com.haky.edge.news.NewsItem
import com.haky.edge.news.TargetPriceLogService
import com.haky.edge.news.TargetPriceTrend
import com.haky.edge.slack.SlackClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** 코멘트 생성 시 사용된 데이터 소스 유무. 앱에서 "근거 두께" 표시에 쓴다. */
@Serializable
data class FactsRichness(
    val newsCount: Int = 0,
    val hasInvestorFlow: Boolean = false,
    val hasFinancials: Boolean = false,
    val hasQuarterlyIncome: Boolean = false,
    val hasShortSelling: Boolean = false,
    val hasValuationBand: Boolean = false,
    val hasBacktest: Boolean = false,
    val hasFlowSensitivity: Boolean = false,
)

/** 앱에 내려주는 분석 결과. comment 는 참고용 종합 코멘트. */
@Serializable
data class Analysis(
    val code: String,
    val name: String,
    val date: String,       // 생성 기준일 (YYYY-MM-DD)
    val comment: String,
    val summary: String? = null,        // 핵심 요약 2~3문장 (comment 맨 앞 ### 핵심 요약 블록 파싱)
    val generatedAt: String = "",       // 캐시 최초 생성 시각 HH:mm (KST)
    val generatedPrice: Double? = null, // 코멘트 생성 시점 현재가 — stale 감지용
    val factsRichness: FactsRichness? = null,
    val numberWarning: Boolean = false, // facts에 없는 수치가 응답에서 발견됨
)

/** Q&A 한 턴(질문·답). 후속 질문 시 앱이 이전 문답을 history로 되보낸다(서버 무상태). */
@Serializable
data class AskTurn(val question: String, val answer: String)

/** 종목 자유 질문(Q&A) 응답. 자유 질문이라 공유 캐시 없음 — 매 호출 생성. */
@Serializable
data class AskAnswer(
    val code: String,
    val name: String,
    val date: String,       // 기준 거래일 (YYYY-MM-DD)
    val question: String,
    val answer: String,
    val generatedAt: String, // 생성 시각 HH:mm (KST)
)

/** Q&A 일일 질문 한도 초과 — 라우트에서 429로 변환. */
class AskDailyLimitException(message: String) : Exception(message)

/** 개인 포지션 정보. avgPrice·qty 가 있을 때만 생성. targetPrice·stopPrice 는 0.0 = 미입력. */
data class Position(
    val avgPrice: Double,
    val qty: Long,
    val targetPrice: Double = 0.0,
    val stopPrice: Double = 0.0,
)

/**
 * 종목 종합 코멘트 생성(② Claude 층).
 *
 * 원칙: **사실은 우리가 수집(시세·52주·PER·수급·가격흐름·재무·뉴스) → Claude 는 해석만.** 수치 날조 금지, 참고용.
 * 모드: DEFENSIVE(기본, 매매 지시 금지) / AGGRESSIVE(평단·신호 사실에 묶은 개별 종목 매매 판단까지 허용).
 * 비용·캐시: 같은 종목·같은 날·같은 모드는 1회만 생성하고 인메모리+파일 캐시로 공유(CLAUDE.md 비용 정책).
 *   - position 없음: (code,date,mode) 공유 캐시 — 전 유저 동일 코멘트.
 *   - position 있음: (code,date,mode,avgPrice,qty) 별도 캐시 — 포지션별 개인화. 공격 모드의
 *     평단 기반 매매 판단이 다른 사용자에게 새지 않게 분리(평단·수량은 장중 불변이라 재호출 churn 없음).
 */
class AnalysisService(
    private val kis: KisClient,
    private val toss: com.haky.edge.toss.TossClient,
    private val naver: NaverNewsClient,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val dart: DartClient,
    private val naverTargetPrice: NaverTargetPriceClient,
    private val targetPriceLog: TargetPriceLogService,
    private val macroImpact: MacroImpactService,
    private val krxShortSelling: KrxShortSellingClient,
    private val valuationBandSvc: ValuationBandService,
    private val peerValuationSvc: PeerValuationService,
    private val backtestSvc: BacktestService,
    private val eventSync: com.haky.edge.macro.EventSyncService,
    private val modelRouter: ModelRouter,
    private val slack: SlackClient = SlackClient(""),
    private val aiCommentChannel: String = "",
    private val notifyScope: CoroutineScope? = null,
    private val askDailyLimit: Int = 200,
    private val stanceLog: StanceLog = StanceLog(),
) {
    private data class Cached(val analysis: Analysis)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val fileCache = FileCache("analysis", Analysis.serializer())

    suspend fun analyze(code: String, position: Position? = null, mode: AnalysisMode = AnalysisMode.DEFENSIVE, force: Boolean = false): Analysis {
        // 주말 통합 거래일: 일요일은 토요일로 접어 토요일 분석을 재사용(데이터 동일). 평일·토요일은 당일.
        val today = effectiveMarketDate()
        // 캐시 키: 포지션 없으면 (code,date,mode) 전 유저 공유. 포지션 있으면 평단·수량·목표가·손절가까지 포함해
        // 사용자별 분리 — 공격 모드의 평단 기반 매매 판단이 다른 사용자에게 새지 않게.
        // 목표가·손절가도 키에 포함: facts에 반영되는데 캐시 적중으로 옛 코멘트가 나오는 불일치 방지.
        val key = buildKey(code, today, mode, position)
        var isRefresh = false
        if (force) {
            // 수동 새로고침 연타 가드: 마지막 생성 후 FORCE_COOLDOWN_MINUTES 안이면 캐시 반환.
            // (force=캐시 무조건 우회였는데, 연타 시 회당 풀 LLM 비용이 나가는 구멍이었음)
            val cached = cache[key]?.analysis ?: fileCache.get(key)?.also { cache[key] = Cached(it) }
            if (cached != null && !isPastMinutes(cached.generatedAt, FORCE_COOLDOWN_MINUTES)) {
                println("[ForceCooldown] $code: ${FORCE_COOLDOWN_MINUTES}분 내 수동 재생성 요청 → 캐시 반환")
                return cached
            }
        }
        if (!force) {
            val cached = cache[key]?.analysis ?: fileCache.get(key)?.also { cache[key] = Cached(it) }
            if (cached != null) {
                if (!shouldAutoRefresh(code, cached)) return cached
                // 가격 3% 이상 괴리 + 쿨다운 경과 → stale. 메모리 캐시 제거하고 재생성으로 진행.
                cache.remove(key)
                isRefresh = true
                println("[StaleDetect] $code: generatedPrice=${cached.generatedPrice} → stale, 재생성")
            }
        }

        // 사실 수집 — ask()와 공용인 collectFacts()가 병렬로 모은다.
        val t0 = System.currentTimeMillis()
        val cf = collectFacts(code, position)
        val facts = cf.facts
        // maxTokens 는 상한(목표 아님). 넉넉히 둬도 짧은 답은 짧고, 길면 ClaudeClient가 이어써 안 잘린다.
        val prompt = if (mode == AnalysisMode.AGGRESSIVE) AGGRESSIVE_PROMPT else DEFENSIVE_PROMPT
        // 모델 라우팅(기본): 최초 생성·브리핑=Opus, 수동 새로고침·급변 자동 재생성=Sonnet.
        // env OPUS_TRIGGERS 로 재조정 가능 — ModelRouter 참고.
        val trigger = when {
            force -> ModelRouter.ANALYSIS_MANUAL
            isRefresh -> ModelRouter.ANALYSIS_AUTO_REFRESH
            else -> ModelRouter.ANALYSIS_INITIAL
        }
        val model = modelRouter.modelFor(trigger)
        val t1 = System.currentTimeMillis()
        var rawComment = claude.complete(prompt, facts, maxTokens = 3500, modelOverride = model)
        // 스탠스 태그(F6)는 캐시·앱 노출 전에 본문에서 떼어낸다 — iOS 파싱 계약 불변.
        var (stance, cleanedComment) = parseStanceTag(rawComment)
        var (summary, comment) = parseSummaryFromComment(cleanedComment)
        // 요약(핵심 요약) 가드: 앱 카드 최상단에 노출되는 블록이라 여기만 엄격 검증.
        // facts에 없는 가격류(≥1000) 수치가 발견되면 같은 모델로 1회 재생성(실사고: 학습 프라이어
        // 주가 "53,700원"이 요약에 누출된 건). 본문 전체는 가공·라운드 오탐이 많아 기존대로 로그만.
        val suspicious = suspiciousSummaryPrices(facts, summary)
        if (suspicious.isNotEmpty()) {
            println("[NumberGuard] $code: 요약에 facts 외 가격류 ${suspicious.joinToString()} → 1회 재생성")
            rawComment = claude.complete(prompt, facts, maxTokens = 3500, modelOverride = model)
            val regen = parseStanceTag(rawComment)
            stance = regen.first
            val second = parseSummaryFromComment(regen.second)
            summary = second.first
            comment = second.second
            val still = suspiciousSummaryPrices(facts, summary)
            if (still.isNotEmpty()) println("[NumberGuard] $code: 재생성 후에도 의심 수치 잔존(${still.joinToString()}) — 로그만 남김")
        }
        println("[Timing] $code: claude=${System.currentTimeMillis() - t1}ms  total=${System.currentTimeMillis() - t0}ms")
        // 본문 환각 의심 수치는 로그로만(모니터링용). UI 경고는 띄우지 않는다 —
        // 단순 숫자 매칭이 "26만 주(2일 합산)"·"170만원대(손절 기준)" 같은 정당한
        // 가공·라운드 표현을 환각으로 오탐해 신뢰를 깎았기 때문. 일반 면책으로 충분.
        warnHallucinatedNumbers(code, facts, comment)

        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val analysis = Analysis(code = code, name = cf.name, date = today, comment = comment, summary = summary, generatedAt = now, generatedPrice = cf.quote.price.toDouble(), factsRichness = cf.richness, numberWarning = false)
        cache[key] = Cached(analysis)
        fileCache.put(key, analysis)
        // F6: 생성분만 스탠스 기록(캐시 적중은 위에서 이미 반환됨 — 중복 없음). "미상"도 기록(채점 제외용).
        stanceLog.append(StanceEntry(code, today, mode.name.lowercase(), stance, cf.quote.price.toDouble(), now, extractRegime(facts)))
        // S4: 공개 분석(포지션 없음)만 #ai코멘트 채널 아카이브. 포지션 포함은 개인정보라 skip.
        if (position == null && aiCommentChannel.isNotBlank() && notifyScope != null) {
            notifyScope.launch { slack.postMessage(aiCommentChannel, formatAiCommentMessage(analysis, mode, isRefresh)) }
        }
        return analysis
    }

    /**
     * 종목 자유 질문 Q&A(A1). analyze()와 같은 사실 데이터를 근거로 질문에만 답한다.
     * 캐시 없음 — 질문이 자유 텍스트라 공유 캐시가 의미 없고, 사용자가 직접 물을 때만 호출되므로
     * 비용은 질문 길이 제한(라우트) + 일일 상한(askDailyLimit)으로 방어.
     * 후속 질문은 앱이 이전 문답을 history로 되보내는 단순 구조(서버 무상태 유지).
     */
    suspend fun ask(
        code: String,
        question: String,
        position: Position? = null,
        mode: AnalysisMode = AnalysisMode.DEFENSIVE,
        history: List<AskTurn> = emptyList(),
    ): AskAnswer {
        tickAskLimit()
        val t0 = System.currentTimeMillis()
        val cf = collectFacts(code, position)
        val userMessage = renderAskUserMessage(cf.facts, history, question)
        // 대화형이라 지연 민감 + 볼륨 트리거 → 기본 Sonnet(ModelRouter.ASK). env OPUS_TRIGGERS로 조정.
        val model = modelRouter.modelFor(ModelRouter.ASK)
        val answer = claude.complete(askPrompt(mode), userMessage, maxTokens = 1500, modelOverride = model)
        println("[Timing] $code: ask total=${System.currentTimeMillis() - t0}ms")
        // 분석 본문과 동일 정책: facts 외 수치는 로그로만 모니터링(가공·라운드 오탐이 많아 UI 경고 없음).
        warnHallucinatedNumbers(code, cf.facts, answer)
        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return AskAnswer(code = code, name = cf.name, date = effectiveMarketDate(), question = question.trim(), answer = answer, generatedAt = now)
    }

    // Q&A 일일 카운터 — 자유 질문은 캐시가 없어 호출당 풀 LLM 비용이라 폭주(연타·공유 배포)를 상한으로 방어.
    private val askCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var askCountDate = ""

    /** 일일 질문 카운터 증가. 한도 초과 시 예외(라우트에서 429로 변환). 날짜는 KST 달력일. */
    private fun tickAskLimit() {
        val today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
        synchronized(askCount) {
            if (askCountDate != today) { askCountDate = today; askCount.set(0) }
        }
        if (askCount.incrementAndGet() > askDailyLimit) {
            askCount.decrementAndGet()
            throw AskDailyLimitException("오늘 질문 한도(${askDailyLimit}건)를 모두 사용했습니다. 내일 다시 질문해 주세요.")
        }
    }

    /** 사실 수집 결과 — analyze()(종합 코멘트)와 ask()(Q&A)가 같은 근거 데이터를 공유한다. */
    private data class CollectedFacts(
        val name: String,
        val quote: Quote,
        val facts: String,
        val richness: FactsRichness,
    )

    /** 사실 수집 — 독립 호출은 전부 병렬, name·quote 확보 후 의존 2건(뉴스·sectorRS) 합류. */
    private suspend fun collectFacts(code: String, position: Position?): CollectedFacts = coroutineScope {
        val t0 = System.currentTimeMillis()
        val quoteD          = async { kis.getPrice(code) }
        val nameD           = async { master.findByCode(code)?.name ?: code }
        val flowsD          = async { kis.getInvestorFlow(code, days = 5) }
        // 60개: 최근 20일은 가격흐름 서사용, 전체 60개는 MA20/60·기술적 앵커(매매 레벨 근거) 계산용.
        val barsD           = async { runCatching { kis.getDailyChart(code, bars = 60) }.getOrElse { emptyList() } }
        val financialsD     = async { runCatching { dart.getFinancials(code) }.getOrNull() }
        val consensusD      = async { runCatching { naverTargetPrice.getTargetPrice(code) }.getOrNull() }
        val shortSellingD   = async { runCatching { krxShortSelling.getShortSelling(code) }.getOrNull() }
        val valuationBandD  = async { runCatching { valuationBandSvc.getValuationBand(code) }.getOrNull() }
        val peerValD        = async { runCatching { peerValuationSvc.getPeerValuation(code) }.getOrNull() }
        val backtestD       = async { runCatching { backtestSvc.getBacktest(code) }.getOrNull() }
        val flowSensD       = async { runCatching { backtestSvc.getFlowSensitivity(code) }.getOrNull() }
        val quarterlyD      = async { runCatching { dart.getQuarterlyIncome(code) }.getOrNull() }
        // 상장주식수: 연환산(포워드) PER 계산용. inquire-price 재호출이라 가벼움(캐시 대상).
        val sharesD         = async { runCatching { kis.getListedShares(code) }.getOrNull() }
        val warningsD       = async { runCatching { toss.getActiveWarnings(code) }.getOrElse { emptyList() } }
        val calendarD       = async { runCatching { toss.getMarketCalendar() }.getOrNull() }

        // sectorChangeRate=quote.sectorName 필요, 뉴스=name 필요 → 두 await 후 병렬 합류
        val quote = quoteD.await()
        val name  = nameD.await()
        // 비슷한 뉴스가 도배되는 날이 많아, 넉넉히 받아 유사 건을 묶고 대표 N건만 쓴다.
        val rawNewsD        = async { runCatching { naver.search(name, display = 30) }.getOrElse { emptyList() } }
        val sectorRsD       = async { runCatching { macroImpact.sectorIndexChangeRate(code, name, quote.sectorName) }.getOrNull() }

        val flows           = flowsD.await()
        val bars            = barsD.await()
        val financials      = financialsD.await()
        val consensusTarget = consensusD.await()
        // 오늘 목표가를 스냅샷 기록(주가 병기 — 돌파 이력용)하고 추세·이벤트 집계 산출(스냅샷 부족 시 null).
        val targetTrend = runCatching { targetPriceLog.recordAndTrend(code, consensusTarget, quote.price) }.getOrNull()
        val targetEvents = runCatching { targetPriceLog.events(code) }.getOrNull()
        val shortSelling    = shortSellingD.await()
        val valuationBand   = valuationBandD.await()
        val peerValuation   = peerValD.await()
        val backtest        = backtestD.await()
        val flowSensitivity = flowSensD.await()
        val quarterlyIncome = quarterlyD.await()
        val listedShares    = sharesD.await()
        val sectorChangeRate = sectorRsD.await()
        val news            = dedupeNews(rawNewsD.await(), limit = 8)
        println("[Timing] $code: facts=${System.currentTimeMillis() - t0}ms")

        // 임박 거시 이벤트(향후 2주) — 파일 캐시 읽기라 가벼움. 없으면 null로 건너뜀.
        val eventsText = runCatching { eventSync.upcomingFactsText() }.getOrNull()
        // 투자유의(거래소 지정 시장경보·단기과열·정리매매·VI) — 발동 항목 라벨만. 없으면 null로 건너뜀.
        val warningsText = warningsD.await()
            .takeIf { it.isNotEmpty() }
            ?.let { "투자유의(거래소 지정, 현재 발동 중): " + it.joinToString(", ") { w -> w.label } }
        val calendar = calendarD.await()
        val facts = buildFacts(code, name, quote, bars, financials, flows, news, consensusTarget, targetTrend, targetEvents, sectorChangeRate, shortSelling, valuationBand, peerValuation, backtest, flowSensitivity, quarterlyIncome, listedShares, eventsText, warningsText, calendar, position)
        val richness = FactsRichness(
            newsCount = news.size,
            hasInvestorFlow = flows.isNotEmpty(),
            hasFinancials = financials != null,
            hasQuarterlyIncome = quarterlyIncome?.netIncome != null,
            hasShortSelling = shortSelling != null,
            hasValuationBand = valuationBand != null && valuationBand.yearsUsed > 0,
            hasBacktest = backtest?.signals?.any { it.confident } == true,
            hasFlowSensitivity = flowSensitivity?.items?.any { it.confident } == true,
        )
        CollectedFacts(name = name, quote = quote, facts = facts, richness = richness)
    }

    /** 사실 데이터를 Claude 입력용 한국어 텍스트로 정리. 여기 있는 값만 근거로 쓰라고 시스템 프롬프트가 지시. */
    private fun buildFacts(
        code: String,
        name: String,
        q: Quote,
        bars: List<DailyBar>,
        financials: FinancialSummary?,
        flows: List<InvestorFlow>,
        news: List<NewsCluster>,
        consensusTarget: Long?,
        targetTrend: TargetPriceTrend?,
        targetEvents: com.haky.edge.news.TargetPriceEvents?,
        sectorChangeRate: Double?,
        shortSelling: ShortSellingSummary?,
        valuationBand: ValuationBand?,
        peerValuation: PeerValuation?,
        backtest: Backtest?,
        flowSensitivity: FlowSensitivity?,
        quarterlyIncome: QuarterlyIncome?,
        listedShares: Long?,
        eventsText: String?,
        warningsText: String?,
        calendar: com.haky.edge.toss.MarketCalendar?,
        position: Position? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("종목: $name ($code)")
        val kst = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val totalMin = kst.hour * 60 + kst.minute
        val isWeekend = kst.dayOfWeek == DayOfWeek.SATURDAY || kst.dayOfWeek == DayOfWeek.SUNDAY
        // 캘린더가 있으면 공식 휴장 여부(주말+공휴일 모두 커버), 없으면 주말 휴리스틱 폴백.
        // 공휴일(평일)을 "장 중"으로 오표시하던 문제를 토스 개장 캘린더로 바로잡는다.
        val isHoliday = calendar?.isHoliday ?: isWeekend
        val nextDay = calendar?.nextBusinessDay?.takeIf { it.isNotBlank() }
        val marketStatus = when {
            isHoliday       -> "휴장 — 전일 종가 기준" + (nextDay?.let { " (다음 거래일 $it)" } ?: "")
            totalMin < 540  -> "장 전 (09:00 개장 전) — 전일 종가 기준"
            totalMin < 930  -> "장 중 (09:00~15:30)"
            else            -> "장 마감 후 (15:30 이후) — 당일 종가 확정"
        }
        sb.appendLine("현재 시장 상태: $marketStatus")
        sb.appendLine("현재가: ${q.price}원 (전일대비 ${q.change}, ${q.changeRate}%)")
        // 투자유의는 리스크 신호라 상단에 배치(거래소 지정 시장경보·단기과열·정리매매·VI).
        if (warningsText != null) sb.appendLine(warningsText)
        // 국면 판정(계산) — 리레이팅/디레이팅을 룰로 감지해 해석 프레임을 지정(C11).
        // 상단 배치: 아래 밸류·실적을 읽기 전에 프레임이 잡혀야 "과거 밴드 기준 고평가" 관성 판정을 막는다.
        RegimeDetector.detect(
            price = q.price,
            consensusTarget = consensusTarget,
            targetTrend = targetTrend,
            quarterlyYoyPct = quarterlyIncome?.yoyPct,
            perPercentile = valuationBand?.perPercentile,
        )?.let { regime ->
            sb.appendLine("국면 판정(계산): ${regime.label} — 근거: ${regime.signals.joinToString("; ")}")
        }
        if (sectorChangeRate != null) {
            val rs = q.changeRate - sectorChangeRate
            val label = when {
                rs > 0.5  -> "섹터 대비 강세"
                rs < -0.5 -> "섹터 대비 약세"
                else      -> "섹터 수준"
            }
            sb.appendLine(
                "섹터 대비 상대강도(RS): ${if (rs >= 0) "+" else ""}${"%.1f".format(rs)}%p" +
                    " (소속 섹터지수 ${if (sectorChangeRate >= 0) "+" else ""}${"%.2f".format(sectorChangeRate)}%, $label)"
            )
        }
        if (shortSelling != null) {
            sb.appendLine("공매도(KRX 데이터):")
            sb.appendLine("  최근 공매도 거래량: ${"%.0f".format(shortSelling.recentVolume.toDouble())}주 (${shortSelling.recentVolumeDate})")
            if (shortSelling.balance != null && shortSelling.balanceDate != null) {
                val balLine = StringBuilder("  공매도 잔고: ${"%.0f".format(shortSelling.balance.toDouble())}주 (${shortSelling.balanceDate} 확정)")
                if (shortSelling.balanceChangePct != null) {
                    val dir = when {
                        shortSelling.balanceChangePct > 1.0 -> "잔고 증가(하락 베팅 강화)"
                        shortSelling.balanceChangePct < -1.0 -> "잔고 감소(숏커버링·하락 베팅 약화)"
                        else -> "잔고 보합"
                    }
                    balLine.append(", 전일 대비 ${if (shortSelling.balanceChangePct >= 0) "+" else ""}${"%.1f".format(shortSelling.balanceChangePct)}% ($dir)")
                }
                sb.appendLine(balLine)
            } else {
                sb.appendLine("  공매도 잔고: 집계 중(T+2일 지연)")
            }
        }
        if (consensusTarget != null && consensusTarget > 0) {
            val upside = (consensusTarget - q.price).toDouble() / q.price * 100
            sb.appendLine(
                "애널리스트 컨센서스 목표주가: ${"%,d".format(consensusTarget)}원" +
                    " (현재가 대비 ${if (upside >= 0) "+" else ""}${"%.1f".format(upside)}%)"
            )
            if (targetTrend != null) {
                // 우리가 누적한 스냅샷 기준. 목표가가 오르는 추세면 밸류 상단권을 시장이 더 높이 본다는 신호.
                val signed = "${if (targetTrend.changePct >= 0) "+" else ""}${"%.1f".format(targetTrend.changePct)}%"
                sb.appendLine(
                    "  └ 컨센서스 목표가 추세: 최근 ${targetTrend.daySpan}일 ${targetTrend.direction} " +
                        "(${targetTrend.baselineDate} ${"%,d".format(targetTrend.baseline)}원 → 현재 ${"%,d".format(targetTrend.current)}원, " +
                        "$signed, 스냅샷 ${targetTrend.snapshotCount}개 기준)"
                )
            }
            // 목표가 이벤트 이력 — "매주 목표가가 올라간다"·"주가가 목표가를 뚫었다" 같은 리레이팅
            // 정황을 정량 사실로. 우리 스냅샷 누적 기준이라 초기엔 비어 있다가 시간이 지나며 차오른다.
            targetEventsLine(targetEvents)?.let { sb.appendLine(it) }
        }
        if (q.high52w > q.low52w && q.high52w > 0) {
            val pos = (q.price - q.low52w).toDouble() / (q.high52w - q.low52w) * 100
            val fromHigh = (q.price - q.high52w).toDouble() / q.high52w * 100
            sb.appendLine(
                "52주: 최고 ${q.high52w} / 최저 ${q.low52w} " +
                    "(현재 위치 ${"%.0f".format(pos)}%, 고점 대비 ${"%.1f".format(fromHigh)}%)"
            )
        }
        // PER/PBR 는 두 소스가 공존한다(KIS 시세 vs 아래 밴드 자체계산 — 이익 연도·주식수 기준이 달라
        // 값이 다를 수 있음). 라벨 없이 병기하면 모델이 날마다 다른 값을 집어 코멘트 PER이 튀는 실사고가
        // 있었음(6/15 43.4배 → 6/17 52.7배, 주가는 +2.7%). 라벨로 구분하고 일관 사용은 프롬프트가 지시.
        if (q.per > 0) sb.appendLine("PER(KIS 시세 기준) ${q.per} / PBR(KIS 시세 기준) ${q.pbr}")
        // 연환산(포워드) PER — 트레일링 PER은 작년 이익 기준이라 이익 급증 종목을 구조적으로
        // "고평가"로 보이게 한다. 최근 분기 누적을 연환산한 추정 PER을 병기해 그 편향을 사실로 보정.
        forwardPerLine(q.price, quarterlyIncome, listedShares)?.let { sb.appendLine(it) }
        if (valuationBand != null && valuationBand.yearsUsed > 0) {
            // 적자 연도는 PER 히스토리에서 제외되므로(턴어라운드 종목) 표본이 적을 수 있다. 적으면 신뢰도 경고.
            val sampleNote = if (valuationBand.yearsUsed < 3)
                " ※ 표본 ${valuationBand.yearsUsed}년으로 적어(적자 연도 제외 등) 밴드 신뢰도 낮음 — 결론은 약하게, 참고만." else ""
            sb.appendLine("밸류에이션 히스토리 밴드(자체 계산: 현재가÷최근 연간 실적, 연도말 기준 과거 ${valuationBand.yearsUsed}년, 상장주식수 근사치):$sampleNote")
            if (valuationBand.perCurrent > 0 && valuationBand.perMax > 0) {
                sb.appendLine(
                    "  PER(자체 계산) 현재 ${"%.1f".format(valuationBand.perCurrent)}배 " +
                        "→ ${valuationBand.yearsUsed}년 밴드 " +
                        "[${"%.1f".format(valuationBand.perMin)}~${"%.1f".format(valuationBand.perMax)}배], " +
                        "중앙 ${"%.1f".format(valuationBand.perMedian)}배 " +
                        "(${valuationBand.perLabel})"
                )
            }
            if (valuationBand.pbrCurrent > 0 && valuationBand.pbrMax > 0) {
                sb.appendLine(
                    "  PBR(자체 계산) 현재 ${"%.2f".format(valuationBand.pbrCurrent)}배 " +
                        "→ ${valuationBand.yearsUsed}년 밴드 " +
                        "[${"%.2f".format(valuationBand.pbrMin)}~${"%.2f".format(valuationBand.pbrMax)}배], " +
                        "중앙 ${"%.2f".format(valuationBand.pbrMedian)}배 " +
                        "(${valuationBand.pbrLabel})"
                )
            }
            sb.appendLine("  ※ KIS 시세 기준과 자체 계산 기준은 산식이 달라 값이 다를 수 있음. 밴드 위치를 논할 땐 자체 계산 값만, 단순 수준 언급엔 한 기준만 골라 일관되게 쓸 것.")
        }
        if (peerValuation != null && (peerValuation.per != null || peerValuation.pbr != null)) {
            // 동종(같은 사업) 대비 상대 위치. 역사 밴드(자기 과거)와 다른 축 — 리레이팅 국면에서 특히 유효.
            sb.appendLine("동종(${peerValuation.clusterLabel}) 상대 밸류 — peer ${peerValuation.peerCount}개 중앙값 대비:")
            peerValuation.per?.let { m ->
                sb.appendLine(
                    "  PER 현재 ${"%.1f".format(m.current)}배 vs 동종 중앙값 ${"%.1f".format(m.peerMedian)}배 " +
                        "(${if (m.diffPct >= 0) "+" else ""}${"%.0f".format(m.diffPct)}%, ${m.label})"
                )
            }
            peerValuation.pbr?.let { m ->
                sb.appendLine(
                    "  PBR 현재 ${"%.2f".format(m.current)}배 vs 동종 중앙값 ${"%.2f".format(m.peerMedian)}배 " +
                        "(${if (m.diffPct >= 0) "+" else ""}${"%.0f".format(m.diffPct)}%, ${m.label})"
                )
            }
        }
        sb.appendLine("거래량: ${q.volume}")

        // 최근 가격 흐름 서사(일봉 계산) — "상한가 두 번 치고 며칠째 급락" 같은 흐름을 사실로 제공.
        // 서사는 최근 20일로 한정(60일 전체는 서사가 늘어짐), 앵커 계산은 아래에서 60일 전체 사용.
        priceActionSummary(bars.take(20))?.let { sb.appendLine().append(it) }

        // 기술적 앵커 — 공격 모드가 진입·손절 레벨을 "지어내지 않고" 여기 있는 값에 묶도록 사실로 제공.
        // (실사고: facts에 레벨이 없어 "310,000~320,000원 분할 진입" 같은 창작 레벨이 나갔음)
        technicalAnchorsText(bars)?.let { sb.appendLine().append(it) }

        // 회사 재무(DART 연간) — 급등락이 펀더멘털 성장에 근거하는지 판단할 근거.
        financialSummaryText(financials)?.let { sb.appendLine().append(it) }
        quarterlyIncomeText(quarterlyIncome)?.let { sb.appendLine().append(it) }

        if (flows.isNotEmpty()) {
            sb.appendLine("수급(일별 순매수 수량, +매수/-매도):")
            flows.forEach {
                sb.appendLine("  ${it.date} 외국인 ${it.foreign} / 기관 ${it.institution} / 개인 ${it.individual}")
            }
        }
        backtestText(backtest)?.let { sb.appendLine().append(it) }
        flowSensitivityText(flowSensitivity)?.let { sb.appendLine().append(it) }
        if (news.isNotEmpty()) {
            sb.appendLine("최근 뉴스(유사 기사는 묶음, '외 N건'=같은 이슈가 그만큼 쏟아졌다는 관심도 신호. 날짜 주의 — 오래된 기사를 오늘 재료처럼 쓰지 말 것):")
            news.forEach { c ->
                val more = if (c.count > 1) " (유사 외 ${c.count - 1}건)" else ""
                val dateLabel = newsDateLabel(c.item.publishedAt)?.let { ", $it" } ?: ""
                sb.appendLine("  - [${c.item.source}$dateLabel] ${c.item.title}$more")
                if (c.item.description.isNotBlank()) {
                    sb.appendLine("    요약: ${c.item.description}")
                }
            }
        }
        // 임박 거시 이벤트(향후 2주) — 이 종목·업종 변동성에 영향 줄 예정 일정.
        if (eventsText != null) sb.appendLine().append(eventsText)

        if (position != null) {
            val currentPrice = q.price.toDouble()
            val pnlRate = if (position.avgPrice > 0)
                (currentPrice - position.avgPrice) / position.avgPrice * 100 else 0.0
            val pnlAmt = (currentPrice - position.avgPrice) * position.qty
            sb.appendLine()
            sb.appendLine("내 포지션 (실제 보유 데이터):")
            sb.appendLine(
                "  평단가: ${position.avgPrice.toLong()}원, 보유수량: ${position.qty}주"
            )
            sb.appendLine(
                "  평가손익: ${if (pnlAmt >= 0) "+" else ""}${"%.0f".format(pnlAmt)}원" +
                    " (${"%.1f".format(pnlRate)}%)"
            )
            if (position.targetPrice > 0) {
                val toTarget = (position.targetPrice - currentPrice) / currentPrice * 100
                sb.appendLine(
                    "  목표가: ${position.targetPrice.toLong()}원" +
                        " (현재가 대비 ${if (toTarget >= 0) "+" else ""}${"%.1f".format(toTarget)}%)"
                )
            }
            if (position.stopPrice > 0) {
                val toStop = (position.stopPrice - currentPrice) / currentPrice * 100
                sb.appendLine(
                    "  손절가: ${position.stopPrice.toLong()}원" +
                        " (현재가 대비 ${if (toStop >= 0) "+" else ""}${"%.1f".format(toStop)}%)"
                )
            }
        }
        return sb.toString()
    }

    /**
     * 일봉(최신일이 앞)에서 최근 가격 흐름을 사람이 읽는 서사로 요약.
     * 고점 대비 낙폭·급등(상한가 수준 포함)·연속 등락을 사실로만 적는다(해석은 Claude 몫).
     */
    private fun priceActionSummary(bars: List<DailyBar>): String? {
        if (bars.size < 2) return null
        val closes = bars.map { it.close }
        val cur = closes.first()
        // 일별 등락률 = (당일종가 - 전일종가)/전일종가. rates[0] 이 가장 최근일.
        val rates = closes.zipWithNext { day, prev ->
            if (prev > 0) (day - prev).toDouble() / prev * 100 else 0.0
        }

        // 최근 고점 대비 낙폭 + 저점 대비 반등폭 — 둘 다 항상 병기한다. 고점 프레임만 주면
        // 모든 종목이 "고점에서 -x%"라는 하락 앵커로만 서술되는 비대칭이 생긴다(편향 리뷰 P2).
        val highIdx = closes.indices.minByOrNull { -closes[it] } ?: 0
        val high = closes[highIdx]
        val drawdown = if (high > 0) (cur - high).toDouble() / high * 100 else 0.0
        val lowIdx = closes.indices.minByOrNull { closes[it] } ?: 0
        val low = closes[lowIdx]
        val rebound = if (low > 0) (cur - low).toDouble() / low * 100 else 0.0

        // 가장 최근일 부호 기준 연속 등락 일수
        val firstSign = rates.firstOrNull()?.let { if (it > 0) 1 else if (it < 0) -1 else 0 } ?: 0
        var streak = 0
        if (firstSign != 0) {
            for (r in rates) {
                val s = if (r > 0) 1 else if (r < 0) -1 else 0
                if (s == firstSign) streak++ else break
            }
        }
        // 연속 구간 누적 등락률
        val streakSum = rates.take(streak).sum()

        // 급변 이벤트는 상승·하락 대칭으로 센다 — 급등만 세면 "하루 -20% 폭락 후 반등"의
        // 폭락이 서사에서 사라지는 상승 편향이 생긴다(편향 리뷰 P2).
        val limitUps = rates.count { it >= 29.0 }  // 상한가 수준(+30% 제한 근처)
        val surges = rates.count { it in 15.0..29.0 } // 상한가는 아니지만 급등
        val limitDowns = rates.count { it <= -29.0 } // 하한가 수준
        val plunges = rates.count { it in -29.0..-15.0 } // 하한가는 아니지만 급락

        val sb = StringBuilder()
        sb.appendLine("최근 ${bars.size}거래일 가격 흐름:")
        sb.appendLine(
            "  최근 고점 ${high}원(약 ${highIdx}거래일 전) 대비 현재 ${"%.1f".format(drawdown)}%" +
                " / 최근 저점 ${low}원(약 ${lowIdx}거래일 전) 대비 ${if (rebound >= 0) "+" else ""}${"%.1f".format(rebound)}%"
        )
        val moves = buildList {
            if (limitUps > 0) add("상한가 수준(+29% 이상) 급등 ${limitUps}회")
            if (surges > 0) add("+15~29% 급등 ${surges}회")
            if (limitDowns > 0) add("하한가 수준(-29% 이하) 급락 ${limitDowns}회")
            if (plunges > 0) add("-15~29% 급락 ${plunges}회")
            if (streak >= 2) add("최근 ${streak}거래일 연속 ${if (firstSign > 0) "상승" else "하락"}(누적 ${"%.1f".format(streakSum)}%)")
        }
        if (moves.isNotEmpty()) sb.appendLine("  " + moves.joinToString(", "))
        return sb.toString()
    }

    /**
     * 기술적 앵커(일봉 계산, 최신일이 앞) — 매매 레벨 제시의 사실 근거.
     * 최근 20일 저점/고점 + MA20 + MA60(표본 60개 있을 때만). 판단 없이 값만.
     */
    private fun technicalAnchorsText(bars: List<DailyBar>): String? {
        if (bars.size < 20) return null
        val closes = bars.map { it.close }
        val recent20 = closes.take(20)
        val low20 = recent20.min()
        val high20 = recent20.max()
        val ma20 = recent20.average()
        val ma60 = if (closes.size >= 60) closes.take(60).average() else null
        val sb = StringBuilder()
        sb.appendLine("기술적 앵커(레벨 제시용 사실 값, 종가 기준):")
        sb.appendLine("  최근 20거래일 저점 ${low20}원 / 고점 ${high20}원")
        sb.append("  20일 이동평균 ${"%,.0f".format(ma20)}원")
        if (ma60 != null) sb.append(", 60일 이동평균 ${"%,.0f".format(ma60)}원")
        sb.appendLine()
        return sb.toString()
    }

    /** 네이버 pubDate(RFC-1123, 예 "Mon, 15 Jun 2026 14:30:00 +0900") → "6/15". 파싱 실패 시 null(라벨 생략). */
    private fun newsDateLabel(publishedAt: String): String? = runCatching {
        val dt = ZonedDateTime.parse(publishedAt.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        "${dt.monthValue}/${dt.dayOfMonth}"
    }.getOrNull()

    /**
     * 백테스트(신호별 익일 적중률)를 Claude 입력용 텍스트로. 신뢰 가능한(confident) 신호만 적는다.
     * 표본이 작고 특정 기간 한정이라는 한계를 명시해 과신을 막는다.
     */
    private fun backtestText(b: Backtest?): String? {
        if (b == null) return null
        val confident = b.signals.filter { it.confident && it.n > 0 }
        if (confident.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine(
            "검증된 신호(이 종목 최근 ${b.tradingDays}거래일 실측, " +
                "평소 익일 상승확률 ${b.baselineWinRate}%·평균 ${"%.2f".format(b.baselineAvgReturn)}%):"
        )
        confident.forEach { s ->
            val edgeSign = if (s.edge >= 0) "+" else ""
            sb.appendLine(
                "  ${s.signal}일(n=${s.n}): 익일 상승확률 ${s.winRate}% / 평균 ${"%.2f".format(s.avgReturn)}%" +
                    " (평소 대비 $edgeSign${"%.2f".format(s.edge)}%p)"
            )
        }
        sb.appendLine("  ※ 과거 표본 통계일 뿐 미래 보장 아님. 승률과 평균이 어긋나면 소수 급등/급락일이 평균을 끌어당긴 것.")
        return sb.toString()
    }

    /** 수급-가격 민감도(Pearson 상관)를 Claude 입력용 텍스트로. confident 항목만. */
    private fun flowSensitivityText(fs: FlowSensitivity?): String? {
        if (fs == null) return null
        val confident = fs.items.filter { it.confident }
        if (confident.isEmpty()) return null
        val sb = StringBuilder()
        sb.appendLine("수급-가격 민감도(이 종목 수급 규모와 당일 등락률 Pearson 상관, 과거 표본):")
        confident.forEach { c ->
            val rSign = if (c.r >= 0) "+" else ""
            sb.appendLine("  ${c.investor}(n=${c.n}): r=$rSign${c.r}, ${c.label}")
        }
        sb.appendLine("  ※ 상관이 강할수록 해당 주체 수급이 이 종목 당일 가격을 함께 끌어올리거나 내리는 경향. 과거 ${fs.items.firstOrNull()?.n ?: 0}거래일 한정, 미래 보장 아님.")
        return sb.toString()
    }

    /**
     * 연간 재무 요약을 사람이 읽는 텍스트로(단위 억원, 전년比 YoY 포함).
     * 매출·영업이익·순이익 중 있는 것만 적는다. 전부 없으면 null.
     */
    private fun financialSummaryText(f: FinancialSummary?): String? {
        if (f == null) return null
        val lines = buildList {
            financeLine("매출액", f.revenue, f.revenuePrev)?.let { add(it) }
            financeLine("영업이익", f.operatingProfit, f.operatingProfitPrev)?.let { add(it) }
            financeLine("당기순이익", f.netIncome, f.netIncomePrev)?.let { add(it) }
        }
        if (lines.isEmpty()) return null
        val basis = if (f.consolidated) "연결" else "별도"
        val sb = StringBuilder()
        sb.appendLine("회사 재무(DART $basis 사업보고서 ${f.fiscalYear}년, 단위 억원):")
        lines.forEach { sb.appendLine("  $it") }
        return sb.toString()
    }

    /** 분기 실적 방향 — "2026년 1분기 누적 순이익 X억 (전년 동기 대비 +Y%, 개선)" 한 줄. */
    private fun quarterlyIncomeText(q: QuarterlyIncome?): String? {
        if (q == null) return null
        val ni = q.netIncome ?: return null
        val niEok = ni / 100_000_000
        val yoy = q.yoyPct
        val direction = when {
            yoy == null -> ""
            yoy > 10    -> " (실적 개선)"
            yoy < -10   -> " (실적 악화)"
            else         -> " (전년 동기와 유사)"
        }
        val yoyText = if (yoy != null) ", 전년 동기 대비 ${if (yoy >= 0) "+" else ""}${"%.1f".format(yoy)}%$direction" else ""
        return "${q.label} 누적 순이익: ${"%,d".format(niEok)}억$yoyText\n"
    }

    /** "매출액 1,234억 (전년 1,000억, YoY +23.4%)" 형태. 당기 없으면 null. */
    private fun financeLine(label: String, cur: Long?, prev: Long?): String? {
        if (cur == null) return null
        val curEok = cur / 100_000_000
        val sb = StringBuilder("$label ${"%,d".format(curEok)}억")
        if (prev != null && prev != 0L) {
            val prevEok = prev / 100_000_000
            val yoy = (cur - prev).toDouble() / kotlin.math.abs(prev) * 100
            sb.append(" (전년 ${"%,d".format(prevEok)}억, YoY ${if (yoy >= 0) "+" else ""}${"%.1f".format(yoy)}%)")
        }
        return sb.toString()
    }

    // ── 유사 뉴스 클러스터링 ───────────────────────────────────────────────
    // 같은 이슈로 도배된 기사를 한 건으로 묶되, 제목이 비슷해도 요약(description)에 유의미한
    // 추가 정보가 있으면 별건으로 둔다(사용자 요구). 최신순 입력 → 대표 limit 건 반환.

    private data class NewsCluster(val item: NewsItem, val count: Int)

    private class MutableCluster(
        val item: NewsItem,
        val titleTokens: Set<String>,
        val descTokens: Set<String>,
        var count: Int = 1,
    )

    private fun dedupeNews(items: List<NewsItem>, limit: Int): List<NewsCluster> {
        val reps = mutableListOf<MutableCluster>()
        for (it in items) {
            val tTok = tokens(it.title)
            val dTok = tokens(it.description)
            // 제목이 비슷하고(>=0.5) 요약도 비슷하면(>=0.6) 같은 기사로 보고 묶는다.
            // 제목만 비슷하고 요약이 다르면 → 추가 정보가 있다고 보고 별건 유지.
            val match = reps.firstOrNull { r ->
                jaccard(tTok, r.titleTokens) >= 0.5 && jaccard(dTok, r.descTokens) >= 0.6
            }
            if (match != null) match.count++
            else reps.add(MutableCluster(it, tTok, dTok))
        }
        return reps.take(limit).map { NewsCluster(it.item, it.count) }
    }

    /** 한국어/영문/숫자 토큰 집합(2자 이상). 대소문자 무시. */
    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()

    /** 자카드 유사도. 둘 다 비면 1.0, 한쪽만 비면 0.0. */
    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /**
     * Claude 응답에서 facts에 없는 수치를 백엔드 로그로만 남긴다(개발 모니터링용).
     * 사용자 UI 경고는 띄우지 않는다 — 단순 숫자 매칭은 "26만 주(2일 합산)"·
     * "170만원대(손절 기준)"·퍼센트(계산값) 같은 정당한 가공/라운드 표현을 환각으로
     * 오탐해 신뢰를 깎기 때문. 진짜 환각은 이 로그를 보고 개발 중 사람이 판단한다.
     *
     * 매칭: 한국어 복합 단위(N조 M억, N만 M) 먼저 파싱해 단일 수치로 변환 후 facts와 ±5% 비교.
     */
    private fun warnHallucinatedNumbers(code: String, facts: String, comment: String) {
        val factsNums = extractNumbers(facts)
        val suspicious = extractNumbers(comment).filter { v ->
            factsNums.none { f ->
                val larger = maxOf(kotlin.math.abs(v), kotlin.math.abs(f))
                if (larger == 0.0) v == f
                else kotlin.math.abs(v - f) / larger <= 0.05
            }
        }
        if (suspicious.isNotEmpty()) {
            println("[NumberGuard] $code: facts 외 수치 ${suspicious.size}건(${suspicious.joinToString()})")
        }
    }

    /**
     * 캐시된 분석이 stale인지 확인. 두 조건 모두 충족해야 재생성:
     * ① 생성 시점 가격 대비 현재가 괴리 ≥ 3% (코멘트가 다른 가격 기준)
     * ② 마지막 생성으로부터 30분 이상 경과 (잦은 재생성 폭주 방지)
     */
    private suspend fun shouldAutoRefresh(code: String, cached: Analysis): Boolean {
        val genPrice = cached.generatedPrice?.takeIf { it > 0 } ?: return false
        val currentPrice = runCatching { kis.getPrice(code).price.toDouble() }.getOrElse { return false }
        val gap = kotlin.math.abs(currentPrice - genPrice) / genPrice
        if (gap < STALE_PRICE_THRESHOLD) return false
        return isPastMinutes(cached.generatedAt, COOLDOWN_MINUTES)
    }

    /** generatedAt(HH:mm, KST)에서 minutes 이상 경과했는지. 파싱 실패 시 true(재생성 허용). */
    private fun isPastMinutes(generatedAt: String, minutes: Long): Boolean {
        if (generatedAt.isBlank()) return true
        return try {
            val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            val genTime = java.time.LocalTime.parse(generatedAt, fmt)
            val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            java.time.Duration.between(genTime, now).toMinutes() >= minutes
        } catch (e: Exception) { true }
    }

    companion object {
        private const val STALE_PRICE_THRESHOLD = 0.03  // 3% 가격 괴리 시 stale
        private const val COOLDOWN_MINUTES = 30L         // 급변 자동 재생성 최소 간격(분)
        private const val FORCE_COOLDOWN_MINUTES = 5L    // 수동 새로고침(force) 연타 가드(분)

        /**
         * 연환산(포워드) PER 한 줄. 최근 분기 누적 순이익을 단순 연환산(1분기×4, 반기×2, 3분기×4/3)해
         * 추정 EPS를 만들고 현재가와 비교한다. 적자·데이터 부족·이상치(500배 초과)는 null(줄 생략).
         * 계절성 미반영 단순 추정이라는 한계를 텍스트에 명시 — 판단은 Claude/사용자 몫.
         */
        internal fun forwardPerLine(price: Long, quarterly: QuarterlyIncome?, listedShares: Long?): String? {
            val ni = quarterly?.netIncome ?: return null
            if (ni <= 0 || listedShares == null || listedShares <= 0 || price <= 0) return null
            val multiplier = when {
                quarterly.label.contains("1분기") -> 4.0
                quarterly.label.contains("반기")  -> 2.0
                quarterly.label.contains("3분기") -> 4.0 / 3.0
                else -> return null
            }
            val annualized = ni * multiplier
            val eps = annualized / listedShares
            if (eps <= 0) return null
            val forwardPer = price / eps
            if (forwardPer <= 0 || forwardPer > 500) return null
            val annualizedEok = (annualized / 100_000_000).toLong()
            return "연환산(포워드) PER: 약 ${"%.1f".format(forwardPer)}배 — ${quarterly.label} 누적 순이익을 연환산(${"%,d".format(annualizedEok)}억)한 추정치(단순 연환산, 계절성 미반영). 트레일링 PER과 차이가 크면 이익이 급변 중이라는 뜻."
        }

        /** 목표가 이벤트 이력 한 줄. 이벤트가 하나도 없으면 null(줄 생략). */
        internal fun targetEventsLine(e: com.haky.edge.news.TargetPriceEvents?): String? {
            if (e == null) return null
            if (e.raisesIn90d == 0 && e.cutsIn90d == 0 && e.breakthroughDays == 0) return null
            val parts = buildList {
                if (e.raisesIn90d > 0) add("상향 ${e.raisesIn90d}회")
                if (e.cutsIn90d > 0) add("하향 ${e.cutsIn90d}회")
                if (e.breakthroughDays > 0) add("주가≥목표가 관측 ${e.breakthroughDays}일")
                if (e.avgRaiseGapDays != null) add("돌파→상향 평균 ${e.avgRaiseGapDays}일")
            }
            return "  └ 목표가 이벤트(우리 스냅샷 ${e.snapshotCount}개 기준, 최근 90일): ${parts.joinToString(", ")}" +
                " — 상향이 반복되고 주가가 목표가를 앞서가면 애널리스트가 주가를 쫓아 올리는 리레이팅 정황."
        }

        /**
         * "### 핵심 요약" 블록 전용 가격류 환각 검사. 앱 카드 최상단이라 여기만 엄격하게 본다.
         * 요약 속 수치 중 ≥1000(원 단위 가격·금액·수량류)이면서 facts 어느 값과도 ±5% 안에 없는 것을 반환.
         * <1000(퍼센트·배수·건수·연도 일부)은 가공 표현 오탐이 많아 제외 — 연도(2026 등)는 facts에 항상 존재.
         */
        internal fun suspiciousSummaryPrices(facts: String, summary: String?): List<Double> {
            if (summary.isNullOrBlank()) return emptyList()
            val factsNums = extractNumbers(facts)
            return extractNumbers(summary)
                .filter { it >= 1000.0 }
                .filter { v ->
                    factsNums.none { f ->
                        val larger = maxOf(kotlin.math.abs(v), kotlin.math.abs(f))
                        if (larger == 0.0) v == f
                        else kotlin.math.abs(v - f) / larger <= 0.05
                    }
                }
                .sorted()
        }

        /**
         * 텍스트에서 수치 집합 추출. 한국어 복합 단위를 먼저 파싱해 단일 값으로 변환하고
         * 해당 부분을 텍스트에서 제거한 뒤 나머지 단순 숫자를 추출 — 부분 숫자 중복 방지.
         * 예) "13조 9,298억" → 139298.0 / "144만 3,170주" → 1443170.0
         */
        internal fun extractNumbers(text: String): Set<Double> {
            val result = mutableSetOf<Double>()
            var remaining = text

            val joEok = Regex("""([\d,]+)조\s*([\d,]+)억""")
            for (m in joEok.findAll(text)) {
                val jo = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                val eok = m.groupValues[2].replace(",", "").toLongOrNull() ?: continue
                result.add((jo * 10_000 + eok).toDouble())
            }
            remaining = joEok.replace(remaining, " ")

            val manN = Regex("""([\d,]+)만\s*([\d,]+)""")
            for (m in manN.findAll(remaining)) {
                val man = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                val rest = m.groupValues[2].replace(",", "").toLongOrNull() ?: continue
                result.add((man * 10_000 + rest).toDouble())
            }
            remaining = manN.replace(remaining, " ")

            val manOnly = Regex("""([\d,]+)만""")
            for (m in manOnly.findAll(remaining)) {
                val man = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                result.add((man * 10_000).toDouble())
            }
            remaining = manOnly.replace(remaining, " ")

            val numRegex = Regex("""-?[\d][\d,]*(?:\.\d+)?""")
            for (m in numRegex.findAll(remaining)) {
                val value = m.value.replace(",", "").toDoubleOrNull() ?: continue
                result.add(value)
            }
            return result
        }

        /** S4: #ai코멘트 채널 발송용 메시지 포맷. */
        internal fun formatAiCommentMessage(analysis: Analysis, mode: AnalysisMode, isRefresh: Boolean): String {
            val modeLabel = if (mode == AnalysisMode.AGGRESSIVE) "⚔️ 공격 모드" else "🛡️ 방어 모드"
            val refreshTag = if (isRefresh) " _(가격변동 재생성)_" else ""
            val header = "*${analysis.name}* (${analysis.code}) | $modeLabel | ${analysis.generatedAt}$refreshTag"
            val body = analysis.summary
                ?: analysis.comment.lines().take(4).joinToString("\n").take(300)
            return "$header\n\n📌 $body"
        }

        /** 캐시 키 빌더. 포지션 없으면 전 유저 공유, 있으면 사용자별 분리. */
        internal fun buildKey(code: String, today: String, mode: AnalysisMode, position: Position?): String =
            if (position == null) "$code:$today:${mode.name}"
            else "$code:$today:${mode.name}:${position.avgPrice.toLong()}:${position.qty}:${position.targetPrice.toLong()}:${position.stopPrice.toLong()}"

        /**
         * Claude 응답에서 `### 핵심 요약` 블록을 파싱해 (summary, body)로 분리.
         * 파싱 실패 시 (null, 원본) — 폴백 안전.
         */
        internal fun parseSummaryFromComment(raw: String): Pair<String?, String> {
            val headerRegex = Regex("""^\s*###\s*핵심\s*요약\s*$""", RegexOption.MULTILINE)
            val headerMatch = headerRegex.find(raw) ?: return Pair(null, raw)
            val afterHeader = raw.substring(headerMatch.range.last + 1).trimStart('\n')
            val blankLineIdx = afterHeader.indexOf("\n\n")
            return if (blankLineIdx >= 0) {
                val summary = afterHeader.substring(0, blankLineIdx).trim()
                val body = afterHeader.substring(blankLineIdx + 2).trim()
                Pair(summary.ifBlank { null }, body.ifBlank { raw })
            } else {
                Pair(null, raw)
            }
        }

        /**
         * F6: 응답 어디든 `[스탠스: 긍정|중립|부정]` 줄을 찾아 (스탠스, 태그 제거 본문) 반환.
         * 태그 없음/오형식 → ("미상", 원본) — 채점에서 제외되고 본문은 그대로 나간다(폴백 안전).
         * 여러 개면 마지막 것을 채택하고 전부 제거(모델이 실수로 중복 출력해도 앱에 안 샌다).
         */
        internal fun parseStanceTag(raw: String): Pair<String, String> {
            val lineRegex = Regex("""(?m)^\s*\[\s*스탠스\s*[:：]\s*(긍정|중립|부정)\s*]\s*$""")
            val matches = lineRegex.findAll(raw).toList()
            if (matches.isEmpty()) return "미상" to raw
            return matches.last().groupValues[1] to lineRegex.replace(raw, "").trimEnd()
        }

        /** facts 텍스트에서 국면 판정 라벨(짧은 형태)만 추출 — 스탠스 로그의 레짐별 집계용. */
        internal fun extractRegime(facts: String): String? =
            Regex("""국면 판정\(계산\): (리레이팅 국면|디레이팅 경계)""").find(facts)?.groupValues?.get(1)

        // ── 시스템 프롬프트(캐시 대상) ────────────────────────────────────────
        // 방어/공격 공통 규칙은 COMMON_RULES 한 곳에만 둔다 — 두 프롬프트가 80% 복제였던
        // 시절의 "한쪽만 고치는 드리프트"를 구조적으로 차단. **소제목** 형식은 iOS 카드
        // 파싱 계약이므로 C3(형식) 변경 시 앱 확인 필수.

        private val COMMON_RULES = """
            공통 규칙(반드시 지킬 것):
            C1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치를 절대 지어내지 마라.
            C2. 가독성: 한 단락엔 한 가지 주제만 담아라. 한 단락이 6문장을 넘으면 두 단락으로 쪼개라(예: 수급이 길면 "수급"과 "공매도·신호"로 분리). 분량을 늘리려 말을 늘이지 마라.
            C3. 형식: 불릿·번호 목록, --- 구분선, ~~취소선~~ 금지(흐르는 문장으로). 응답은 첫 글자부터 "### 핵심 요약"으로 시작하고 그 앞에 아무것도 쓰지 마라. 각 단락 첫 줄에 **소제목**만 굵게 넣고, 그 다음 줄부터 본문. 소제목과 본문 사이, 단락과 단락 사이는 빈 줄 하나(\n\n).
            C4. 핵심 수치는 **굵게** 표시해 눈에 띄게 하라 — 등락률·주가·승률·목표주가·PER/PBR 등 독자가 기억할 숫자. 단, 문장 전체를 굵게 하지 말고 숫자/짧은 구절만.
            C5. 어려운 금융 영어(모멘텀, 밸류에이션, 멀티플 등)는 가급적 한국어로 바꾸거나 괄호 설명을 붙여라.
            C6. 뉴스는 종목과 무관한 것이 섞일 수 있다. 관련 있어 보이는 것만 쓰고 억지로 연결하지 마라. 뉴스마다 날짜가 붙어 있다 — 3일 이상 지난 기사를 오늘의 재료처럼 서술하지 말고 "지난 ~일 보도된" 식으로 시점을 구분하라.
            C7. "현재 시장 상태"에 따라 가격 표현을 다르게 써라:
                - "장 중": "현재 XXX원에 거래 중", "XXX원 수준" 등 실시간 표현
                - "장 마감 후": "XXX원에 마감", "당일 XXX원으로 마감" 등 종가 표현
                - "장 전" 또는 "휴장": "전일 XXX원에 마감" 등 전일 종가 표현
            C8. PER/PBR은 "KIS 시세 기준"과 "자체 계산" 두 값이 있을 수 있다(산식이 달라 값이 다름). 밴드 위치를 논할 땐 자체 계산 값을 쓰고, 그 외엔 한 기준만 골라 일관되게 쓰라. 두 값을 섞어 쓰지 마라.
            C9. "검증된 신호"·"수급-가격 민감도"는 "이 종목 과거 통계상" 같은 한정을 붙여라. 표본 n이 15 미만인 신호는 "참고 수준"이라고 명시하고 "의미 있게 높다/유의미하다" 같은 통계적 확신 표현을 쓰지 마라. n은 항상 함께 표기하라. 승률과 평균이 어긋나면 그 의미(소수 급등/급락일이 평균을 끌어당긴 영향)도 짚어라. 절대 미래 수익을 단정하지 마라.
            C10. 밸류에이션 해석 균형(중요): "역사적 상단권"이나 높은 PER/PBR 백분위를 그 자체로 "비싸다·매수하지 마라"로 단정하지 마라.
                역사 밴드는 한 가지 축일 뿐이다. 반드시 아래와 함께 저울질해서 판단하라:
                - 실적 방향: "회사 재무"·분기 실적의 매출·이익이 구조적으로 늘고 있으면, 시장이 더 높은 멀티플을 주는 리레이팅(이익은 그대로인데 주가가 앞서감)이거나, 이익이 점프해 과거 밴드 자체가 무의미해진 경우일 수 있다.
                - 컨센서스 목표주가: 현재가 대비 상승여력이 크면 역사적 상단이어도 시장은 더 위를 본다는 뜻이다. "컨센서스 목표가 추세"(최근 상향/하향)가 있으면 그 방향도 함께 보라 — 꾸준히 상향 중이면 상단권을 시장이 계속 높여 잡는 강한 신호, 하향 추세면 경계 신호다.
                - 동종 상대 밸류: "동종 상대 밸류" 섹션이 있으면 같은 업종 경쟁사 중앙값과 비교한 위치다. 역사적 상단권이어도 동종 대비 낮으면 상대적으로 싼 편이고(리레이팅 국면에서 특히 의미 있다), 동종 대비 높으면 그 프리미엄을 정당화할 실적·성장 근거가 있는지 짚어라.
                - 표본·신뢰도: 밴드에 "신뢰도 낮음/표본 적음" 표시가 있으면 밴드 결론을 약하게 다뤄라.
                업종을 미리 단정하지 말고(반도체든 조선·방산이든) 위 사실로 판단하라. 반대로 역사적으로 싸 보여도 이익이 꺾이는 중이면 함정일 수 있다는 양방향 경계를 똑같이 적용하라.
            C11. "국면 판정(계산)" 항목이 있으면 해석 프레임을 그에 맞춰라(항목이 없으면 이 규칙은 무시):
                - "리레이팅 국면": 과거 밴드·트레일링 PER 기준의 고평가 단정을 하지 마라. 대신 이익 추정 속도(연환산 PER·분기 YoY)와 주가 속도 중 무엇이 빠른지, 목표가 추세, 수급으로 판단하라. 단 낙관 단정도 금지 — "이익이 계속 따라와야 유지되는 가격"이라는 조건을 반드시 명시하고, 이익 추정이 꺾이면 되돌림 폭이 클 수 있다는 리스크를 함께 짚어라.
                - "디레이팅 경계": 역사적으로 싸 보여도 이익·목표가가 꺾이는 중이면 밸류 함정일 수 있음을 우선 짚어라. "싸니까 기회"로 시작하지 마라.
                국면 판정은 룰 계산 결과이며 근거가 함께 적혀 있다 — 근거를 코멘트에서 재확인하며 쓰되, 판정과 실제 데이터가 어긋나 보이면 판정을 무시하지 말고 그 어긋남 자체를 짚어라.
        """.trimIndent()

        // 말미 재강조 — 거대 프롬프트에서 지시 준수율은 서두보다 말미가 높다.
        // 실사고(학습 프라이어 주가 "53,700원"이 핵심 요약에 누출) 재발 방지의 1차 방어선.
        private val FINAL_GUARD = """
            마지막 경고(가장 중요): 너의 학습 지식 속 이 회사의 주가·시가총액·목표주가·과거 실적 수치는 전부 낡아서 틀렸다. 절대 사용하지 마라. 가격·수치는 위 "사실 데이터"에서 그대로 복사해서만 쓴다. 특히 ### 핵심 요약에 쓰는 모든 수치는 사실 데이터에 존재하는 값이어야 한다 — 요약을 쓰기 전에 각 수치가 사실 데이터에 있는지 스스로 확인하라.
        """.trimIndent()

        // 방어 모드 고유 부분. 사실/해석 분리·매매 지시 금지.
        private val DEFENSIVE_CORE = """
            너는 한국 주식 투자 보조 앱의 분석 어시스턴트다.
            독자는 주식에 관심이 있지만 전문 트레이더가 아닌 일반인이다. 전문 용어를 쓸 때는 괄호 안에 짧게 뜻을 달아준다.
            예) PER(주가가 1년 순이익의 몇 배인지), PBR(주가가 순자산의 몇 배인지), 수급(외국인·기관·개인 중 누가 사고 파는지), 컨센서스 목표주가(여러 증권사 애널리스트가 제시한 평균 목표값)

            응답 형식(반드시):
            맨 앞에 아래 블록을 넣어라:

            ### 핵심 요약
            (2~3문장 산문. 이 종목의 핵심 판단과 주요 수치를 포함. 불릿 없이 흐르는 문장으로.)

            그 다음 빈 줄 하나 후에 소제목 단락들을 이어라.

            방어 모드 규칙(반드시 지킬 것):
            D1. 다음 주제들을 자연스럽게 이어지는 단락으로 풀어라. 있는 재료만 다루고, 없는 주제는 건너뛴다:
               - 최근 흐름: 주가가 왜 이렇게 움직였나 — 뉴스와 가격 흐름을 연결해 "무슨 일이 있었는지".
               - 실적 확인: 그 움직임이 일시적 기대인지 실제 실적 변화인지 — "회사 재무"가 있으면 매출·이익 추세와 비교.
               - 수급: 외국인·기관이 사고 파는 추세, 공매도, "검증된 신호"를 묶어 누가 어느 방향인지.
               - 밸류·목표가: PER/PBR·밸류에이션 밴드·컨센서스 목표주가로 "지금 이 가격이 어느 수준인지".
               - 종합: 지금 이 종목을 어떻게 봐야 하는지 마무리.
               소제목 예: **최근 흐름**, **실적 확인**, **수급**, **공매도·신호**, **밸류·목표가**, **종합**
            D2. 사실과 해석을 구분해서, 근거 없는 단정은 "~로 보인다", "~일 수 있다"처럼 신중하게.
            D3. "지금 사라/팔라"처럼 매매를 지시하지 마라.
            D4. "내 포지션" 섹션이 있으면 평단가 기준 현재 손익과 목표가까지 남은 거리를 마지막 단락에 자연스럽게 녹여준다.
            D5. "임박 거시 이벤트" 섹션이 있으면, 그 일정이 이 종목·업종에 어떤 변동성이나 방향을 줄 수 있는지
                종합 단락에서 한두 문장으로만 짚어라(별도 소제목 만들지 말 것). 날짜·이벤트명은 사실대로 쓰되
                영향은 "~결과에 따라 ~할 수 있다"는 조건부로. 이 종목·업종과 분명히 관련된 일정만 다루고,
                무관하면 억지로 엮지 말고 통째로 건너뛰어라. 일정 자체로 주가를 단정하지 마라.
            D6. "투자유의" 항목이 있으면 거래소가 실제로 지정한 리스크 신호이므로 반드시 짚어라(없으면 언급하지 마라):
                - "투자위험"·"정리매매"는 강한 경고 — 상장폐지·급락 위험을 신중하지만 분명하게 알려라.
                - "투자경고"·"단기과열"은 과열·변동성 확대 신호로, 단기 급등 뒤 되돌림 위험을 짚어라.
                - "정적VI"·"동적VI"는 변동성 완화장치 발동 이력으로, 주가 변동성이 큰 상태라는 참고 정보로만 다뤄라.
                종합 단락에서 한두 문장으로 녹이되, 이 사실로 매매를 지시하지는 마라.
        """.trimIndent()

        // F6 스탠스 태그 — COMMON_RULES(내용 규칙)와 분리된 후처리 지시. 백엔드가 파싱 후 본문에서
        // 제거하므로 요약/소제목 계약(C3)과 충돌하지 않는다. 맨 끝(FINAL_GUARD 뒤)에 붙인다.
        private val STANCE_TAG_INSTRUCTION = """
            출력 후처리 지시(본문 내용·톤과 무관):
            응답의 맨 마지막 줄에 [스탠스: 긍정] [스탠스: 중립] [스탠스: 부정] 중 하나를 정확히 그 형식으로 한 줄 추가하라.
            이는 이 종목에 대한 너의 종합 시각 요약이며 시스템이 별도 기록용으로 떼어간다.
            본문을 먼저 평소대로 완성하고, 그 결론을 태그로 옮기기만 하라 — 태그 때문에 본문 방향을 억지로 정하지 마라.
            판단이 서지 않거나 혼조면 중립을 선택하라. 태그 뒤에는 아무것도 쓰지 마라.
        """.trimIndent()

        private val DEFENSIVE_PROMPT = DEFENSIVE_CORE + "\n\n" + COMMON_RULES + "\n\n" + FINAL_GUARD + "\n\n" + STANCE_TAG_INSTRUCTION

        // 공격 모드 고유 부분. 공통 사실·환각가드(COMMON_RULES·FINAL_GUARD) 위에서 개별 종목
        // 매매 판단까지 단호하게 허용한다(macro-impact 공격 모드는 섹터 레벨까지만 — 여기는 개별 종목 가능).
        // 모든 레벨(진입·손절·차익)은 facts의 "기술적 앵커" 등 실제 값에 묶는다(A3) — 창작 레벨 실사고 재발 방지.
        private val AGGRESSIVE_CORE = """
            너는 한국 주식 투자 보조 앱의 분석 어시스턴트다.
            지금은 "공격적 모드" — 사용자가 이 종목에 대한 단호한 매매 판단을 직접 요청해 켠 상태다.
            에두르거나 "~수도 있다"식 양비론으로 빠지지 말고, 사실에 묶인 결론을 자신감 있게 딱 잘라 말하라.
            독자는 전문 트레이더가 아닌 일반인이다. 전문 용어를 쓸 때는 괄호 안에 짧게 뜻을 달아준다.
            예) PER(주가가 1년 순이익의 몇 배인지), PBR(주가가 순자산의 몇 배인지), 수급(외국인·기관·개인 중 누가 사고 파는지)

            응답 형식(반드시):
            맨 앞에 아래 블록을 넣어라:

            ### 핵심 요약
            (2~3문장 산문. 핵심 판단 + 주요 수치 + 권고 스탠스를 포함. 불릿 없이 흐르는 문장으로.)

            그 다음 빈 줄 하나 후에 소제목 단락들을 이어라.

            공격 모드 규칙(반드시 지킬 것):
            A1. 다음 주제들을 자연스럽게 이어지는 단락으로 풀어라. 있는 재료만 다루고, 없는 주제는 건너뛴다:
               - 최근 흐름: 주가가 왜 이렇게 움직였나 — 뉴스와 가격 흐름을 연결.
               - 실적 확인: 그 움직임이 일시적 기대인지 실제 실적 변화인지 — "회사 재무"가 있으면 추세와 비교.
               - 수급·신호: 외국인·기관 방향, 공매도, "검증된 신호"를 묶어 누가 어느 방향인지.
               - 밸류·목표가: PER/PBR·밸류에이션 밴드·컨센서스 목표주가로 지금 이 가격이 어느 수준인지.
               - 종합·액션: 위 사실을 종합해 "지금 이 종목을 어떻게 할지" 단호하게 못박아 마무리.
               소제목 예: **최근 흐름**, **실적 확인**, **수급·신호**, **밸류·목표가**, **종합·액션**
            A2. (방어 모드와 핵심 차이) 개별 종목 매매 판단을 허용한다. 단 모든 매매 판단은 반드시 계산된 사실에 묶어라 — 평단 대비 손익, 손절·목표가까지 거리, 검증된 신호 승률, 밸류 밴드 위치, 수급 방향:
               - 보유 중이면: 평단 대비 손익과 손절·목표가 거리를 근거로 — "비중을 줄여라 / 분할 추가 여력을 써라 / 손절 라인을 지켜라 / 목표가에서 차익 실현하라".
               - 미보유면: 밸류 밴드·신호·수급을 근거로 — "이 가격대는 분할 진입 구간이다 / 지금은 관망하고 ~선까지 기다려라".
            A3. 레벨 앵커(중요): 진입·손절·차익 실현 가격을 제시할 때는 반드시 사실 데이터에 있는 가격(기술적 앵커의 최근 20거래일 저점/고점·20/60일 이동평균, 52주 고저, 최근 고점, 컨센서스 목표주가, 본인 목표가/손절가) 중에서 골라 쓰고, 괄호로 어떤 값인지 명시하라. 예: "손절은 **320,400원**(최근 20거래일 저점) 이탈 시". 사실 데이터에 없는 임의 가격대를 만들어내지 마라. 앵커로 쓸 값이 마땅치 않으면 가격 레벨 제시를 생략하고 조건("외국인 순매수 전환 확인 후" 등)으로 대신하라.
            A4. 결과를 확정하지 마라("반드시 오른다/떨어진다" 금지). 스탠스는 단호하게 내되, 미래 단정은 하지 마라.
            A5. "임박 거시 이벤트" 섹션이 있으면, 그 일정이 이 종목·업종 변동성에 미칠 영향을 종합·액션 단락에서
                짚고 대응까지 못박아라(예: "D-2 FOMC 전까지 비중을 늘리지 말고 결과를 보고 대응하라"). 날짜·이벤트명은
                사실대로, 결과 방향은 조건부로. 이 종목·업종과 무관한 일정은 억지로 엮지 말고 건너뛰어라.
            A6. "투자유의" 항목이 있으면 거래소가 지정한 리스크 신호다(없으면 언급 금지). 종합·액션 단락에서 스탠스에 반영하라:
                "투자위험"·"정리매매"는 강한 경고 — 신규 진입을 말리거나 보유분 리스크 관리(비중 축소·손절 라인)를 분명히 권하라.
                "투자경고"·"단기과열"·"VI"는 과열·변동성 신호로, 추격 매수를 경계하고 되돌림을 기다리라는 식으로 액션에 묶어라. 단 결과를 단정하지는 마라.
        """.trimIndent()

        private val AGGRESSIVE_PROMPT = AGGRESSIVE_CORE + "\n\n" + COMMON_RULES + "\n\n" + FINAL_GUARD + "\n\n" + STANCE_TAG_INSTRUCTION

        // ── Q&A(ask) — 분석 코멘트와 달리 "### 핵심 요약"/소제목 형식 계약이 없다 ──────────
        // 원칙은 동일(사실 한정·통계 한정·시장상태 표현)하되, "질문에 정면으로·짧게"가 형식의 전부.

        const val ASK_MAX_QUESTION_CHARS = 300
        private const val ASK_MAX_HISTORY_TURNS = 3
        private const val ASK_HISTORY_ANSWER_CHARS = 600

        /**
         * Q&A user 메시지 조립: 사실 데이터 + (있으면) 이전 문답 + 이번 질문.
         * 이전 문답은 최근 ASK_MAX_HISTORY_TURNS개만, 답변은 앞 ASK_HISTORY_ANSWER_CHARS자로 잘라
         * 토큰 폭주를 막는다(사실 데이터만으로도 이미 크다).
         */
        internal fun renderAskUserMessage(facts: String, history: List<AskTurn>, question: String): String = buildString {
            append(facts.trimEnd())
            appendLine()
            val turns = history.takeLast(ASK_MAX_HISTORY_TURNS)
            if (turns.isNotEmpty()) {
                appendLine()
                appendLine("이전 문답(맥락 참고용, 오래된 것부터):")
                turns.forEach { t ->
                    appendLine("  Q: ${t.question.trim()}")
                    val a = t.answer.trim()
                    appendLine("  A: ${if (a.length > ASK_HISTORY_ANSWER_CHARS) a.take(ASK_HISTORY_ANSWER_CHARS) + " …(생략)" else a}")
                }
            }
            appendLine()
            append("사용자 질문: ${question.trim()}")
        }

        private val ASK_CORE = """
            너는 한국 주식 투자 보조 앱의 Q&A 어시스턴트다.
            사용자가 아래 "사실 데이터"가 딸린 특정 종목에 대해 자유 질문을 했다. 질문은 user 메시지 맨 끝 "사용자 질문:"에 있다.
            독자는 주식에 관심이 있지만 전문 트레이더가 아닌 일반인이다. 전문 용어를 쓸 때는 괄호 안에 짧게 뜻을 달아준다.
            예) PER(주가가 1년 순이익의 몇 배인지), 수급(외국인·기관·개인 중 누가 사고 파는지)

            Q&A 규칙(반드시 지킬 것):
            Q1. 질문에 정면으로 답하라 — 첫 문장이 곧 답이어야 한다. 묻지 않은 주제(수급·밸류 등)를 관성적으로 훑지 말고, 답의 근거가 되는 사실만 골라 써라.
            Q2. 분량은 질문 크기에 맞춰라. 한두 문단이 기본, 길어도 네 문단. 소제목·불릿·번호 목록·구분선 없이 흐르는 문장으로만 쓴다.
            Q3. 사실 데이터에 근거가 없는 질문(예: 미공개 계약 조건, 데이터에 없는 기간의 시세)은 "제공된 데이터로는 알 수 없다"고 먼저 말하고, 데이터에 있는 인접한 사실로 답할 수 있는 부분까지만 답하라. 학습 지식으로 빈칸을 메꾸지 마라.
            Q4. 핵심 수치는 **굵게** 표시하라. 뉴스를 근거로 쓸 땐 날짜를 확인해 3일 이상 지난 기사를 오늘의 재료처럼 서술하지 마라.
            Q5. "현재 시장 상태"에 맞는 가격 표현을 써라 — 장 중="현재 XXX원에 거래 중", 장 마감 후="XXX원에 마감", 장 전·휴장="전일 XXX원에 마감".
            Q6. "검증된 신호"·"수급-가격 민감도" 통계는 "이 종목 과거 통계상" 한정을 붙이고 표본 n을 함께 표기하라. n이 15 미만이면 "참고 수준"이라고 명시하라. 미래 수익을 단정하지 마라.
            Q7. "이전 문답"이 있으면 그 맥락을 이어서 답하라. 단 이전 답변과 사실 데이터가 충돌하면 사실 데이터를 우선하고, 필요하면 정정하라.
            Q8. PER/PBR은 "KIS 시세 기준"과 "자체 계산" 두 값이 있을 수 있다(산식이 달라 값이 다름). 한 답변 안에서는 한 기준만 골라 일관되게 쓰고, 밴드 위치를 논할 땐 자체 계산 값을 써라. 두 값을 섞어 쓰지 마라.
            Q9. "국면 판정(계산)" 항목이 있으면 그 프레임에 맞춰 답하라 — "리레이팅 국면"이면 과거 밴드·트레일링 PER 기준의 고평가 단정을 하지 말되 "이익이 계속 따라와야 유지되는 가격"이라는 조건을 명시하고, "디레이팅 경계"면 싸 보여도 밸류 함정 가능성을 우선 짚어라(항목이 없으면 이 규칙은 무시).
        """.trimIndent()

        private val ASK_DEFENSIVE_STANCE = """
            스탠스(방어 모드): "지금 사라/팔라"처럼 매매를 지시하지 마라. 매매 판단을 묻는 질문에는 판단에 필요한 사실(밸류 위치, 수급 방향, 평단 대비 손익, 목표가까지 거리)을 정리해 주고, 어느 쪽 근거가 더 두터운지까지만 짚어라. 결론은 "~로 보인다" 수준으로 신중하게.
        """.trimIndent()

        private val ASK_AGGRESSIVE_STANCE = """
            스탠스(공격 모드 — 사용자가 단호한 판단을 직접 요청해 켠 상태): 매매 판단을 물으면 에두르지 말고 사실에 묶인 결론을 딱 잘라 말하라. 진입·손절·차익 실현 가격을 제시할 때는 반드시 사실 데이터에 있는 값(기술적 앵커의 20거래일 저점/고점·20/60일 이동평균, 52주 고저, 최근 고점, 컨센서스 목표주가, 본인 목표가/손절가) 중에서 골라 쓰고 괄호로 어떤 값인지 명시하라. 앵커로 쓸 값이 마땅치 않으면 가격 레벨 대신 조건("외국인 순매수 전환 확인 후" 등)으로 답하라. 스탠스는 단호하되 결과("반드시 오른다/떨어진다")는 단정하지 마라.
        """.trimIndent()

        // FINAL_GUARD의 Q&A 변형 — "### 핵심 요약" 언급 대신 답 전체 검증으로.
        private val ASK_FINAL_GUARD = """
            마지막 경고(가장 중요): 너의 학습 지식 속 이 회사의 주가·시가총액·목표주가·과거 실적 수치는 전부 낡아서 틀렸다. 절대 사용하지 마라. 가격·수치는 위 "사실 데이터"에서 그대로 복사해서만 쓴다. 답을 보내기 전에 답 속 각 수치가 사실 데이터에 있는 값인지 스스로 확인하라.
        """.trimIndent()

        internal fun askPrompt(mode: AnalysisMode): String =
            ASK_CORE + "\n\n" +
                (if (mode == AnalysisMode.AGGRESSIVE) ASK_AGGRESSIVE_STANCE else ASK_DEFENSIVE_STANCE) +
                "\n\n" + ASK_FINAL_GUARD
    }
}
