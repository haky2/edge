package com.haky.edge.lab

import com.haky.edge.kis.DailyBar
import kotlinx.serialization.Serializable

/**
 * 통합 백테스트 엔진(전략 실험실) — 신호를 선언적으로 정의하면(SignalDef)
 * 유니버스 리플레이 + 대조군 + 통계 리포트가 자동으로 나온다. LLM 0, 순수 계산.
 *
 * 기존 일회성 검증(anchor·discovery·catalyst·sensitivity)에서 얻은 방법론 교훈을
 * 코드 구조로 강제한다:
 *  - lookahead 차단: SignalContext가 t 이후 데이터를 노출하지 않음(미래 접근 = 예외).
 *    결측·워밍업 부족은 NaN 반환 → NaN 비교는 항상 false라 신호가 발화하지 않는다.
 *  - 기저율 함정 통제: 채점은 벤치마크(코스피) 초과수익 기본, baseline 버킷 자동 포함.
 *  - 방향 교란 통제: 하락일(-2%)·상승일(+2%) 대조군 자동 채점. 신호가 controlLabel을
 *    지정하면 판정 시 그 대조군도 이겨야 "지지"(예: 저점 터치는 단순 하락일보다 나아야 함).
 *  - 연속 발화 중복: 클러스터 dedupe(±5거래일, 첫 발화만). baseline·대조군은 무조건부 분포라 제외.
 *  - n<15 침묵, 같은 날 복수 종목 발화(교차 종속) 가시화(distinctDates).
 *  - 전 신호 공통 평가창(최대 워밍업 기준) — 신호끼리·baseline과 같은 날짜 집합에서 비교.
 */
object BacktestEngine {

    val HORIZONS = listOf(5, 20)
    const val PRIMARY_HORIZON = 20
    const val CLUSTER_GAP = 5
    const val MIN_BUCKET_N = 15
    const val CTL_MOVE_PCT = 2.0

    const val BASELINE = "baseline"
    const val CTL_DOWN = "대조군:하락일(-2%)"
    const val CTL_UP = "대조군:상승일(+2%)"

    enum class Dedupe { NONE, CLUSTER }

    /** 선언적 신호 정의. rule은 SignalContext(t 시점까지의 정보만)로 발화 여부를 판정한다. */
    class SignalDef(
        val label: String,
        val warmupBars: Int,               // rule이 필요로 하는 과거 봉 수(전 신호 max가 공통 평가창 시작)
        val dedupe: Dedupe = Dedupe.CLUSTER,
        val controlLabel: String? = null,  // 판정 시 추가로 이겨야 하는 대조군(CTL_DOWN/CTL_UP)
        val rule: (SignalContext) -> Boolean,
    )

    /** 벤치마크 일봉(오름차순). 날짜는 YYYYMMDD — 종목 일봉과 정확 조인. */
    class BenchSeries(val dates: List<String>, val closes: List<Double>) {
        val idxByDate: Map<String, Int> = dates.withIndex().associate { (i, d) -> d to i }
    }

