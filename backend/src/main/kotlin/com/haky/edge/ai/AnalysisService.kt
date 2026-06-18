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

        // 사실 수집 — 독립 호출은 전부 병렬, name·quote 확보 후 의존 2건(뉴스·sectorRS) 합류.
        val t0 = System.currentTimeMillis()
        return coroutineScope {
            val quoteD          = async { kis.getPrice(code) }
            val nameD           = async { master.findByCode(code)?.name ?: code }
            val flowsD          = async { kis.getInvestorFlow(code, days = 5) }
            val barsD           = async { runCatching { kis.getDailyChart(code, bars = 20) }.getOrElse { emptyList() } }
            val financialsD     = async { runCatching { dart.getFinancials(code) }.getOrNull() }
            val consensusD      = async { runCatching { naverTargetPrice.getTargetPrice(code) }.getOrNull() }
            val shortSellingD   = async { runCatching { krxShortSelling.getShortSelling(code) }.getOrNull() }
            val valuationBandD  = async { runCatching { valuationBandSvc.getValuationBand(code) }.getOrNull() }
            val peerValD        = async { runCatching { peerValuationSvc.getPeerValuation(code) }.getOrNull() }
            val backtestD       = async { runCatching { backtestSvc.getBacktest(code) }.getOrNull() }
            val flowSensD       = async { runCatching { backtestSvc.getFlowSensitivity(code) }.getOrNull() }
            val quarterlyD      = async { runCatching { dart.getQuarterlyIncome(code) }.getOrNull() }

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
            // 오늘 목표가를 스냅샷 기록하고 과거 대비 상향/하향 추세를 산출(스냅샷 부족 시 null).
            val targetTrend = runCatching { targetPriceLog.recordAndTrend(code, consensusTarget) }.getOrNull()
            val shortSelling    = shortSellingD.await()
            val valuationBand   = valuationBandD.await()
            val peerValuation   = peerValD.await()
            val backtest        = backtestD.await()
            val flowSensitivity = flowSensD.await()
            val quarterlyIncome = quarterlyD.await()
            val sectorChangeRate = sectorRsD.await()
            val news            = dedupeNews(rawNewsD.await(), limit = 8)
            println("[Timing] $code: facts=${System.currentTimeMillis() - t0}ms")

            // 임박 거시 이벤트(향후 2주) — 파일 캐시 읽기라 가벼움. 없으면 null로 건너뜀.
            val eventsText = runCatching { eventSync.upcomingFactsText() }.getOrNull()
            val facts = buildFacts(code, name, quote, bars, financials, flows, news, consensusTarget, targetTrend, sectorChangeRate, shortSelling, valuationBand, peerValuation, backtest, flowSensitivity, quarterlyIncome, eventsText, position)
            // maxTokens 는 상한(목표 아님). 넉넉히 둬도 짧은 답은 짧고, 길면 ClaudeClient가 이어써 안 잘린다.
            val prompt = if (mode == AnalysisMode.AGGRESSIVE) AGGRESSIVE_PROMPT else DEFENSIVE_PROMPT
            // 모델 라우팅: force=수동 새로고침→Sonnet, isRefresh=급변 자동 재생성→Opus, 그 외=최초 생성→Opus.
            val trigger = when {
                force -> ModelRouter.ANALYSIS_MANUAL
                isRefresh -> ModelRouter.ANALYSIS_AUTO_REFRESH
                else -> ModelRouter.ANALYSIS_INITIAL
            }
            val t1 = System.currentTimeMillis()
            val rawComment = claude.complete(prompt, facts, maxTokens = 3500, modelOverride = modelRouter.modelFor(trigger))
            println("[Timing] $code: claude=${System.currentTimeMillis() - t1}ms  total=${System.currentTimeMillis() - t0}ms")
            val (summary, comment) = parseSummaryFromComment(rawComment)
            // 환각 의심 수치를 로그로만 남긴다(모니터링용). UI 경고는 더 이상 띄우지 않는다 —
            // 단순 숫자 매칭이 "26만 주(2일 합산)"·"170만원대(손절 기준)" 같은 정당한
            // 가공·라운드 표현을 환각으로 오탐해 신뢰를 깎았기 때문. 일반 면책으로 충분.
            warnHallucinatedNumbers(code, facts, comment)

            val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
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
            val analysis = Analysis(code = code, name = name, date = today, comment = comment, summary = summary, generatedAt = now, generatedPrice = quote.price.toDouble(), factsRichness = richness, numberWarning = false)
            cache[key] = Cached(analysis)
            fileCache.put(key, analysis)
            // S4: 공개 분석(포지션 없음)만 #ai코멘트 채널 아카이브. 포지션 포함은 개인정보라 skip.
            if (position == null && aiCommentChannel.isNotBlank() && notifyScope != null) {
                notifyScope.launch { slack.postMessage(aiCommentChannel, formatAiCommentMessage(analysis, mode, isRefresh)) }
            }
            analysis
        }
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
        sectorChangeRate: Double?,
        shortSelling: ShortSellingSummary?,
        valuationBand: ValuationBand?,
        peerValuation: PeerValuation?,
        backtest: Backtest?,
        flowSensitivity: FlowSensitivity?,
        quarterlyIncome: QuarterlyIncome?,
        eventsText: String?,
        position: Position? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("종목: $name ($code)")
        val kst = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val totalMin = kst.hour * 60 + kst.minute
        val isWeekend = kst.dayOfWeek == DayOfWeek.SATURDAY || kst.dayOfWeek == DayOfWeek.SUNDAY
        val marketStatus = when {
            isWeekend       -> "주말(휴장) — 전일 종가 기준"
            totalMin < 540  -> "장 전 (09:00 개장 전) — 전일 종가 기준"
            totalMin < 930  -> "장 중 (09:00~15:30)"
            else            -> "장 마감 후 (15:30 이후) — 당일 종가 확정"
        }
        sb.appendLine("현재 시장 상태: $marketStatus")
        sb.appendLine("현재가: ${q.price}원 (전일대비 ${q.change}, ${q.changeRate}%)")
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
        }
        if (q.high52w > q.low52w && q.high52w > 0) {
            val pos = (q.price - q.low52w).toDouble() / (q.high52w - q.low52w) * 100
            val fromHigh = (q.price - q.high52w).toDouble() / q.high52w * 100
            sb.appendLine(
                "52주: 최고 ${q.high52w} / 최저 ${q.low52w} " +
                    "(현재 위치 ${"%.0f".format(pos)}%, 고점 대비 ${"%.1f".format(fromHigh)}%)"
            )
        }
        if (q.per > 0) sb.appendLine("PER ${q.per} / PBR ${q.pbr}")
        if (valuationBand != null && valuationBand.yearsUsed > 0) {
            // 적자 연도는 PER 히스토리에서 제외되므로(턴어라운드 종목) 표본이 적을 수 있다. 적으면 신뢰도 경고.
            val sampleNote = if (valuationBand.yearsUsed < 3)
                " ※ 표본 ${valuationBand.yearsUsed}년으로 적어(적자 연도 제외 등) 밴드 신뢰도 낮음 — 결론은 약하게, 참고만." else ""
            sb.appendLine("밸류에이션 히스토리 밴드(연도말 기준 과거 ${valuationBand.yearsUsed}년, 상장주식수 근사치):$sampleNote")
            if (valuationBand.perCurrent > 0 && valuationBand.perMax > 0) {
                sb.appendLine(
                    "  PER 현재 ${"%.1f".format(valuationBand.perCurrent)}배 " +
                        "→ ${valuationBand.yearsUsed}년 밴드 " +
                        "[${"%.1f".format(valuationBand.perMin)}~${"%.1f".format(valuationBand.perMax)}배], " +
                        "중앙 ${"%.1f".format(valuationBand.perMedian)}배 " +
                        "(${valuationBand.perLabel})"
                )
            }
            if (valuationBand.pbrCurrent > 0 && valuationBand.pbrMax > 0) {
                sb.appendLine(
                    "  PBR 현재 ${"%.2f".format(valuationBand.pbrCurrent)}배 " +
                        "→ ${valuationBand.yearsUsed}년 밴드 " +
                        "[${"%.2f".format(valuationBand.pbrMin)}~${"%.2f".format(valuationBand.pbrMax)}배], " +
                        "중앙 ${"%.2f".format(valuationBand.pbrMedian)}배 " +
                        "(${valuationBand.pbrLabel})"
                )
            }
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
        priceActionSummary(bars)?.let { sb.appendLine().append(it) }

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
            sb.appendLine("최근 뉴스(유사 기사는 묶음, '외 N건'=같은 이슈가 그만큼 쏟아졌다는 관심도 신호):")
            news.forEach { c ->
                val more = if (c.count > 1) " (유사 외 ${c.count - 1}건)" else ""
                sb.appendLine("  - [${c.item.source}] ${c.item.title}$more")
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

        // 최근 고점 대비 현재 낙폭(되돌림 폭)
        val highIdx = closes.indices.minByOrNull { -closes[it] } ?: 0
        val high = closes[highIdx]
        val drawdown = if (high > 0) (cur - high).toDouble() / high * 100 else 0.0

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

        val limitUps = rates.count { it >= 29.0 }  // 상한가 수준(+30% 제한 근처)
        val surges = rates.count { it in 15.0..29.0 } // 상한가는 아니지만 급등

        val sb = StringBuilder()
        sb.appendLine("최근 ${bars.size}거래일 가격 흐름:")
        sb.appendLine(
            "  최근 고점 ${high}원(약 ${highIdx}거래일 전) 대비 현재 ${"%.1f".format(drawdown)}%"
        )
        val moves = buildList {
            if (limitUps > 0) add("상한가 수준(+29% 이상) 급등 ${limitUps}회")
            if (surges > 0) add("+15~29% 급등 ${surges}회")
            if (streak >= 2) add("최근 ${streak}거래일 연속 ${if (firstSign > 0) "상승" else "하락"}(누적 ${"%.1f".format(streakSum)}%)")
        }
        if (moves.isNotEmpty()) sb.appendLine("  " + moves.joinToString(", "))
        return sb.toString()
    }

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
     * 텍스트에서 수치 집합 추출. 한국어 복합 단위를 먼저 파싱해 단일 값으로 변환하고
     * 해당 부분을 텍스트에서 제거한 뒤 나머지 단순 숫자를 추출 — 부분 숫자 중복 방지.
     * 예) "13조 9,298억" → 139298.0 / "144만 3,170주" → 1443170.0
     */
    private fun extractNumbers(text: String): Set<Double> {
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
        return isPastCooldown(cached.generatedAt)
    }

    private fun isPastCooldown(generatedAt: String): Boolean {
        if (generatedAt.isBlank()) return true
        return try {
            val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            val genTime = java.time.LocalTime.parse(generatedAt, fmt)
            val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            java.time.Duration.between(genTime, now).toMinutes() >= COOLDOWN_MINUTES
        } catch (e: Exception) { true }
    }

    companion object {
        private const val STALE_PRICE_THRESHOLD = 0.03  // 3% 가격 괴리 시 stale
        private const val COOLDOWN_MINUTES = 30L         // 재생성 최소 간격(분)

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

        // 방어 모드 시스템 프롬프트(캐시 대상). 사실/해석 분리·환각 가드·매매 지시 금지.
        private val DEFENSIVE_PROMPT = """
            너는 한국 주식 투자 보조 앱의 분석 어시스턴트다.
            독자는 주식에 관심이 있지만 전문 트레이더가 아닌 일반인이다. 전문 용어를 쓸 때는 괄호 안에 짧게 뜻을 달아준다.
            예) PER(주가가 1년 순이익의 몇 배인지), PBR(주가가 순자산의 몇 배인지), 수급(외국인·기관·개인 중 누가 사고 파는지), 컨센서스 목표주가(여러 증권사 애널리스트가 제시한 평균 목표값)

            응답 형식(반드시):
            맨 앞에 아래 블록을 넣어라:

            ### 핵심 요약
            (2~3문장 산문. 이 종목의 핵심 판단과 주요 수치를 포함. 불릿 없이 흐르는 문장으로.)

            그 다음 빈 줄 하나 후에 소제목 단락들을 이어라.

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치를 절대 지어내지 마라.
            2. 다음 주제들을 자연스럽게 이어지는 단락으로 풀어라. 있는 재료만 다루고, 없는 주제는 건너뛴다:
               - 최근 흐름: 주가가 왜 이렇게 움직였나 — 뉴스와 가격 흐름을 연결해 "무슨 일이 있었는지".
               - 실적 확인: 그 움직임이 일시적 기대인지 실제 실적 변화인지 — "회사 재무"가 있으면 매출·이익 추세와 비교.
               - 수급: 외국인·기관이 사고 파는 추세, 공매도, "검증된 신호"를 묶어 누가 어느 방향인지.
               - 밸류·목표가: PER/PBR·밸류에이션 밴드·컨센서스 목표주가로 "지금 이 가격이 어느 수준인지".
               - 종합: 지금 이 종목을 어떻게 봐야 하는지 마무리.
            3. 가독성 규칙(중요): 한 단락엔 한 가지 주제만 담아라. 한 단락이 6문장을 넘으면 두 단락으로 쪼개라(예: 수급이 길면 "수급"과 "공매도·신호"로 분리). 분량을 늘리려 말을 늘이지 말고, 길어질 땐 나눠서 읽기 쉽게 하라.
            4. 사실과 해석을 구분해서, 근거 없는 단정은 "~로 보인다", "~일 수 있다"처럼 신중하게.
            5. "지금 사라/팔라"처럼 매매를 지시하지 마라.
            6. 어려운 금융 영어(모멘텀, 밸류에이션, 멀티플 등)는 가급적 한국어로 바꾸거나 괄호 설명을 붙여라.
            7. 형식: 불릿·번호 목록, --- 구분선, ~~취소선~~ 금지(흐르는 문장으로). 각 단락 첫 줄에 **소제목**(예: **최근 흐름**, **실적 확인**, **수급**, **공매도·신호**, **밸류·목표가**, **종합**)만 굵게 넣고, 그 다음 줄부터 본문. 소제목과 본문 사이, 단락과 단락 사이는 빈 줄 하나(\n\n).
            8. 핵심 수치는 **굵게** 표시해 눈에 띄게 하라 — 등락률·주가·승률·목표주가·PER/PBR 등 독자가 기억할 숫자. 단, 문장 전체를 굵게 하지 말고 숫자/짧은 구절만.
            9. 뉴스는 종목과 무관한 것이 섞일 수 있다. 관련 있어 보이는 것만 쓰고 억지로 연결하지 마라.
            10. "내 포지션" 섹션이 있으면 평단가 기준 현재 손익과 목표가까지 남은 거리를 마지막 단락에 자연스럽게 녹여준다.
            11. "검증된 신호" 섹션이 있으면 수급 단락에서 활용하되, "이 종목 과거 통계상" 같은 한정을 붙이고 표본이 작을 수 있음을 신중하게 다뤄라. 승률과 평균이 다르면 그 의미(소수 급등일 영향)도 짚어준다. 절대 미래 수익을 단정하지 마라.
            12. "현재 시장 상태"에 따라 가격 표현을 다르게 써라:
                - "장 중": "현재 XXX원에 거래 중", "XXX원 수준" 등 실시간 표현
                - "장 마감 후": "XXX원에 마감", "당일 XXX원으로 마감" 등 종가 표현
                - "장 전" 또는 "주말(휴장)": "전일 XXX원에 마감" 등 전일 종가 표현
            13. "임박 거시 이벤트" 섹션이 있으면, 그 일정이 이 종목·업종에 어떤 변동성이나 방향을 줄 수 있는지
                종합 단락에서 한두 문장으로만 짚어라(별도 소제목 만들지 말 것). 날짜·이벤트명은 사실대로 쓰되
                영향은 "~결과에 따라 ~할 수 있다"는 조건부로. 이 종목·업종과 분명히 관련된 일정만 다루고,
                무관하면 억지로 엮지 말고 통째로 건너뛰어라. 일정 자체로 주가를 단정하지 마라.
            14. 밸류에이션 해석 균형(중요): "역사적 상단권"이나 높은 PER/PBR 백분위를 그 자체로 "비싸다·매수하지 마라"로 단정하지 마라.
                역사 밴드는 한 가지 축일 뿐이다. 반드시 아래와 함께 저울질해서 판단하라:
                - 실적 방향: "회사 재무"·분기 실적의 매출·이익이 구조적으로 늘고 있으면, 시장이 더 높은 멀티플을 주는 리레이팅(이익은 그대로인데 주가가 앞서감)이거나, 이익이 점프해 과거 밴드 자체가 무의미해진 경우일 수 있다.
                - 컨센서스 목표주가: 현재가 대비 상승여력이 크면 역사적 상단이어도 시장은 더 위를 본다는 뜻이다. "컨센서스 목표가 추세"(최근 상향/하향)가 있으면 그 방향도 함께 보라 — 목표가가 꾸준히 상향되는 중이면 상단권을 시장이 계속 높여 잡는 강한 신호이고, 하향 추세면 반대로 경계 신호다.
                - 동종 상대 밸류: "동종 상대 밸류" 섹션이 있으면 같은 업종 경쟁사 중앙값과 비교한 위치다. 역사적 상단권이어도 동종 대비 낮으면 상대적으로 싼 편이고(리레이팅 국면에서 특히 의미 있다), 동종 대비 높으면 그 프리미엄을 정당화할 실적·성장 근거가 있는지 짚어라.
                - 표본·신뢰도: 밴드에 "신뢰도 낮음/표본 적음" 표시가 있으면 밴드 결론을 약하게 다뤄라.
                업종을 미리 단정하지 말고(반도체든 조선·방산이든) 위 사실로 판단하라. 반대로 역사적으로 싸 보여도 이익이 꺾이는 중이면 함정일 수 있다는 양방향 경계를 똑같이 적용하라.
        """.trimIndent()

        // 공격 모드 시스템 프롬프트. 방어 모드와 같은 사실·환각가드 위에서, 개별 종목 매매 판단까지
        // 단호하게 허용한다(macro-impact 공격 모드는 섹터 레벨까지만 — 여기는 개별 종목 가능).
        // 단 모든 판단은 반드시 계산된 사실(평단 손익·손절/목표가 거리·신호 승률·밸류 위치·수급)에 묶고,
        // 결과 단정·환각은 계속 금지. iOS 카드가 **소제목** 섹션을 파싱하므로 소제목 형식은 방어 모드와 동일 유지.
        private val AGGRESSIVE_PROMPT = """
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

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치를 절대 지어내지 마라.
               모든 매매 판단은 반드시 계산된 사실에 묶어라 — 평단 대비 손익, 손절·목표가까지 거리, 검증된 신호 승률, 밸류 밴드 위치, 수급 방향.
            2. 다음 주제들을 자연스럽게 이어지는 단락으로 풀어라. 있는 재료만 다루고, 없는 주제는 건너뛴다:
               - 최근 흐름: 주가가 왜 이렇게 움직였나 — 뉴스와 가격 흐름을 연결.
               - 실적 확인: 그 움직임이 일시적 기대인지 실제 실적 변화인지 — "회사 재무"가 있으면 추세와 비교.
               - 수급·신호: 외국인·기관 방향, 공매도, "검증된 신호"를 묶어 누가 어느 방향인지.
               - 밸류·목표가: PER/PBR·밸류에이션 밴드·컨센서스 목표주가로 지금 이 가격이 어느 수준인지.
               - 종합·액션: 위 사실을 종합해 "지금 이 종목을 어떻게 할지" 단호하게 못박아 마무리.
            3. (방어 모드와 핵심 차이) 개별 종목 매매 판단을 허용한다. 단 반드시 포지션·신호 사실에 묶을 것:
               - 보유 중이면: 평단 대비 손익과 손절·목표가 거리를 근거로 — "비중을 줄여라 / 분할 추가 여력을 써라 / 손절 라인을 지켜라 / 목표가에서 차익 실현하라".
               - 미보유면: 밸류 밴드·신호·수급을 근거로 — "이 가격대는 분할 진입 구간이다 / 지금은 관망하고 ~선까지 기다려라".
            4. 결과를 확정하지 마라("반드시 오른다/떨어진다" 금지). 스탠스는 단호하게 내되, 미래 단정은 하지 마라.
            5. "검증된 신호" 섹션이 있으면 "이 종목 과거 통계상" 같은 한정을 붙이고 표본이 작을 수 있음을 신중히 다뤄라. 승률과 평균이 어긋나면 그 의미(소수 급등일 영향)도 짚어라. 미래 수익을 단정하지 마라.
            6. 가독성: 한 단락엔 한 주제만. 한 단락이 6문장을 넘으면 두 단락으로 쪼개라. 어려운 금융 영어는 한국어로 바꾸거나 괄호 설명을 붙여라.
            7. 형식: 불릿·번호 목록, --- 구분선, ~~취소선~~ 금지(흐르는 문장으로). 각 단락 첫 줄에 **소제목**(예: **최근 흐름**, **실적 확인**, **수급·신호**, **밸류·목표가**, **종합·액션**)만 굵게 넣고, 그 다음 줄부터 본문. 소제목과 본문 사이, 단락과 단락 사이는 빈 줄 하나(\n\n).
            8. 핵심 수치는 **굵게** 표시하라 — 등락률·주가·승률·목표주가·PER/PBR·평단 대비 손익 등. 문장 전체를 굵게 하지 말고 숫자/짧은 구절만.
            9. 뉴스는 종목과 무관한 것이 섞일 수 있다. 관련 있어 보이는 것만 쓰고 억지로 연결하지 마라.
            10. "현재 시장 상태"에 따라 가격 표현을 다르게 써라:
                - "장 중": "현재 XXX원에 거래 중", "XXX원 수준" 등 실시간 표현
                - "장 마감 후": "XXX원에 마감", "당일 XXX원으로 마감" 등 종가 표현
                - "장 전" 또는 "주말(휴장)": "전일 XXX원에 마감" 등 전일 종가 표현
            11. "임박 거시 이벤트" 섹션이 있으면, 그 일정이 이 종목·업종 변동성에 미칠 영향을 종합·액션 단락에서
                짚고 대응까지 못박아라(예: "D-2 FOMC 전까지 비중을 늘리지 말고 결과를 보고 대응하라"). 날짜·이벤트명은
                사실대로, 결과 방향은 조건부로. 이 종목·업종과 무관한 일정은 억지로 엮지 말고 건너뛰어라.
            12. 밸류에이션 해석 균형(중요): "역사적 상단권"·높은 PER/PBR 백분위를 그 자체로 "비싸다·매수하지 마라"로 단정하지 마라.
                역사 밴드는 한 축일 뿐 — 실적 방향(매출·이익이 구조적으로 늘면 리레이팅이거나 이익 점프로 과거 밴드가 무의미), 컨센서스 목표가 상승여력·상향/하향 추세, 동종 상대 밸류(같은 업종 대비 낮음/높음), 밴드 표본·신뢰도를 함께 저울질해 스탠스를 정하라.
                업종을 미리 단정하지 말고(반도체·조선·방산 불문) 사실로 판단하라. 단호한 결론을 내되, 역으로 싸 보여도 이익이 꺾이면 함정일 수 있다는 경계도 적용하라.
        """.trimIndent()
    }
}
