package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.master.StockMaster
import com.haky.edge.util.KST
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 예측 winRate 버킷 1개 × horizon — 캘리브레이션 채점(예측 승률 구간별 실현 양수율). */
@Serializable
data class AnalogCalibrationBucket(
    val label: String,              // "<45" | "45~60" | ">60"
    val days: Int,
    val n: Int,
    val avgPredictedWinRatePct: Double, // 버킷 내 예측 winRate 평균
    val realizedPosRatePct: Double,     // 실현 양수 비율 — 예측과 이게 비슷해야 캘리브레이션 성립
    val silenced: Boolean,              // n<15 — 수치는 병기하되 판정 제외
)

/** horizon 1개(5/20거래일)의 검증 결과. */
@Serializable
data class AnalogHorizonValidation(
    val days: Int,
    val n: Int,                     // 유효 replay 표본 수
    val buckets: List<AnalogCalibrationBucket>,
    val monotonic: Boolean?,        // 버킷 실현 양수율 단조 증가 여부(본선). 판정 가능 버킷<2면 null
    val spearmanMedianVsRealized: Double, // 예측 median × 실현 수익률 순위 상관(판별력)
    val analogSignAccuracyPct: Double,    // sign(예측 median) == sign(실현) 비율
    val naiveSignAccuracyPct: Double,     // sign(직전 20일 수익률) == sign(실현) 비율 — 베이스라인
    val signN: Int,                 // 부호 비교 공통 표본(예측·나이브·실현 전부 0 아님)
)

@Serializable
data class AnalogCodeStat(
    val code: String,
    val name: String,
    val bars: Int,
    val samples: Int,
)

/** GET /analog-validation 응답 — ②-2a Analog 캘리브레이션 실증(1회성 관리 라우트). */
@Serializable
data class AnalogValidationReport(
    val generatedAt: String,
    val codes: List<AnalogCodeStat>,
    val samplingGapDays: Int,
    val totalSamples: Int,
    val avgMatchedN: Double,        // replay별 compute()가 채택한 유사 국면 수 평균(참고)
    val horizons: List<AnalogHorizonValidation>,
    val caveat: String,
    val textReport: String,
)

/**
 * ②-2a Analog 캘리브레이션 실증 — 유사 국면 카드의 forward 분포("5일 승률 75%")가
 * 사후에 맞는 분포였는지 walk-forward replay로 채점한다. LLM 0, 순수 계산.
 *
 * 방법: 각 종목 750봉(폴백 시 보유분)에서 5거래일 간격으로 t를 뽑아 bars[0..t]만으로
 * AnalogService.compute()를 재실행(vectorAt이 look-ahead 안전) → 예측 winRate·median vs
 * t+5/t+20 실현 수익률. 측정은 사전 지정 4종(다중비교 방지):
 *  1. 캘리브레이션: 예측 winRate 3버킷(<45/45~60/>60) → 버킷별 실현 양수율 단조 증가(본선)
 *  2. 판별력: Spearman(예측 median, 실현) — heavy-tail이라 순위 상관(통계감사 규칙)
 *  3. 베이스라인: "직전 20일 추세 유지" 나이브 부호 예측과 부호 일치율 비교
 *  4. n<15 버킷은 침묵(표본 수 병기, 판정 제외)
 *
 * 60일 horizon은 검증 제외(750봉에서 replay 창이 절반으로 줄어 표본 부족) — 5/20일만.
 * 5거래일 간격 샘플링은 클러스터 자기상관 완화용(통계감사 교훈: 창 겹침 비독립).
 */