    /**
     * t일 시점의 관찰 가능 정보만 노출하는 컨텍스트.
     * - daysAgo < 0(미래 접근)은 프로그래밍 오류 → IllegalArgumentException.
     * - 데이터 부족(워밍업 밖·벤치 결측)은 NaN → 모든 비교가 false = 미발화. 안전한 기본값.
     */
    class SignalContext internal constructor(
        private val bars: List<DailyBar>,   // 오름차순
        val t: Int,
        private val bench: BenchSeries,
        private val benchIdx: Int,
    ) {
        private fun at(daysAgo: Int): DailyBar? {
            require(daysAgo >= 0) { "미래 접근 금지: daysAgo=$daysAgo" }
            val i = t - daysAgo
            return if (i < 0) null else bars[i]
        }

        fun close(daysAgo: Int = 0): Double = at(daysAgo)?.close?.toDouble() ?: Double.NaN
        fun high(daysAgo: Int = 0): Double = at(daysAgo)?.high?.toDouble() ?: Double.NaN
        fun low(daysAgo: Int = 0): Double = at(daysAgo)?.low?.toDouble() ?: Double.NaN
        fun volume(daysAgo: Int = 0): Double = at(daysAgo)?.volume?.toDouble() ?: Double.NaN

        /** days 거래일 수익률(%): close(0) vs close(days). */
        fun ret(days: Int): Double {
            val base = close(days)
            val cur = close(0)
            return if (base.isNaN() || cur.isNaN() || base <= 0) Double.NaN else (cur / base - 1) * 100
        }

        /** 종가 n봉 평균. 창 = [t-endDaysAgo-n+1, t-endDaysAgo] (endDaysAgo=1 → t-1까지). */
        fun maClose(n: Int, endDaysAgo: Int = 0): Double = window(n, endDaysAgo) { it.close.toDouble() }?.average() ?: Double.NaN
        fun maxClose(n: Int, endDaysAgo: Int = 0): Double = window(n, endDaysAgo) { it.close.toDouble() }?.max() ?: Double.NaN
        fun minClose(n: Int, endDaysAgo: Int = 0): Double = window(n, endDaysAgo) { it.close.toDouble() }?.min() ?: Double.NaN
        fun maxHigh(n: Int, endDaysAgo: Int = 0): Double = window(n, endDaysAgo) { it.high.toDouble() }?.max() ?: Double.NaN
        fun minLow(n: Int, endDaysAgo: Int = 0): Double = window(n, endDaysAgo) { it.low.toDouble() }?.min() ?: Double.NaN
        fun avgVolume(n: Int, endDaysAgo: Int = 0): Double = window(n, endDaysAgo) { it.volume.toDouble() }?.average() ?: Double.NaN

        /** 52주(252봉, t 포함) 고저 대비 현재 종가 위치 % (0=저점, 100=고점). */
        fun pos52w(): Double {
            val hi = maxHigh(252)
            val lo = minLow(252)
            val c = close(0)
            if (hi.isNaN() || lo.isNaN() || c.isNaN() || hi <= lo) return Double.NaN
            return (c - lo) / (hi - lo) * 100
        }

        /** 벤치마크 days 거래일 수익률(%). 벤치 자기 배열 기준(종목과 거래일 어긋남 안전). */
        fun benchRet(days: Int): Double {
            require(days >= 0) { "미래 접근 금지: days=$days" }
            if (benchIdx < days) return Double.NaN
            val base = bench.closes[benchIdx - days]
            return if (base <= 0) Double.NaN else (bench.closes[benchIdx] / base - 1) * 100
        }

        private inline fun window(n: Int, endDaysAgo: Int, crossinline f: (DailyBar) -> Double): List<Double>? {
            require(endDaysAgo >= 0) { "미래 접근 금지: endDaysAgo=$endDaysAgo" }
            require(n > 0) { "창 크기는 양수: n=$n" }
            val end = t - endDaysAgo
            val start = end - n + 1
            if (start < 0 || end < 0) return null
            return (start..end).map { f(bars[it]) }
        }
    }

    // ── 리플레이 ─────────────────────────────────────────────────────────────

    /** 발화 1건. raw/excess = horizon → forward 수익률 %. */
    data class Firing(
        val code: String,
        val date: String,
        val t: Int,
        val raw: Map<Int, Double>,
        val excess: Map<Int, Double>,
    )

    data class ReplayResult(
        val firings: Map<String, List<Firing>>,  // label → 발화 목록(채택분)
        val joinFailures: Int,                   // 종목 거래일인데 벤치에 없는 날(해당 일 제외)
        val evalDays: Int,                       // 평가에 쓴 날 수
    )

