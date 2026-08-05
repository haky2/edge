package com.haky.edge.slack

import com.haky.edge.ai.ValuationBandService
import com.haky.edge.util.writeTextAtomic
import com.haky.edge.dart.DartClient
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.master.StockMaster
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 신호 알림 푸시(S3) — 관심종목을 순회하며 의미 있는 신호가 *새로* 발생했을 때만 Slack 채널에 알린다.
 * 무료 푸시 대체의 메인 가치([[edge-slack-slices]] S3). 앱은 pull, 이건 백엔드가 능동 push.
 *
 * 신호 4종(전부 기존 데이터 재사용, 새 API 0):
 *  1. 연속 순매수 — 외국인/기관 N일 연속(/investor).
 *  2. 신규 공시 — 중요 유형(수주·증자·자사주·합병·실적 등) DART 공시(/dart).
 *  3. 밸류밴드 저평가 진입 — 역사적 PER 하위 구간 진입(valuation-band).
 *  4. 수급 전환점(F4) — 5일 연속 순매도 후 첫 순매수(반대 방향 대칭). #1은 추세 확인, 이건 변곡 감지.
 *     수급이 가격을 실제로 움직이는 종목만(flow-sensitivity r≥0.3·confident) — 소음 필터.
 *
 * 도배 방지(핵심) — 신호마다 디듀프 패턴이 다르고, 상태를 {DATA_DIR}/signal_state.json에 영속한다
 *   (`.cache/`(날짜 자동 stale)와 달리 삭제 대상 아님. GCS edge-app-data 볼륨이라 콜드 스타트에도 유지):
 *   - 순매수: key="code:FOREIGN" → 마지막 발화 streak 시작일. 같은 streak는 1번, 끊겼다 재시작하면 재발화.
 *   - 공시:   key="DISC:code"    → 이미 알린 rceptNo 목록(최근 N개). 새 접수번호만 발화.
 *   - 밸류:   key="VALUE:code"   → 마지막 본 perLabel. "저평가 아님→저평가" 전이일 때만 발화(상태는 매번 갱신).
 *   - 전환:   key="REV:code:주체:방향" → 마지막 발화일. 같은 방향 전환은 7일 내 재알림 금지.
 *
 * 발송: 발생 신호를 종류별 섹션으로 묶어 한 메시지로 한 번만 발송(종목마다 따로 보내 도배하지 않는다).
 *   채널/토큰 미설정(로컬)이면 SlackClient가 no-op — 평가 로직은 그대로 돌아 라우트 응답으로 검증 가능.
 */