class AnalogValidationService(
    private val history: DailyHistoryService,
    private val master: StockMaster,
    private val codes: List<String>,
) {
    suspend fun validate(): AnalogValidationReport {
        val samples = mutableListOf<ReplaySample>()
        val codeStats = mutableListOf<AnalogCodeStat>()
        for (code in codes) {
            val bars = runCatching { history.getHistory(code, minBars = TARGET_BARS) }.getOrElse { emptyList() }
            val asc = bars.reversed()
            val collected = collectSamples(asc)
            samples += collected
            codeStats += AnalogCodeStat(
                code = code,
                name = master.findByCode(code)?.name ?: code,
                bars = asc.size,
                samples = collected.size,
            )
        }

        val horizons = HORIZONS.map { h -> scoreHorizon(samples, h) }
        val report = AnalogValidationReport(
            generatedAt = LocalDate.now(KST).toString(),
            codes = codeStats,
            samplingGapDays = SAMPLE_GAP,
            totalSamples = samples.size,
            avgMatchedN = round1(samples.map { it.matchedN.toDouble() }.averageOrZero()),
            horizons = horizons,
            caveat = "관심종목 표본 — 강세주 선택 편향 가능(②-3과 동일). 750봉 미달 종목은 보유분으로 폴백(bars 병기). " +
                "5거래일 간격 샘플링에도 창 겹침(20일 horizon) 잔존 — 표본이 완전히 독립은 아님.",
            textReport = "",
        )
        val rendered = report.copy(textReport = renderText(report))
        println(rendered.textReport)
        return rendered
    }

    companion object {
        const val TARGET_BARS = 750
        const val SAMPLE_GAP = 5
        val HORIZONS = listOf(5, 20)
        private const val MIN_BUCKET_N = 15
        private val BUCKET_LABELS = listOf("<45", "45~60", ">60")

        internal data class ReplaySample(
            val date: String,
            val matchedN: Int,                    // compute()가 채택한 유사 국면 수
            val predicted: Map<Int, Pair<Double, Double>>, // horizon → (winRate, median)
            val realized: Map<Int, Double>,       // horizon → 실현 수익률 %
            val ret20: Double,                    // 직전 20일 수익률(나이브 베이스라인용)
        )

        /**
         * replay 가능 t = [MIN_HISTORY+60, n-1-60], SAMPLE_GAP 간격 — 스펙 사전 지정 범위.
         * 하한은 compute()의 후보(60일 forward 확정 필요)가 최소 1개 생기는 지점,
         * 상한은 라이브와 동일한 60일 여유(5/20일 실현엔 t+20 ≤ n-1이면 족하나 범위 고정 우선).
         */
        internal fun replayIndices(n: Int): List<Int> {
            val lo = AnalogService.MIN_HISTORY + 60
            val hi = n - 1 - 60
            if (hi < lo) return emptyList()
            return (lo..hi step SAMPLE_GAP).toList()
        }

        /** 한 종목의 replay 표본 수집. asc = 오래된 순. */
        internal fun collectSamples(asc: List<DailyBar>): List<ReplaySample> {
            val closes = asc.map { it.close.toDouble() }
            return replayIndices(asc.size).mapNotNull { t ->
                val report = AnalogService.compute("", "", asc[t].date, asc.subList(0, t + 1).reversed())
                if (report.n <= 0) return@mapNotNull null
                val predicted = HORIZONS.mapNotNull { h ->
                    report.horizons.firstOrNull { it.days == h }?.let { h to (it.winRate to it.median) }
                }.toMap()
                if (predicted.size < HORIZONS.size) return@mapNotNull null
                if (closes[t] <= 0 || closes[t - 20] <= 0) return@mapNotNull null
                ReplaySample(
                    date = asc[t].date,
                    matchedN = report.n,
                    predicted = predicted,
                    realized = HORIZONS.associateWith { h -> (closes[t + h] / closes[t] - 1) * 100 },
                    ret20 = (closes[t] / closes[t - 20] - 1) * 100,
                )
            }
        }

        internal fun bucketLabel(winRate: Double): String = when {
            winRate < 45.0 -> "<45"
            winRate <= 60.0 -> "45~60"
            else -> ">60"
        }

        internal fun scoreHorizon(samples: List<ReplaySample>, h: Int): AnalogHorizonValidation {
            val valid = samples.filter { h in it.predicted && h in it.realized }

            val buckets = BUCKET_LABELS.map { label ->
                val inBucket = valid.filter { bucketLabel(it.predicted.getValue(h).first) == label }
                AnalogCalibrationBucket(
                    label = label, days = h, n = inBucket.size,
                    avgPredictedWinRatePct = round1(inBucket.map { it.predicted.getValue(h).first }.averageOrZero()),
                    realizedPosRatePct = round1(
                        if (inBucket.isEmpty()) 0.0
                        else inBucket.count { it.realized.getValue(h) > 0 } * 100.0 / inBucket.size
                    ),
                    silenced = inBucket.size < MIN_BUCKET_N,
                )
            }
            // 본선: 판정 가능(n≥15) 버킷들의 실현 양수율이 예측 순서대로 단조 증가하나
            val judgeable = buckets.filter { !it.silenced }
            val monotonic = if (judgeable.size < 2) null
            else judgeable.zipWithNext().all { (a, b) -> a.realizedPosRatePct <= b.realizedPosRatePct }

            val spearman = BacktestService.spearman(
                valid.map { it.predicted.getValue(h).second },
                valid.map { it.realized.getValue(h) },
            )

            // 부호 비교는 공통 표본(예측 median·나이브 ret20·실현 전부 비0)에서 — 동일 조건 비교
            val signSamples = valid.filter {
                it.predicted.getValue(h).second != 0.0 && it.ret20 != 0.0 && it.realized.getValue(h) != 0.0
            }
            fun accuracy(predSign: (ReplaySample) -> Double): Double =
                if (signSamples.isEmpty()) 0.0
                else signSamples.count { predSign(it) * it.realized.getValue(h) > 0 } * 100.0 / signSamples.size

            return AnalogHorizonValidation(
                days = h,
                n = valid.size,
                buckets = buckets,
                monotonic = monotonic,
                spearmanMedianVsRealized = round3(spearman),
                analogSignAccuracyPct = round1(accuracy { it.predicted.getValue(h).second }),
                naiveSignAccuracyPct = round1(accuracy { it.ret20 }),
                signN = signSamples.size,
            )
        }

        internal fun renderText(r: AnalogValidationReport): String = buildString {
            appendLine("═══ Analog 캘리브레이션 실증(②-2a) ═══")
            appendLine("종목 ${r.codes.size} · 표본 ${r.totalSamples}(간격 ${r.samplingGapDays}거래일) · 평균 매칭 국면 ${r.avgMatchedN}건")
            appendLine(r.codes.joinToString("; ") { "${it.name}=${it.samples}(${it.bars}봉)" })
            for (hv in r.horizons) {
                appendLine()
                appendLine("── ${hv.days}일 forward (n=${hv.n}) ──")
                for (b in hv.buckets) {
                    val mark = if (b.silenced) " [침묵 n<15]" else ""
                    appendLine("  예측 승률 ${b.label}: n=${b.n} 예측평균 ${b.avgPredictedWinRatePct}% → 실현 양수율 ${b.realizedPosRatePct}%$mark")
                }
                appendLine("  단조 증가(본선): ${hv.monotonic ?: "판정 불가(버킷 부족)"}")
                appendLine("  Spearman(예측 median, 실현): ${hv.spearmanMedianVsRealized}")
                appendLine("  부호 일치율: analog ${hv.analogSignAccuracyPct}% vs 나이브(추세 유지) ${hv.naiveSignAccuracyPct}% (n=${hv.signN})")
            }
            appendLine()
            appendLine("caveat: ${r.caveat}")
        }

        private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
        internal fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
        internal fun round3(v: Double) = kotlin.math.round(v * 1000) / 1000.0
    }
}