    /**
     * 한 종목 리플레이. 평가창 t ∈ [워밍업 max, size-1-maxHorizon].
     * 벤치 조인 실패일·벤치 forward 부족일은 건너뛴다(초과수익 채점 불가).
     * benchLookback: rule의 benchRet() 최대 일수(평가일 벤치 인덱스 하한).
     */
    fun replay(
        code: String,
        barsAsc: List<DailyBar>,
        bench: BenchSeries,
        signals: List<SignalDef>,
        benchLookback: Int = 20,
    ): ReplayResult {
        val maxH = HORIZONS.max()
        val warmup = (signals.maxOfOrNull { it.warmupBars } ?: 0).coerceAtLeast(1) // 1 = 대조군 dayChange용
        val out = mutableMapOf<String, MutableList<Firing>>()
        val lastAdopted = mutableMapOf<String, Int>()
        var joinFails = 0
        var evalDays = 0

        for (t in warmup..barsAsc.size - 1 - maxH) {
            val bar = barsAsc[t]
            val prevClose = barsAsc[t - 1].close
            if (bar.close <= 0 || prevClose <= 0) continue
            val kIdx = bench.idxByDate[bar.date] ?: run { joinFails++; -1 }
            if (kIdx < benchLookback || kIdx + maxH > bench.closes.lastIndex) continue
            evalDays++

            val raw = HORIZONS.associateWith { h ->
                (barsAsc[t + h].close.toDouble() / bar.close - 1) * 100
            }
            val kBase = bench.closes[kIdx]
            val excess = HORIZONS.associateWith { h ->
                raw.getValue(h) - (bench.closes[kIdx + h] / kBase - 1) * 100
            }
            val firing = Firing(code, bar.date, t, raw, excess)

            // 대조군(무조건부 분포 — dedupe 없음)
            out.getOrPut(BASELINE) { mutableListOf() } += firing
            val dayChange = (bar.close.toDouble() / prevClose - 1) * 100
            if (dayChange <= -CTL_MOVE_PCT) out.getOrPut(CTL_DOWN) { mutableListOf() } += firing
            if (dayChange >= CTL_MOVE_PCT) out.getOrPut(CTL_UP) { mutableListOf() } += firing

            val ctx = SignalContext(barsAsc, t, bench, kIdx)
            for (sig in signals) {
                if (!sig.rule(ctx)) continue
                if (sig.dedupe == Dedupe.CLUSTER) {
                    val last = lastAdopted[sig.label]
                    if (last != null && t - last <= CLUSTER_GAP) continue
                    lastAdopted[sig.label] = t
                }
                out.getOrPut(sig.label) { mutableListOf() } += firing
            }
        }
        return ReplayResult(out, joinFails, evalDays)
    }

    // ── 집계·판정 ─────────────────────────────────────────────────────────────

    @Serializable
    data class LabBucket(
        val label: String,
        val days: Int,
        val n: Int,
        val distinctDates: Int,       // n과 크게 다르면 같은 날 발화 몰림(교차 종속) 신호
        val avgRawPct: Double,
        val avgExcessPct: Double,
        val medianExcessPct: Double,
        val winExcessPct: Double,     // 초과수익 > 0 비율
        val silenced: Boolean,        // n < 15 — 수치 병기, 판정 제외
    )

    @Serializable
    data class LabVerdict(
        val label: String,
        val verdict: String,          // 지지 | 반증 | 혼재 | 대조군미달 | 표본부족
        val reason: String,
    )

    fun aggregate(pooled: Map<String, List<Firing>>): List<LabBucket> =
        pooled.entries.flatMap { (label, firings) ->
            HORIZONS.map { h ->
                val ex = firings.map { it.excess.getValue(h) }
                val raw = firings.map { it.raw.getValue(h) }
                LabBucket(
                    label = label, days = h, n = ex.size,
                    distinctDates = firings.map { it.date }.distinct().size,
                    avgRawPct = round2(raw.averageOr0()),
                    avgExcessPct = round2(ex.averageOr0()),
                    medianExcessPct = round2(median(ex)),
                    winExcessPct = round1(if (ex.isEmpty()) 0.0 else ex.count { it > 0 } * 100.0 / ex.size),
                    silenced = ex.size < MIN_BUCKET_N,
                )
            }
        }

    /**
     * 사전 지정 판정(주 지평 20일): baseline보다 평균 초과수익·승률 둘 다 높아야 1차 통과.
     * controlLabel 지정 신호는 그 대조군도 둘 다 이겨야 "지지"(방향 효과 아닌 고유 신호 확인).
     * 둘 다 낮으면 "반증", 섞이면 "혼재", n<15 "표본부족".
     */
    fun judge(buckets: List<LabBucket>, signals: List<SignalDef>): List<LabVerdict> {
        val at20 = buckets.filter { it.days == PRIMARY_HORIZON }.associateBy { it.label }
        val base = at20[BASELINE] ?: return emptyList()
        return signals.map { sig ->
            val b = at20[sig.label]
            if (b == null || b.silenced) {
                return@map LabVerdict(sig.label, "표본부족", "n=${b?.n ?: 0} < $MIN_BUCKET_N — 판정 침묵")
            }
            val beatsBaseAvg = b.avgExcessPct > base.avgExcessPct
            val beatsBaseWin = b.winExcessPct > base.winExcessPct
            val vsBase = "baseline 대비 ${fmtSigned(b.avgExcessPct - base.avgExcessPct)}%p·승률 ${fmtSigned(b.winExcessPct - base.winExcessPct)}%p"
            when {
                !beatsBaseAvg && !beatsBaseWin -> LabVerdict(sig.label, "반증", vsBase)
                !(beatsBaseAvg && beatsBaseWin) -> LabVerdict(sig.label, "혼재", vsBase)
                else -> {
                    val ctl = sig.controlLabel?.let { at20[it] }
                    when {
                        sig.controlLabel == null -> LabVerdict(sig.label, "지지", vsBase)
                        ctl == null || ctl.silenced ->
                            LabVerdict(sig.label, "지지", "$vsBase (대조군 ${sig.controlLabel} 표본부족 — 방향 통제 미검)")
                        b.avgExcessPct > ctl.avgExcessPct && b.winExcessPct > ctl.winExcessPct ->
                            LabVerdict(sig.label, "지지", "$vsBase, 대조군도 상회")
                        else -> LabVerdict(sig.label, "대조군미달",
                            "$vsBase 이지만 ${sig.controlLabel} 대비 ${fmtSigned(b.avgExcessPct - ctl.avgExcessPct)}%p·승률 ${fmtSigned(b.winExcessPct - ctl.winExcessPct)}%p — 방향 효과 넘는 고유 신호 없음")
                    }
                }
            }
        }
    }

