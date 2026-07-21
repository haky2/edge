package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.macro.YahooHistoryClient
import com.haky.edge.util.KST
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 신호 버킷 1개 × horizon의 채점 결과. excess = 코스피(^KS11) 대비 초과수익률. */
@Serializable
data class DiscoveryValidationBucket(
    val label: String,        // baseline | 상대모멘텀(+3/5/7p) | 신고가근접 | 저점반등 | 교집합(2신호)
    val days: Int,            // 5 | 20
    val n: Int,
    val avgRawPct: Double,
    val avgExcessPct: Double,
    val medianExcessPct: Double,
    val winExcessPct: Double, // 초과수익 > 0 비율
    val silenced: Boolean,    // n<15 — 수치 병기, 판정 제외
)

/** GET /discovery-validation 응답 — ②-2b Discovery 가격 3신호 실증(1회성 관리 라우트). */
@Serializable
data class DiscoveryValidationReport(
    val generatedAt: String,
    val universeSize: Int,
    val codesScored: Int,
    val kospiDays: Int,
    val dateRange: String,    // 채점 이벤트 날짜 범위
    val joinFailures: Int,    // 종목 거래일인데 ^KS11 이력에 없는 날(정렬 누락 — 해당 일 제외)
    val buckets: List<DiscoveryValidationBucket>,
    val caveat: String,
    val textReport: String,
)

/**
 * ②-2b Discovery 신호 실측 — 후보 발굴 컷(신호 2개 교집합·상대모멘텀 +5%p·신고가 90%·
 * 저점반등 30%/+5%)이 전부 손짐작 값 — 발굴 후보가 실제로 코스피 대비 초과수익을 냈는지
 * peer 유니버스 750봉으로 채점한다. LLM 0, 순수 계산.
 *
 * ⚠️ K5(2026-07-21): **정본은 signal-lab discovery 수트**(GET /signal-lab?suite=discovery&universe=peer). 라우트 해제됨.
 * 핵심 3신호(상대모멘텀+5p·신고가근접·저점반등)는 signal-lab이 **소수점까지 완전 재현**(2026-07-21 대조:
 * baseline·n·Δ 전부 일치). 이 파일은 그리드({+3,+7})·교집합(2신호) 민감도 버킷 재현·단위테스트용으로 동결.
 * 신규 실측은 signal-lab을 쓸 것.
 *
 * 설계 제약(스펙 사전 지정):
 *  - 수급전환은 KIS가 과거 임의 시점 수급 이력을 안 줘 재구성 불가 → **가격 3신호만**.
 *    교집합 버킷도 가격 신호끼리의 2개 이상(라이브의 수급 포함 교집합과 다름 — caveat).
 *  - 52주 위치는 라이브(quote.high52w/low52w)와 달리 252봉 창 고저 재계산 — 근사 차이 caveat.
 *  - 유니버스는 peer 전 종목(관심 제외 없이 — 라이브와 다름을 caveat).
 *  - 채점: forward 5/20거래일 코스피 대비 초과수익(②-1 방식), 같은 (code,날짜)는 버킷당 1회,
 *    연속 발화는 첫 발화만(±5거래일 클러스터, anchor 방식). 벤치마크는 Yahoo ^KS11
 *    (KIS index range 페이지네이션 미검증 회피), 750봉≈3년 커버 위해 range=5y.
 *  - 컷 민감도는 그리드 튜닝 금지 — 상대모멘텀 +5%p에 한해 {+3, +7} 2값만 사전 지정 비교.
 *  - 판정 기준(사전 지정): 버킷이 baseline보다 20일 평균 초과수익·승률 둘 다 높아야 "신호 있음".
 *    n<15 버킷은 침묵.
 */
