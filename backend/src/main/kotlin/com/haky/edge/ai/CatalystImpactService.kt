package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.kis.DailyBar
import com.haky.edge.master.StockMaster
import com.haky.edge.util.KST
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** forward return 통계 1 horizon(1·5·20 거래일). */
@Serializable
data class ImpactHorizon(
    val days: Int,
    val avgPct: Double,
    val winRatePct: Double,
    val n: Int,              // 해당 horizon 측정 가능 건수(forward 봉 부족분 제외됨)
)

/** n개 이벤트의 horizon 묶음 */
@Serializable
data class ImpactStats(
    val n: Int,
    val horizons: List<ImpactHorizon>,
)

/** 선반영 여부별 분해 — null = 해당 그룹 이벤트 없음 */
@Serializable
data class ImpactSplit(
    val fresh: ImpactStats?,      // preReflected=false
    val reflected: ImpactStats?,  // preReflected=true
)

/** GET /catalyst-impact/{code} 응답 */
@Serializable
data class CatalystImpact(
    val code: String,
    val name: String,
    val category: String,
    val n: Int,                        // catalyst_events.jsonl 상 이벤트 수(중복 url 포함)
    val horizons: List<ImpactHorizon>, // 전체 통계
    val preReflectedSplit: ImpactSplit,
    val caveat: String,
)

/**
 * F2 수주 공시 임팩트 통계 — 백필(2-1) + 통계 계산(2-2).
 * LLM 0. forward return 계산은 F3 EarningsPreviewService.computeReactions 패턴 복제.
 */