    // ── 리포트 ───────────────────────────────────────────────────────────────

    @Serializable
    data class SignalLabReport(
        val generatedAt: String,
        val suite: String,
        val universeLabel: String,
        val universeSize: Int,
        val universeCodes: List<String> = emptyList(), // 표본 재현성용(R3 대조 유니버스 문서화)
        val codesScored: Int,
        val benchDays: Int,
        val dateRange: String,
        val joinFailures: Int,
        val buckets: List<LabBucket>,
        val verdicts: List<LabVerdict>,
        val extraStats: List<String> = emptyList(), // 수트 고유 보조 통계(예: rotation의 Spearman·기저율)
        val caveat: String,
        val textReport: String,
    )

    fun renderText(r: SignalLabReport): String = buildString {
        appendLine("═══ 전략 실험실 — 수트 ${r.suite} ═══")
        appendLine("유니버스 ${r.universeLabel}(${r.universeSize}) · 채점 ${r.codesScored}종목 · 기간 ${r.dateRange} · " +
            "벤치 ${r.benchDays}일 · 조인 누락 ${r.joinFailures}일")
        if (r.universeCodes.isNotEmpty()) appendLine("종목: ${r.universeCodes.joinToString(",")}")
        val byLabel = r.buckets.groupBy { it.label }
        val base = byLabel[BASELINE]?.associateBy { it.days } ?: emptyMap()
        val order = listOf(BASELINE, CTL_DOWN, CTL_UP) + byLabel.keys.filter {
            it != BASELINE && it != CTL_DOWN && it != CTL_UP
        }.sorted()
        for (label in order) {
            val bs = byLabel[label] ?: continue
            appendLine()
            appendLine("── $label ──")
            for (b in bs.sortedBy { it.days }) {
                val bl = base[b.days]
                val cmp = if (label == BASELINE || bl == null) ""
                else " (baseline 대비 ${fmtSigned(b.avgExcessPct - bl.avgExcessPct)}%p·승률 ${fmtSigned(b.winExcessPct - bl.winExcessPct)}%p)"
                val mark = if (b.silenced) " [침묵 n<$MIN_BUCKET_N]" else ""
                val dateNote = if (b.n > 0 && b.distinctDates < b.n / 2) " ⚠날짜중복(${b.distinctDates}일)" else ""
                appendLine("  ${b.days}일: n=${b.n} 초과수익 평균 ${b.avgExcessPct}% 중앙값 ${b.medianExcessPct}% " +
                    "승률 ${b.winExcessPct}%$cmp$mark$dateNote")
            }
        }
        if (r.extraStats.isNotEmpty()) {
            appendLine()
            appendLine("── 보조 통계 ──")
            for (s in r.extraStats) appendLine("  $s")
        }
        appendLine()
        appendLine("── 판정(사전 지정: 20일 초과수익·승률 둘 다 baseline 상회, 지정 시 대조군도 상회) ──")
        for (v in r.verdicts) appendLine("  [${v.verdict}] ${v.label} — ${v.reason}")
        appendLine()
        appendLine("caveat: ${r.caveat}")
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    internal fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    private fun List<Double>.averageOr0() = if (isEmpty()) 0.0 else average()
    internal fun fmtSigned(v: Double) = (if (v >= 0) "+" else "") + "%.2f".format(v)
    internal fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
    internal fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
}