class SignalService(
    private val slack: SlackClient,
    private val kis: KisClient,
    private val master: StockMaster,
    private val dart: DartClient,
    private val valuationBand: ValuationBandService,
    private val signalChannel: String,
    // 스캔 대상 관심종목을 스캔 시점에 조회(앱 등록 기반 동적 목록). 고정 리스트가 아니다.
    private val watchlist: () -> List<String>,
    private val backtest: com.haky.edge.ai.BacktestService? = null, // F4 필터·근거용(없으면 전환 신호 skip)
    private val earningsPreview: com.haky.edge.ai.EarningsPreviewService? = null, // F3 리뷰용(없으면 skip)
    private val premortem: com.haky.edge.ai.PremortemService? = null, // F5 무효화 조건 감시(없으면 skip)
    private val targetLog: com.haky.edge.news.TargetPriceLogService? = null, // F5 target_cut 감시용 목표가 이력(없으면 skip)
    private val rebalance: com.haky.edge.ai.RebalanceService? = null, // R2 비중 점검 신호(없으면 skip)
    private val investorHistory: com.haky.edge.kis.InvestorHistoryLog? = null, // 수급 아카이브(없으면 skip)
    private val signalFiredLog: SignalFiredLog? = null, // N3-a 발화 로그(없으면 skip)
    private val guidance: com.haky.edge.ai.GuidanceService? = null, // N2 가이던스 수집(없으면 skip)
) {
    private val dataDir = File(System.getenv("DATA_DIR") ?: ".data").also { it.mkdirs() }
    private val stateFile = File(dataDir, "signal_state.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    /** 한 번의 스캔 결과(라우트 응답·로깅용). fired = 이번에 새로 발화한 신호 설명들. */
    @Serializable
    data class ScanResult(
        val scanned: Int,
        val fired: List<String>,
        val posted: Boolean,
    )

    private data class FlowSignal(val code: String, val name: String, val type: FlowType, val streak: Int, val startDate: String, val cumulative: Long)
    private data class DisclosureSignal(val code: String, val name: String, val title: String, val url: String, val rceptNo: String)
    private data class ValuationSignal(val code: String, val name: String, val perCurrent: Double, val percentile: Int)
    private data class ReversalSignal(val code: String, val name: String, val type: FlowType, val toBuy: Boolean, val prevStreak: Int, val todayQty: Long, val backtestNote: String?)
    private data class EarningsReviewSignal(
        val code: String, val name: String,
        val review: com.haky.edge.ai.EarningsPreviewService.EarningsReview,
        val guidance: com.haky.edge.ai.Guidance? = null, // N2 — "회사가 말한 것"(수집 실패·한도 초과면 null)
    )
    private data class PremortemSignal(val code: String, val name: String, val reason: String, val descriptions: List<String>)
    private data class RebalanceSignal(val text: String)

    internal enum class FlowType(val label: String) { FOREIGN("외국인"), INSTITUTION("기관") }

    /** 수급 전환 감지 결과(4a 순수 함수 출력). toBuy=순매도→순매수 전환. */
    internal data class Reversal(val toBuy: Boolean, val prevStreak: Int, val todayQty: Long)

    /** 관심종목 순회 → 4종 신호 평가 → 디듀프 통과분만 한 메시지로 발송. */
    suspend fun scan(): ScanResult {
        val codes = watchlist()
        val state = loadState()
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
        val flowSignals = mutableListOf<FlowSignal>()
        val disclosureSignals = mutableListOf<DisclosureSignal>()
        val valuationSignals = mutableListOf<ValuationSignal>()
        val reversalSignals = mutableListOf<ReversalSignal>()
        val reviewSignals = mutableListOf<EarningsReviewSignal>()

        for (code in codes) {
            val name = runCatching { master.findByCode(code)?.name }.getOrNull() ?: code

            // 1. 연속 순매수 — 미확정(전부 0)일은 getInvestorFlow가 이미 제외함.
            val flowsOrNull = runCatching { kis.getInvestorFlow(code, days = 10) }.getOrNull()?.takeIf { it.isNotEmpty() }

            // 수급 아카이브 — KIS는 과거 이력 조회가 없어 오늘 안 남기면 영영 사라진다.
            // 신호 평가와 무관하게 확정 일별값을 영속(실패해도 스캔은 계속).
            flowsOrNull?.let { flows -> runCatching { investorHistory?.appendNew(code, flows) } }
            flowsOrNull?.let { flows ->
                for (type in FlowType.entries) {
                    val sig = evalFlow(code, name, type, flows) ?: continue
                    val key = "$code:${type.name}"
                    if (state[key] == sig.startDate) continue   // 같은 streak → skip
                    state[key] = sig.startDate
                    flowSignals += sig
                }
            }

            // 4. 수급 전환점(F4) — 5일 연속 한 방향 후 첫 반대 방향. 수급 데이터는 #1과 공유.
            val bt = backtest
            if (flowsOrNull != null && bt != null) {
                val sensitivity = runCatching { bt.getFlowSensitivity(code) }.getOrNull()
                for (type in FlowType.entries) {
                    val net = flowsOrNull.map { if (type == FlowType.FOREIGN) it.foreign else it.institution }
                    val rev = detectReversal(net) ?: continue
                    // 소음 필터: 이 주체 수급이 가격과 같은 방향으로 움직여온 종목만(r≥0.3·confident).
                    val corr = sensitivity?.items?.firstOrNull { it.investor == type.corrLabel }
                    if (corr == null || !corr.confident || corr.r < REVERSAL_MIN_CORR) continue
                    val dirLabel = if (rev.toBuy) "BUY" else "SELL"
                    val key = "REV:$code:${type.name}:$dirLabel"
                    if (withinCooldown(state[key], today, REVERSAL_COOLDOWN_DAYS)) continue
                    state[key] = today
                    reversalSignals += ReversalSignal(code, name, type, rev.toBuy, rev.prevStreak, rev.todayQty,
                        backtestNote = if (rev.toBuy) backtestNote(code, type) else null)
                }
            }

            // 2. 신규 공시 — 중요 유형만, 이미 알린 rceptNo는 skip.
            val discsOrNull = runCatching { dart.getDisclosures(code, days = 2) }.getOrNull()
            discsOrNull?.let { discs ->
                val key = "DISC:$code"
                val seen = state[key]?.split(",")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                val fresh = mutableListOf<DisclosureSignal>()
                for (d in discs) {
                    if (!isImportant(d.reportName)) continue
                    val rceptNo = extractRceptNo(d.url) ?: continue
                    if (rceptNo in seen) continue
                    seen += rceptNo
                    fresh += DisclosureSignal(code, name, d.reportName, d.url, rceptNo)
                }
                if (fresh.isNotEmpty()) {
                    disclosureSignals += fresh
                    // 최근 50개만 유지(무한 증가 방지). 접수번호는 시간순 증가라 큰 값이 최신.
                    state[key] = seen.sortedDescending().take(50).joinToString(",")
                }
            }

            // 5. 실적 리뷰(F3 3c) — 새 정기보고서 접수 감지 → 실제 누적 순이익 vs 직전 run-rate 예상.
            val ep = earningsPreview
            if (discsOrNull != null && ep != null) {
                val key = "EREV:$code"
                val seen = state[key]?.split(",")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                for (d in discsOrNull) {
                    if (!isPeriodicName(d.reportName) || d.reportName.contains("정정")) continue
                    val rceptNo = extractRceptNo(d.url) ?: continue
                    if (rceptNo in seen) continue
                    // O2: seen은 리뷰 **성공 시에만** 기록한다. 기존엔 review() 호출 전에 기록해서
                    // DART 일시 오류·공시 접수 직후 재무 API 미반영(actual null)이면 그 실적 리뷰가
                    // 영영 스킵됐다. 보고서명 파싱 불가만 영구 대상 외라 즉시 seen. 나머지 실패는
                    // 미기록 → 다음 스캔 재시도, 공시 창(days=2)을 벗어나면 자연 소멸(무한 재시도 없음).
                    if (com.haky.edge.ai.EarningsPreviewService.reviewPlan(d.reportName) == null) {
                        seen += rceptNo
                        continue
                    }
                    runCatching { ep.review(code, d.reportName) }.getOrNull()?.let { rv ->
                        seen += rceptNo
                        // N2: 리뷰 발화 시 가이던스 수집(웹검색 1회, (code,rceptNo) 캐시·일일 한도).
                        // 실패해도 리뷰 신호는 그대로 — 가이던스는 병기 정보일 뿐.
                        val g = runCatching {
                            guidance?.collectForReview(code, d.reportName, rceptNo, rv.periodLabel)
                        }.getOrNull()
                        reviewSignals += EarningsReviewSignal(code, name, rv, g)
                    }
                }
                if (seen.isNotEmpty()) state[key] = seen.sortedDescending().take(10).joinToString(",")
            }

            // 3. 밸류밴드 저평가 진입 — 상태 전이(저평가 아님→저평가)일 때만. 상태는 매 스캔 갱신.
            runCatching { valuationBand.getValuationBand(code) }.getOrNull()
                ?.takeIf { it.perPercentile >= 0 }
                ?.let { vb ->
                    val key = "VALUE:$code"
                    val prev = state[key]
                    if (vb.perLabel == LOW_VALUE_LABEL && prev != LOW_VALUE_LABEL) {
                        valuationSignals += ValuationSignal(code, name, vb.perCurrent, vb.perPercentile)
                    }
                    state[key] = vb.perLabel   // 발화 여부와 무관하게 현재 상태 기록(전이 감지용)
                }
        }

        // 6. 프리모템 무효화 조건(F5) — 관심종목 목록과 무관하게 활성 프리모템 전체를 평가.
        //    가격·수급(price_below/above·flow_exit) + 펀더멘털(target_cut 목표가 하향·event_before 임박 이벤트).
        //    target_cut/event_before 조회는 해당 타입이 활성일 때만(불필요한 DART/이력 조회 회피).
        //    markFired(1회성 비활성화)는 여기서 하지 않고 발송 성공 후에 — 발송 실패 시 알림이
        //    영영 사라지는 걸 막는다(다음 스캔에 재평가·재발송).
        val premortemSignals = mutableListOf<PremortemSignal>()
        val premortemFired = mutableListOf<Pair<String, List<Int>>>()
        premortem?.let { svc ->
            for (pm in runCatching { svc.allWithActive() }.getOrElse { emptyList() }) {
                val price = runCatching { kis.getPrice(pm.code).price }.getOrNull()
                val sellStreak = runCatching { kis.getInvestorFlow(pm.code, days = 10) }.getOrNull()
                    ?.let { flows -> foreignSellStreak(flows) } ?: 0
                val hasType = { type: String -> pm.invalidations.any { it.active && it.type == type } }
                val targetCut = if (hasType("target_cut"))
                    runCatching { targetLog?.cutSince(pm.code, pm.createdAt) }.getOrNull() else null
                val daysToEvent = if (hasType("event_before"))
                    runCatching { earningsPreview?.preview(pm.code)?.dDay }.getOrNull() else null
                val fired = com.haky.edge.ai.PremortemService.firedInvalidations(
                    pm, price, sellStreak,
                    targetLowered = targetCut != null,
                    daysToNextEvent = daysToEvent,
                )
                if (fired.isEmpty()) continue
                premortemFired += pm.code to fired
                premortemSignals += PremortemSignal(
                    pm.code, pm.name, pm.reason,
                    fired.map { i ->
                        val inv = pm.invalidations[i]
                        val base = inv.description + (inv.anchor?.let { " ($it)" } ?: "")
                        // target_cut/event_before 발화 시 관측한 실제 값을 병기(사실 앵커).
                        when {
                            inv.type == "target_cut" && targetCut != null ->
                                "$base (목표가 ${"%,d".format(targetCut.fromTarget)}→${"%,d".format(targetCut.toTarget)}원, ${"%+.1f".format(targetCut.changePct)}%)"
                            inv.type == "event_before" && daysToEvent != null ->
                                "$base (실적 D-$daysToEvent)"
                            else -> base
                        }
                    },
                )
            }
        }

        // 7. 리밸런싱 비중 점검(R2) — 스냅샷 신선도 게이트는 check() 내부, 여기선 14일 쿨다운 추가.
        val rebalanceSignals = mutableListOf<RebalanceSignal>()
        rebalance?.let { svc ->
            runCatching { svc.check() }.getOrNull()?.let { result ->
                if (result.evaluated) {
                    for (drift in result.drifts.filter { it.fired }) {
                        val key = "RB:DRIFT:${drift.code}"
                        if (withinCooldown(state[key], today, REBALANCE_COOLDOWN_DAYS)) continue
                        state[key] = today
                        val sign = if (drift.deltaPp >= 0) "+" else ""
                        rebalanceSignals += RebalanceSignal(
                            "*${drift.name}* (${drift.code}) 비중: ${"%.1f".format(drift.baselinePct)}% → ${"%.1f".format(drift.currentPct)}% (기준 대비 ${sign}${"%.1f".format(drift.deltaPp)}%p)"
                        )
                    }
                    if (result.topBandFired) {
                        val key = "RB:TOP"
                        if (!withinCooldown(state[key], today, REBALANCE_COOLDOWN_DAYS)) {
                            state[key] = today
                            rebalanceSignals += RebalanceSignal(
                                "역사적 상단권 종목 비중 합 ${"%.1f".format(result.topBandWeightPct!!)}% (${result.topBandStocks.joinToString("·")})"
                            )
                        }
                    }
                }
            }
        }

        val descriptions = flowSignals.map { "${it.name}(${it.code}) ${it.type.label} ${it.streak}일 연속 순매수" } +
            disclosureSignals.map { "${it.name}(${it.code}) 공시: ${it.title}" } +
            valuationSignals.map { "${it.name}(${it.code}) 밸류 저평가(PER 하위 ${it.percentile}%)" } +
            reversalSignals.map { "${it.name}(${it.code}) ${it.type.label} ${if (it.toBuy) "매수" else "매도"} 전환(직전 ${it.prevStreak}일 반대)" } +
            reviewSignals.map { "${it.name}(${it.code}) 실적 리뷰: ${it.review.periodLabel} run-rate ${it.review.verdict}" } +
            premortemSignals.map { "${it.name}(${it.code}) 무효화 조건 발동: ${it.descriptions.joinToString("; ")}" } +
            rebalanceSignals.map { "비중 점검 신호: ${it.text}" }

        // N3-a: 발송 직전 — 디듀프 통과분(signal lists)을 jsonl에 영속.
        if (descriptions.isNotEmpty()) {
            runCatching {
                signalFiredLog?.appendNew(buildFiredEntries(today, flowSignals, disclosureSignals, valuationSignals, reversalSignals, reviewSignals, premortemSignals, rebalanceSignals))
            }
        }

        var posted = false
        if (descriptions.isNotEmpty()) {
            posted = slack.postMessage(signalChannel, formatMessage(flowSignals, disclosureSignals, valuationSignals, reversalSignals, reviewSignals, premortemSignals, rebalanceSignals))
        }
        // 상태 저장·프리모템 비활성화는 "알릴 게 없거나, 알림이 실제로 나갔을 때"만 —
        // 발송 실패 시 디듀프 상태가 앞서 나가 신호가 조용히 유실되는 걸 막는다(다음 스캔 재시도).
        if (descriptions.isEmpty() || posted) {
            premortem?.let { svc ->
                premortemFired.forEach { (code, idx) -> runCatching { svc.markFired(code, idx) } }
            }
            saveState(state)   // 밸류밴드 상태 추적 위해 발화 없어도 저장(전이 기준 갱신)
        } else {
            println("[Signals] Slack 발송 실패 — 상태 저장·프리모템 비활성화 보류(다음 스캔 재시도)")
        }
        return ScanResult(scanned = codes.size, fired = descriptions, posted = posted)
    }

    /** 발화한 신호 목록을 SignalFired 레코드로 변환(N3-a 로그용). */
    private fun buildFiredEntries(
        date: String,
        flows: List<FlowSignal>,
        discs: List<DisclosureSignal>,
        vals: List<ValuationSignal>,
        reversals: List<ReversalSignal>,
        reviews: List<EarningsReviewSignal>,
        premortems: List<PremortemSignal>,
        rebalances: List<RebalanceSignal>,
    ): List<SignalFired> = buildList {
        flows.forEach { add(SignalFired(date, it.code, "FLOW", "${it.type.label} ${it.streak}일 연속 순매수")) }
        discs.forEach { add(SignalFired(date, it.code, "DISCLOSURE", it.title)) }
        vals.forEach { add(SignalFired(date, it.code, "VALUATION", "PER ${"%.1f".format(it.perCurrent)}배 (역사적 하위 ${it.percentile}%)")) }
        reversals.forEach { add(SignalFired(date, it.code, "REVERSAL", "${it.type.label} ${if (it.toBuy) "매수" else "매도"} 전환")) }
        reviews.forEach { add(SignalFired(date, it.code, "EARNINGS_REVIEW", "${it.review.periodLabel} ${it.review.verdict}")) }
        premortems.forEach { add(SignalFired(date, it.code, "PREMORTEM", it.descriptions.joinToString("; "))) }
        rebalances.forEach { add(SignalFired(date, "", "REBALANCE", it.text)) }
    }

    /** 한 종목·한 신호종류의 연속 순매수 streak. THRESHOLD 미만이면 null. */
    private fun evalFlow(code: String, name: String, type: FlowType, flows: List<InvestorFlow>): FlowSignal? {
        val value: (InvestorFlow) -> Long = when (type) {
            FlowType.FOREIGN -> { f -> f.foreign }
            FlowType.INSTITUTION -> { f -> f.institution }
        }
        var streak = 0
        var cumulative = 0L
        for (f in flows) {              // 최신부터
            if (value(f) > 0) { streak++; cumulative += value(f) } else break
        }
        if (streak < FLOW_THRESHOLD) return null
        return FlowSignal(code, name, type, streak, flows[streak - 1].date, cumulative)
    }

    /** 중요 공시 여부 — 주가 트리거가 되는 유형 키워드 화이트리스트. 단순 정정·경미한 공시는 제외. */
    private fun isImportant(reportName: String): Boolean =
        IMPORTANT_KEYWORDS.any { reportName.contains(it) }

    /** DART url(...rcpNo=20260101000123)에서 접수번호 추출. */
    private fun extractRceptNo(url: String): String? =
        Regex("""rcpNo=(\d+)""").find(url)?.groupValues?.get(1)

    /** 외인 순매수(전환 방향) 백테스트 근거 한 줄 — 매수 전환 메시지에 병기. 데이터 없으면 null. */
    private suspend fun backtestNote(code: String, type: FlowType): String? {
        val bt = runCatching { backtest?.getBacktest(code) }.getOrNull() ?: return null
        val sig = bt.signals.firstOrNull { it.signal == "${type.corrLabel} 순매수" } ?: return null
        if (sig.n <= 0 || sig.winRate < 0) return null
        val caveat = if (sig.confident) "" else ", 참고 수준"
        return "이 종목 ${type.corrLabel} 순매수 신호 익일 승률 ${sig.winRate}% (n=${sig.n}$caveat)"
    }

    /** FlowCorrelation.investor / Backtest signal명은 "외인/기관" 축약 라벨을 쓴다. */
    private val FlowType.corrLabel: String
        get() = when (this) { FlowType.FOREIGN -> "외인"; FlowType.INSTITUTION -> "기관" }

    /** 정기보고서(분기/반기/사업) 여부 — F3 리뷰 대상 판별. */
    private fun isPeriodicName(name: String) =
        name.contains("분기보고서") || name.contains("반기보고서") || name.contains("사업보고서")

    /** 외국인 연속 순매도 일수(최신부터). flow_exit 평가용 — 0=오늘 순매도 아님. */
    private fun foreignSellStreak(flows: List<InvestorFlow>): Int {
        var streak = 0
        for (f in flows) { if (f.foreign < 0) streak++ else break }
        return streak
    }

    /** 종류별 섹션으로 한 메시지 구성. (mrkdwn 정규화는 SlackClient가 일괄 처리) */
    private fun formatMessage(
        flows: List<FlowSignal>,
        discs: List<DisclosureSignal>,
        vals: List<ValuationSignal>,
        reversals: List<ReversalSignal> = emptyList(),
        reviews: List<EarningsReviewSignal> = emptyList(),
        premortems: List<PremortemSignal> = emptyList(),
        rebalances: List<RebalanceSignal> = emptyList(),
    ): String = buildString {
        appendLine("🔔 *오늘의 관심종목 신호*")
        appendLine()
        if (flows.isNotEmpty()) {
            appendLine("📈 *연속 순매수*")
            flows.forEach { s ->
                appendLine("• *${s.name}* (${s.code}) — ${s.type.label} *${s.streak}일 연속* · 누적 +${"%,d".format(s.cumulative)}주")
            }
            appendLine()
        }
        if (discs.isNotEmpty()) {
            appendLine("📋 *신규 공시*")
            discs.forEach { d ->
                appendLine("• *${d.name}* (${d.code}) — ${d.title}")
                appendLine("  ${d.url}")
            }
            appendLine()
        }
        if (vals.isNotEmpty()) {
            appendLine("💰 *밸류에이션 — 역사적 하단권 진입*")
            vals.forEach { v ->
                appendLine("• *${v.name}* (${v.code}) — PER *${"%.1f".format(v.perCurrent)}배* (역사적 하위 ${v.percentile}%)")
            }
            appendLine()
        }
        if (reversals.isNotEmpty()) {
            appendLine("🔄 *수급 전환점*")
            reversals.forEach { r ->
                val dir = if (r.toBuy) "순매도 후 첫 *순매수*" else "순매수 후 첫 *순매도*"
                val qty = "%,d".format(kotlin.math.abs(r.todayQty))
                appendLine("• *${r.name}* (${r.code}) — ${r.type.label} ${r.prevStreak}일 연속 $dir (${if (r.toBuy) "+" else "-"}${qty}주)")
                r.backtestNote?.let { appendLine("  _${it}_") }
            }
            appendLine()
        }
        if (reviews.isNotEmpty()) {
            appendLine("📊 *실적 리뷰 — run-rate 대비*")
            reviews.forEach { s ->
                val rv = s.review
                appendLine("• *${s.name}* (${s.code}) — ${rv.periodLabel} 누적 순이익 *${"%,d".format(rv.actualEok)}억*, 직전 속도 예상(${"%,d".format(rv.expectedEok)}억) 대비 *${"%+.1f".format(rv.diffPct)}% ${rv.verdict}*")
                // N2: 회사가 직접 밝힌 가이던스 병기(있을 때만, 최대 4건).
                s.guidance?.items?.take(GUIDANCE_MAX_LINES)?.forEach { g ->
                    val at = g.saidAt.ifBlank { "시점 미상" }
                    appendLine("  🗣 ${g.topic}: ${g.statement} ($at)")
                }
            }
            appendLine("  _단순 연환산 예상 대비이며 컨센서스가 아닙니다. 가이던스는 웹 수집 회사 발언(검증되지 않음)_")
            appendLine()
        }
        if (premortems.isNotEmpty()) {
            appendLine("⚠️ *무효화 조건 발동 — 매수 가설 점검*")
            premortems.forEach { p ->
                p.descriptions.forEach { d ->
                    appendLine("• *${p.name}* (${p.code}) — $d")
                }
                appendLine("  _매수 사유: ${p.reason.ifBlank { "(미입력)" }}_")
            }
            appendLine()
        }
        if (rebalances.isNotEmpty()) {
            appendLine("⚖️ *비중 점검 신호*")
            rebalances.forEach { r -> appendLine("• ${r.text}") }
            appendLine("  _${com.haky.edge.ai.RebalanceService.CAVEAT}_")
            appendLine()
        }
        append("_장 마감 후 확정 데이터 기준 · 참고용_")
    }

    private fun loadState(): MutableMap<String, String> {
        if (!stateFile.exists()) return mutableMapOf()
        return runCatching {
            json.decodeFromString(mapSerializer, stateFile.readText()).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun saveState(state: Map<String, String>) {
        runCatching { stateFile.writeTextAtomic(json.encodeToString(mapSerializer, state)) }
    }

    companion object {
        private const val FLOW_THRESHOLD = 3        // N일 연속 순매수 = 신호
        private const val LOW_VALUE_LABEL = "역사적 하단권"
        internal const val REVERSAL_MIN_STREAK = 5  // 직전 N일 연속 반대 방향이어야 전환으로 인정
        internal const val REVERSAL_COOLDOWN_DAYS = 7 // 같은 방향 전환 재알림 금지 기간
        internal const val REVERSAL_MIN_CORR = 0.3  // flow-sensitivity r 하한(수급이 가격을 움직이는 종목만)
        internal const val REBALANCE_COOLDOWN_DAYS = 14 // 리밸런싱 룰별 재알림 금지 기간
        private const val GUIDANCE_MAX_LINES = 4    // N2 실적 리뷰당 가이던스 표시 상한(도배 방지)

        /**
         * 수급 전환점 감지(F4, 순수 함수). netByDay = 한 주체의 일별 순매수량, 최신이 앞(확정 일별값).
         * 당일 방향 ≠ 0 이고 직전 [REVERSAL_MIN_STREAK]일 이상 연속 반대 방향이면 전환.
         * 0(보합)은 streak을 끊는다 — "연속"의 정의를 보수적으로.
         */
        internal fun detectReversal(netByDay: List<Long>): Reversal? {
            if (netByDay.size < REVERSAL_MIN_STREAK + 1) return null
            val today = netByDay[0]
            if (today == 0L) return null
            var streak = 0
            for (i in 1 until netByDay.size) {
                val v = netByDay[i]
                // 오늘과 반대 부호가 이어지는 동안만 카운트
                if (v != 0L && (v > 0) != (today > 0)) streak++ else break
            }
            if (streak < REVERSAL_MIN_STREAK) return null
            return Reversal(toBuy = today > 0, prevStreak = streak, todayQty = today)
        }

        /** 마지막 발화일(ISO)로부터 days일이 안 지났으면 true(재알림 금지). 파싱 실패는 false(발화 허용). */
        internal fun withinCooldown(lastIso: String?, todayIso: String, days: Int): Boolean {
            if (lastIso.isNullOrBlank()) return false
            return runCatching {
                val last = java.time.LocalDate.parse(lastIso)
                val today = java.time.LocalDate.parse(todayIso)
                !last.plusDays(days.toLong()).isBefore(today)
            }.getOrDefault(false)
        }
        // 주가 트리거가 되는 중요 공시 유형(부분 일치). 단순 정정·임원변경 등 경미한 건 제외된다.
        private val IMPORTANT_KEYWORDS = listOf(
            "공급계약", "수주", "단일판매",            // 수주·계약
            "유상증자", "무상증자", "감자",            // 증자·감자
            "자기주식", "자사주",                       // 자사주
            "합병", "회사분할", "영업양수", "영업양도",  // 지배구조
            "영업실적", "잠정실적", "매출액또는손익구조", // 실적
            "전환사채", "신주인수권부사채", "교환사채",   // 메자닌
            "최대주주변경", "경영권", "공개매수",         // 지분(소유변동신고서 같은 노이즈 제외)
            "상장폐지", "관리종목", "거래정지",          // 상장 리스크
            "주식분할", "주식병합", "현금ㆍ현물배당",     // 주주환원·자본
        )
    }
}