class CatalystImpactService(
    private val dart: DartClient,
    private val history: DailyHistoryService,
    private val master: StockMaster,
    private val eventLog: CatalystEventLog,
) {
    private val backfillMutex = Mutex()

    companion object {
        val HORIZONS = listOf(1, 5, 20)
        const val BACKFILL_YEARS = 2L
        const val ORDER_CATEGORY = "수주·공급계약"

        /**
         * 이벤트 날짜 정규화 — CatalystEvent.date 계약("공시=YYYYMMDD, 뉴스=발행 표기, 정규화는 통계
         * 단계에서")의 이행부. 뉴스는 RFC-1123 발행 표기("Tue, 07 Jul 2026 07:52:00 +0900")라
         * 정규화 없이 일봉 날짜(YYYYMMDD)와 문자열 비교하면 통계에서 조용히 탈락하고 n만 부풀었다.
         * 파싱 불가는 null(통계 제외 — n에서도 빠져 분모가 일치).
         */
        internal fun normalizeDate(raw: String): String? {
            val head = raw.take(8)
            if (head.length == 8 && head.all { it.isDigit() }) return head
            return runCatching {
                java.time.ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(KST).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
            }.getOrNull()
        }

        /** DART 보고서명 → 수주·공급계약 여부(CatalystService.ruleCategory 핵심만 복제). */
        fun isOrderCategory(reportName: String): Boolean {
            val n = reportName.replace(" ", "")
            return !n.contains("정정") &&
                (n.contains("단일판매") || n.contains("공급계약") || n.contains("수주"))
        }

        /**
         * 이벤트 날짜 목록 + 일봉(오래된 순)에서 horizon별 forward return 통계.
         * 기준봉 = 이벤트일 이전(당일 포함) 마지막 거래일, 반응 = 그 다음 거래일부터.
         * forward 봉이 부족한 건은 해당 horizon에서만 제외(F3 computeReactions 동일 방식).
         */
        internal fun computeHorizons(eventDates: List<String>, barsAsc: List<DailyBar>): List<ImpactHorizon> {
            return HORIZONS.map { horizon ->
                val returns = mutableListOf<Double>()
                for (ymd in eventDates) {
                    val baseIdx = barsAsc.indexOfLast { it.date <= ymd }
                    if (baseIdx < 0) continue
                    val base = barsAsc[baseIdx].close
                    if (base <= 0) continue
                    if (baseIdx + horizon <= barsAsc.lastIndex)
                        returns += (barsAsc[baseIdx + horizon].close.toDouble() / base - 1) * 100
                }
                ImpactHorizon(
                    days       = horizon,
                    avgPct     = if (returns.isEmpty()) 0.0 else round2(returns.average()),
                    winRatePct = if (returns.isEmpty()) 0.0 else round1(returns.count { it > 0 } * 100.0 / returns.size),
                    n          = returns.size,
                )
            }
        }

        private fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
        private fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0

        internal fun buildCaveat(n: Int) = buildString {
            if (n < 10) append("표본 ${n}건 — 통계가 불안정합니다. ")
            else if (n < 30) append("표본 ${n}건 — 참고 수준입니다. ")
            append("과거 통계일 뿐 향후 반응을 보장하지 않습니다.")
        }
    }

    /**
     * F2-1: 해당 종목의 과거 2년 수주·공급계약 공시를 catalyst_events.jsonl에 백필.
     * url 기준 중복 체크 — 이미 있는 공시는 건너뜀.
     * 백필분: sentiment=호재(수주는 기본 호재), strength=미상, preReflected=false(미상).
     */
    suspend fun backfill(code: String) = backfillMutex.withLock {
        val existingUrls = eventLog.readAll().filter { it.code == code }.map { it.url }.toSet()
        val end = LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE)
        val bgn = LocalDate.now(KST).minusYears(BACKFILL_YEARS).format(DateTimeFormatter.BASIC_ISO_DATE)

        val disclosures = dart.getDisclosuresForPeriod(code, bgn, end)
        val now = LocalDateTime.now(KST).toString()

        val toAppend = disclosures
            .filter { isOrderCategory(it.reportName) && it.url !in existingUrls }
            .map { d ->
                CatalystEvent(
                    code         = code,
                    date         = d.date,
                    source       = "공시",
                    category     = ORDER_CATEGORY,
                    sentiment    = "호재",
                    strength     = "미상",
                    preReflected = false,
                    url          = d.url,
                    judgedAt     = now,
                )
            }
        if (toAppend.isNotEmpty()) eventLog.append(toAppend)
        toAppend.size
    }

    /** F2-2: 종목+카테고리의 공시 임팩트 통계. 이벤트 없으면 안내 메시지 포함 빈 응답. */
    suspend fun impact(code: String, category: String = ORDER_CATEGORY): CatalystImpact {
        val name = master.findByCode(code)?.name ?: code
        // 날짜 정규화(뉴스=RFC 표기 → YYYYMMDD). 정규화 불가 건은 n·통계 모두에서 제외.
        val events = eventLog.readAll()
            .filter { it.code == code && it.category == category }
            .mapNotNull { e -> normalizeDate(e.date)?.let { e.copy(date = it) } }
        if (events.isEmpty()) return empty(code, name, category)

        val barsAsc = history.getHistory(code).asReversed()
        if (barsAsc.size < 2) return empty(code, name, category)

        val allDates = events.map { it.date }
        val allHorizons = computeHorizons(allDates, barsAsc)

        val split = ImpactSplit(
            fresh = events.filter { !it.preReflected }.takeIf { it.isNotEmpty() }?.let { grp ->
                ImpactStats(grp.size, computeHorizons(grp.map { it.date }, barsAsc))
            },
            reflected = events.filter { it.preReflected }.takeIf { it.isNotEmpty() }?.let { grp ->
                ImpactStats(grp.size, computeHorizons(grp.map { it.date }, barsAsc))
            },
        )

        return CatalystImpact(
            code = code, name = name, category = category,
            n = events.size,
            horizons = allHorizons,
            preReflectedSplit = split,
            caveat = buildCaveat(events.size),
        )
    }

    private fun empty(code: String, name: String, category: String) = CatalystImpact(
        code = code, name = name, category = category,
        n = 0, horizons = emptyList(),
        preReflectedSplit = ImpactSplit(null, null),
        caveat = "아직 기록된 $category 이벤트가 없습니다. POST /catalyst-impact/$code/backfill 로 백필을 먼저 실행하세요.",
    )
}