class DiscoveryValidationService(
    private val history: DailyHistoryService,
    private val yahoo: YahooHistoryClient,
) {
    suspend fun validate(): DiscoveryValidationReport {
        val kospiRaw = yahoo.dailyCloses("^KS11", range = "5y", zone = SEOUL)
        val kospi = KospiSeries(
            dates = kospiRaw.map { it.first.format(YMD) },
            closes = kospiRaw.map { it.second },
        )

        val universe = PeerValuationService.peerUniverse().keys.sorted()
        val pooled = mutableMapOf<String, MutableList<Firing>>()   // label → 클러스터 dedupe 후 발화
        val baseline = mutableListOf<Firing>()
        var scored = 0
        var joinFailures = 0
        for (code in universe) {
            val barsAsc = runCatching { history.getHistory(code, minBars = TARGET_BARS) }
                .getOrElse { emptyList() }.asReversed()
            if (barsAsc.size < MIN_BARS) continue
            scored++
            val collected = collectFirings(barsAsc, kospi)
            joinFailures += collected.joinFailures
            baseline += collected.firings
            dedupeByLabel(collected.firings).forEach { (label, list) ->
                pooled.getOrPut(label) { mutableListOf() } += list
            }
        }

        val buckets = buildList {
            addAll(bucketsOf("baseline", baseline))
            for (label in LABEL_ORDER) addAll(bucketsOf(label, pooled[label] ?: emptyList()))
        }
        val allDates = baseline.map { it.date }
        val report = DiscoveryValidationReport(
            generatedAt = LocalDate.now(KST).toString(),
            universeSize = universe.size,
            codesScored = scored,
            kospiDays = kospi.dates.size,
            dateRange = if (allDates.isEmpty()) "-" else "${allDates.min()} ~ ${allDates.max()}",
            joinFailures = joinFailures,
            buckets = buckets,
            caveat = "가격 3신호만(수급전환은 이력 재구성 불가 — 라이브 교집합 컷과 다름). " +
                "52주 위치는 252봉 창 재계산(라이브는 KIS 52주 고저 — 근사 차이). " +
                "유니버스는 peer 전 종목(관심종목 제외 없음 — 라이브와 다름). " +
                "같은 날 복수 종목 발화는 독립 표본 아님(시장 공통 충격).",
            textReport = "",
        )
        val rendered = report.copy(textReport = renderText(report))
        println(rendered.textReport)
        return rendered
    }

    companion object {
        const val TARGET_BARS = 750
        const val MIN_BARS = 300          // 252(52주 창)+20(forward)+여유
        const val CLUSTER_GAP = 5         // 같은 신호 재발화 무시 간격(거래일)
        private const val MIN_BUCKET_N = 15
        val HORIZONS = listOf(5, 20)
        internal val REL_MOMENTUM_GRID = listOf(3.0, 5.0, 7.0) // +5p가 라이브 컷, {+3,+7}은 사전 지정 비교
        internal val LABEL_ORDER = listOf(
            "상대모멘텀(+3p)", "상대모멘텀(+5p)", "상대모멘텀(+7p)",
            "신고가근접", "저점반등", "교집합(2신호)",
        )

        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")

        internal class KospiSeries(val dates: List<String>, val closes: List<Double>) {
            val idxByDate: Map<String, Int> = dates.withIndex().associate { (i, d) -> d to i }
        }

        /** 발화 1건 = (code 무관 풀링, 날짜·t·수익률). labels = 이 날 켜진 버킷들. */
        internal data class Firing(
            val t: Int,
            val date: String,
            val labels: Set<String>,
            val raw: Map<Int, Double>,     // horizon → 원수익률 %
            val excess: Map<Int, Double>,  // horizon → 코스피 대비 초과수익률 %
        )

        internal data class Collected(val firings: List<Firing>, val joinFailures: Int)

        /**
         * 한 종목의 전 평가일 수집. barsAsc = 오래된 순. t 범위 [252, size-1-20]:
         * 52주 창(t-251..t) + forward 20거래일 확보. 코스피는 같은 날짜로 정확 조인
         * (미스매치 = joinFailures — Yahoo 결측일 방어), 코스피 자기 배열에서 forward 산출.
         */
        internal fun collectFirings(barsAsc: List<DailyBar>, kospi: KospiSeries): Collected {
            val closes = barsAsc.map { it.close.toDouble() }
            val out = mutableListOf<Firing>()
            var fails = 0
            for (t in 252..barsAsc.size - 1 - 20) {
                val date = barsAsc[t].date
                val kIdx = kospi.idxByDate[date] ?: run { fails++; null } ?: continue
                if (kIdx < 20 || kIdx + 20 > kospi.closes.lastIndex) continue
                if (closes[t] <= 0 || closes[t - 20] <= 0 || closes[t - 5] <= 0) continue

                val win52 = barsAsc.subList(t - 251, t + 1)
                val hi = win52.maxOf { it.high }.toDouble()
                val lo = win52.minOf { it.low }.toDouble()
                val pos52w = if (hi > lo) (closes[t] - lo) / (hi - lo) * 100 else continue
                val ret20 = (closes[t] / closes[t - 20] - 1) * 100
                val ret5 = (closes[t] / closes[t - 5] - 1) * 100
                val kBase = kospi.closes[kIdx]
                if (kBase <= 0 || kospi.closes[kIdx - 20] <= 0) continue
                val benchRet20 = (kBase / kospi.closes[kIdx - 20] - 1) * 100

                val labels = buildSet {
                    val relDiff = ret20 - benchRet20
                    for (pp in REL_MOMENTUM_GRID) if (relDiff >= pp) add("상대모멘텀(+${pp.toInt()}p)")
                    if (pos52w >= DiscoveryService.HIGH_POS_PCT) add("신고가근접")
                    if (pos52w < DiscoveryService.LOW_POS_PCT && ret5 >= DiscoveryService.REBOUND_RET5_PCT) add("저점반등")
                    // 교집합은 라이브 경로(evaluateSignals, 수급은 빈 리스트 → 미발화)로 판정해 컷 정의 일치 보장
                    val live = DiscoveryService.evaluateSignals(pos52w, ret20, ret5, benchRet20, emptyList(), emptyList())
                    if (live.size >= DiscoveryService.MIN_SIGNALS) add("교집합(2신호)")
                }

                val raw = HORIZONS.associateWith { h -> (closes[t + h] / closes[t] - 1) * 100 }
                val kFwd = HORIZONS.associateWith { h -> (kospi.closes[kIdx + h] / kBase - 1) * 100 }
                out += Firing(
                    t = t, date = date, labels = labels,
                    raw = raw,
                    excess = HORIZONS.associateWith { h -> raw.getValue(h) - kFwd.getValue(h) },
                )
            }
            return Collected(out, fails)
        }

        /** 버킷별 ±CLUSTER_GAP 클러스터 dedupe — 같은 신호의 연속 발화는 첫 발화만(종목 내). */
        internal fun dedupeByLabel(firings: List<Firing>): Map<String, List<Firing>> {
            val byLabel = mutableMapOf<String, MutableList<Firing>>()
            for (f in firings.sortedBy { it.t }) {
                for (label in f.labels) {
                    val acc = byLabel.getOrPut(label) { mutableListOf() }
                    if (acc.isEmpty() || f.t - acc.last().t > CLUSTER_GAP) acc += f
                }
            }
            return byLabel
        }

        internal fun bucketsOf(label: String, firings: List<Firing>): List<DiscoveryValidationBucket> =
            HORIZONS.map { h ->
                val ex = firings.mapNotNull { it.excess[h] }
                val raw = firings.mapNotNull { it.raw[h] }
                DiscoveryValidationBucket(
                    label = label, days = h, n = ex.size,
                    avgRawPct = round2(if (raw.isEmpty()) 0.0 else raw.average()),
                    avgExcessPct = round2(if (ex.isEmpty()) 0.0 else ex.average()),
                    medianExcessPct = round2(if (ex.isEmpty()) 0.0 else AnalogService.median(ex)),
                    winExcessPct = round1(if (ex.isEmpty()) 0.0 else ex.count { it > 0 } * 100.0 / ex.size),
                    silenced = ex.size < MIN_BUCKET_N,
                )
            }

        internal fun renderText(r: DiscoveryValidationReport): String = buildString {
            appendLine("═══ Discovery 가격 3신호 실증(②-2b) ═══")
            appendLine("유니버스 ${r.universeSize} · 채점 ${r.codesScored}종목 · 기간 ${r.dateRange} · " +
                "^KS11 ${r.kospiDays}일 · 조인 누락 ${r.joinFailures}일")
            val byLabel = r.buckets.groupBy { it.label }
            val base = byLabel["baseline"]?.associateBy { it.days } ?: emptyMap()
            for ((label, bs) in byLabel) {
                appendLine()
                appendLine("── $label ──")
                for (b in bs) {
                    val bl = base[b.days]
                    val cmp = if (label == "baseline" || bl == null) ""
                    else " (baseline 대비 초과 ${fmtSigned(b.avgExcessPct - bl.avgExcessPct)}%p·승률 ${fmtSigned(b.winExcessPct - bl.winExcessPct)}%p)"
                    val mark = if (b.silenced) " [침묵 n<15]" else ""
                    appendLine("  ${b.days}일: n=${b.n} 초과수익 평균 ${b.avgExcessPct}% 중앙값 ${b.medianExcessPct}% " +
                        "승률 ${b.winExcessPct}%$cmp$mark")
                }
            }
            appendLine()
            appendLine("판정 기준(사전 지정): 20일 평균 초과수익·승률이 baseline보다 둘 다 높아야 신호 있음. n<15 침묵.")
            appendLine("caveat: ${r.caveat}")
        }

        private fun fmtSigned(v: Double) = (if (v >= 0) "+" else "") + "%.2f".format(v)
        internal fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
        internal fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
    }
}
