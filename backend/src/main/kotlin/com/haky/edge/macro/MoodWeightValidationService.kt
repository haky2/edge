package com.haky.edge.macro

import com.haky.edge.ai.BacktestService
import com.haky.edge.util.KST
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** 지표 1개의 단변량 실측 결과. expectedDir = 현행 가중치 부호. */
@Serializable
data class MoodIndicatorFinding(
    val key: String,
    val weight: Double,          // 현행 LEADING_WEIGHTS 값
    val n: Int,                  // 전체 페어 수
    val nFiltered: Int,          // 노이즈 필터(|등락|<자기 중앙값 제외) 후
    val agreeRate: Double,       // 부호 일치율(기대 부호 적용, 필터 후)
    val spearman: Double,        // Spearman(지표등락×기대부호, 코스피등락) — 양수면 현행 부호 지지
    val t: Double,
    val verdict: String,         // SUPPORTED / CONTRADICTED / INCONCLUSIVE / INSUFFICIENT (전 기간)
    val firstHalfVerdict: String, // 전반(적합 구간)만의 판정 — 교정 후보 도출용(홀드아웃 규율)
)

/** 가중치 세트 1개의 3분류 재현 성적. */
@Serializable
data class MoodSystemReplay(
    val label: String,           // current | drop-unsupported | flip-contradicted
    val weights: Map<String, Double>,
    val nFull: Int,
    val accuracyFullPct: Double,
    val accuracyFirstHalfPct: Double,  // 적합 구간(교정 후보를 여기서 도출)
    val accuracyHoldoutPct: Double,    // 후반 검증 구간 — 채택 판단은 여기서만
    val nHoldout: Int,
    val predClassDist: Map<String, Int>, // 전 기간 예측 클래스 분포(중립 쏠림 점검)
)

/** GET /moodweight-validation 응답 — ③ MoodLog 가중치 실측(1회성 관리 라우트). */
@Serializable
data class MoodWeightValidationReport(
    val generatedAt: String,
    val periodStart: String,
    val periodEnd: String,
    val holdoutStart: String,          // 이 날짜부터 검증 구간
    val kospiDays: Int,
    val actualClassDist: Map<String, Int>, // classifyActual ±0.3% 밴드의 실제 분포
    val majorityClassPct: Double,          // 다수 클래스 비율(기준선 — 33.3%와 병기)
    val findings: List<MoodIndicatorFinding>,
    val replays: List<MoodSystemReplay>,
    val recommendation: String,
    val caveat: String,
    val textReport: String,
)

/**
 * ③ MoodLog 방향예측 가중치 실측 — LEADING_WEIGHTS(손짐작)와 composite ±0.5 임계를
 * 라이브 21건이 아니라 Yahoo 2년 이력으로 채점한다. LLM 0, 순수 계산.
 *
 * 정렬(스펙 사전 지정): 미국 지표·환율 전부 **T-1 세션 등락 → 한국 T일 예측**
 * (08시 KST엔 당일 미형성 — usdkrw·dxy 포함). 각 코스피 거래일 T에 대해 지표별
 * "T보다 앞선 마지막 봉"의 등락률을 쓴다(갭 ≤ 7일 — 연휴 방어, 라이브의 스냅샷 동작과 동일).
 *
 * **선물 3종(nqfut·esfut·ymfut)은 백테스트 불가** — 라이브는 08시 야간 세션 중간 스냅샷을
 * 쓰는데 이력의 선물 일봉 종가는 지수 종가와 사실상 동일 정보라 재구성 불가. 지수·환율
 * 8지표분만 검증하고 선물 기여분은 라이브 MoodLog 성숙 후(9월) 재실측.
 *
 * 측정(사전 지정 — 다중비교 방지):
 *  1. 지표별 단변량 — 부호 일치율(노이즈 필터: |등락|<자기 중앙값 제외) + Spearman(heavy-tail,
 *     통계감사 규칙). 판정 임계는 D1(sensitivity-validation) 재사용: SUPPORTED(일치≥54% && ρ>0,
 *     또는 t≥2) / CONTRADICTED(일치≤46% && t≤−2) / INCONCLUSIVE / INSUFFICIENT(n<80).
 *  2. 현행 시스템 재현 — inferDirectionWith(정본 공유)를 이력에 적용, 3분류 정확도 vs
 *     기준선(균등 33.3% + 다수 클래스 비율 둘 다 — 감사 4탄 F5 교훈).
 *  3. 클래스 구조 — classifyActual ±0.3% 밴드의 실제 분포 + 예측 클래스 분포(중립 쏠림).
 *  4. 교정안 — 전반(적합)에서만 후보 도출(무근거 지표 제거·CONTRADICTED 부호 반전),
 *     후반(홀드아웃) 정확도로만 채택 판단. **개선 기준: 홀드아웃 +2%p 이상**(n≈240 이항
 *     se≈3.2%p의 절반 남짓 — 그 미만은 노이즈로 보고 현행 유지 + 기록만). 연속 최적화 금지.
 */
class MoodWeightValidationService(private val yahoo: YahooHistoryClient) {

    suspend fun validate(): MoodWeightValidationReport {
        // 1) 이력 수집 — 지표 8종(UTC) + 코스피(Asia/Seoul)
        val changesByKey = mutableMapOf<String, List<Pair<LocalDate, Double>>>()
        for ((key, symbol) in INDICATOR_SYMBOLS) {
            val closes = runCatching { yahoo.dailyCloses(symbol, range = "2y", zone = ZoneOffset.UTC) }
                .getOrElse { e -> println("[MoodWeightValidation] $key($symbol) 실패: ${e.message}"); emptyList() }
            if (closes.size >= 100) changesByKey[key] = toChanges(closes)
            else println("[MoodWeightValidation] $key 표본 부족(${closes.size}) — 제외")
        }
        val kospiCloses = yahoo.dailyCloses("^KS11", range = "2y", zone = SEOUL)
        val kospiChanges = toChanges(kospiCloses)

        // 2) 정렬 — 코스피 거래일 T × 지표 T-1(직전 봉, 갭≤7일)
        val rows = alignRows(kospiChanges, changesByKey)
        val splitIdx = rows.size / 2
        val firstHalf = rows.subList(0, splitIdx)
        val holdout = rows.subList(splitIdx, rows.size)

        // 3) 단변량(전 기간 + 전반)
        val findings = MarketMoodLogService.LEADING_WEIGHTS
            .filterKeys { it in changesByKey }
            .map { (key, w) ->
                val full = univariate(rows, key, w)
                val first = univariate(firstHalf, key, w)
                MoodIndicatorFinding(
                    key = key, weight = w,
                    n = full.n, nFiltered = full.nFiltered,
                    agreeRate = full.agreeRate.round3(), spearman = full.rho.round3(), t = full.t.round2(),
                    verdict = full.verdict,
                    firstHalfVerdict = first.verdict,
                )
            }

        // 4) 재현 — 현행 + 전반 판정 기반 교정 후보 2종
        val current = MarketMoodLogService.LEADING_WEIGHTS.filterKeys { it in changesByKey }
        val candidates = linkedMapOf("current" to current)
        val supported1H = findings.filter { it.firstHalfVerdict == "SUPPORTED" }.map { it.key }.toSet()
        val contradicted1H = findings.filter { it.firstHalfVerdict == "CONTRADICTED" }.map { it.key }.toSet()
        if (supported1H.isNotEmpty() && supported1H.size < current.size) {
            candidates["drop-unsupported"] = current.filterKeys { it in supported1H }
        }
        if (contradicted1H.isNotEmpty()) {
            candidates["flip-contradicted"] = current.mapValues { (k, w) -> if (k in contradicted1H) -w else w }
        }
        val replays = candidates.map { (label, weights) ->
            MoodSystemReplay(
                label = label, weights = weights,
                nFull = rows.size,
                accuracyFullPct = accuracy(rows, weights).round1(),
                accuracyFirstHalfPct = accuracy(firstHalf, weights).round1(),
                accuracyHoldoutPct = accuracy(holdout, weights).round1(),
                nHoldout = holdout.size,
                predClassDist = rows.groupingBy {
                    MarketMoodLogService.inferDirectionWith(weights, it.changes)
                }.eachCount(),
            )
        }

        // 5) 클래스 구조 + 기준선
        val actualDist = rows.groupingBy { MarketMoodLogService.classifyActual(it.kospiChg) }.eachCount()
        val majorityPct = if (rows.isEmpty()) 0.0 else actualDist.values.max() * 100.0 / rows.size

        val currentReplay = replays.first { it.label == "current" }
        val best = replays.maxBy { it.accuracyHoldoutPct }
        val recommendation = when {
            best.label == "current" || best.accuracyHoldoutPct - currentReplay.accuracyHoldoutPct < ADOPT_MARGIN_PP ->
                "현행 유지 — 홀드아웃에서 ${ADOPT_MARGIN_PP}%p 이상 나은 교정안 없음(사전 지정 기준). 실측 결과만 기록."
            else ->
                "교정 후보 채택 검토: ${best.label} (홀드아웃 ${best.accuracyHoldoutPct}% vs 현행 ${currentReplay.accuracyHoldoutPct}%)."
        }

        val report = MoodWeightValidationReport(
            generatedAt = LocalDate.now(KST).toString(),
            periodStart = rows.firstOrNull()?.date?.toString() ?: "-",
            periodEnd = rows.lastOrNull()?.date?.toString() ?: "-",
            holdoutStart = holdout.firstOrNull()?.date?.toString() ?: "-",
            kospiDays = rows.size,
            actualClassDist = actualDist,
            majorityClassPct = majorityPct.round1(),
            findings = findings,
            replays = replays,
            recommendation = recommendation,
            caveat = "선물 3종(nqfut·esfut·ymfut)은 08시 스냅샷 재구성 불가로 검증 제외 — 지수·환율 8지표분만. " +
                "dxy·usdkrw는 라이브 08시 스냅샷과 Yahoo 일봉 종가의 근사 차이 있음. " +
                "2년 = 특정 국면(2024H2~2026H1) 편중. 코스피 방향의 일별 예측 가능성 자체가 낮을 수 있음 — 무근거도 정직한 결과.",
            textReport = "",
        )
        val rendered = report.copy(textReport = renderText(report))
        println(rendered.textReport)
        return rendered
    }

    companion object {
        internal const val MIN_N = 80              // D1(sensitivity) 판정 임계 재사용
        internal const val MAX_GAP_DAYS = 7L       // 지표 직전 봉 허용 갭(연휴 방어)
        internal const val ADOPT_MARGIN_PP = 2.0   // 홀드아웃 개선 채택 기준(사전 지정)

        private val SEOUL = ZoneId.of("Asia/Seoul")

        // MoodLog 지표 키 → Yahoo 심볼. 선물 3종은 의도적으로 없음(재구성 불가 — 클래스 주석).
        internal val INDICATOR_SYMBOLS = mapOf(
            "nasdaq" to "^IXIC", "sp500" to "^GSPC", "dow" to "^DJI",
            "ewy" to "EWY", "sox" to "^SOX", "rut" to "^RUT",
            "dxy" to "DX-Y.NYB", "usdkrw" to "KRW=X",
        )

        /** (날짜, 종가) → (날짜, 전일 대비 %). 첫 항목은 기준일이라 제외. */
        internal fun toChanges(closes: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>> =
            closes.zipWithNext { (_, prev), (date, cur) ->
                date to (if (prev > 0.0) (cur - prev) / prev * 100.0 else 0.0)
            }

        /** 코스피 거래일 T 1행 — 지표별 T-1(직전 봉) 등락 맵. */
        internal data class Row(val date: LocalDate, val kospiChg: Double, val changes: Map<String, Double>)

        /**
         * 정렬: 각 코스피 거래일 T에 대해 지표별로 "T보다 앞선 마지막 봉"의 등락(갭 ≤ MAX_GAP_DAYS).
         * 지표 봉이 없으면 그 지표만 생략(라이브 inferDirection의 부분 평가와 동일).
         */
        internal fun alignRows(
            kospiChanges: List<Pair<LocalDate, Double>>,
            changesByKey: Map<String, List<Pair<LocalDate, Double>>>,
        ): List<Row> {
            val sortedByKey = changesByKey.mapValues { (_, v) -> v.sortedBy { it.first } }
            return kospiChanges.map { (t, kospiChg) ->
                val changes = buildMap {
                    for ((key, series) in sortedByKey) {
                        val idx = series.binarySearchBy(t) { it.first }
                            .let { if (it >= 0) it else -(it + 1) } - 1   // t보다 앞선 마지막 인덱스
                        val (d, chg) = series.getOrNull(idx) ?: continue
                        if (ChronoUnit.DAYS.between(d, t) <= MAX_GAP_DAYS) put(key, chg)
                    }
                }
                Row(t, kospiChg, changes)
            }.filter { it.changes.isNotEmpty() }
        }

        internal data class UnivariateStats(
            val n: Int, val nFiltered: Int, val agreeRate: Double,
            val rho: Double, val t: Double, val verdict: String,
        )

        /**
         * 지표 1개 단변량 — x축은 등락×기대부호(가중치 부호)로 뒤집어 "ρ>0 = 현행 부호 지지"로 통일.
         * 일치율은 노이즈 필터(|등락| ≥ 자기 중앙값, 양쪽 비0) 후. D1 computeStats와 같은 구조,
         * 상관만 Pearson → Spearman(등락률 heavy-tail — 통계감사 규칙).
         */
        internal fun univariate(rows: List<Row>, key: String, weight: Double): UnivariateStats {
            val dir = if (weight >= 0) 1 else -1
            val pairs = rows.mapNotNull { r -> r.changes[key]?.let { it to r.kospiChg } }
            if (pairs.isEmpty()) return UnivariateStats(0, 0, 0.0, 0.0, 0.0, "INSUFFICIENT")

            val xs = pairs.map { it.first * dir }
            val ys = pairs.map { it.second }
            val rho = BacktestService.spearman(xs, ys)
            val n = pairs.size
            val t = when {
                n <= 2 -> 0.0
                abs(rho) >= 1.0 -> rho * 1e6
                else -> rho * sqrt((n - 2).toDouble()) / sqrt(1 - rho * rho)
            }

            val absMoves = pairs.map { abs(it.first) }.sorted()
            val median = absMoves[absMoves.size / 2].takeIf { it > 0.0 } ?: Double.MIN_VALUE
            val filtered = pairs.filter { abs(it.first) >= median && it.first != 0.0 && it.second != 0.0 }
            val agree = if (filtered.isEmpty()) 0.0
            else filtered.count { (x, y) -> (if (x > 0) dir else -dir).let { e -> (y > 0 && e > 0) || (y < 0 && e < 0) } }
                .toDouble() / filtered.size

            val verdict = when {
                filtered.size < MIN_N -> "INSUFFICIENT"
                (agree >= 0.54 && rho > 0) || t >= 2.0 -> "SUPPORTED"
                agree <= 0.46 && t <= -2.0 -> "CONTRADICTED"
                else -> "INCONCLUSIVE"
            }
            return UnivariateStats(n, filtered.size, agree, rho, t, verdict)
        }

        /** 가중치 세트의 3분류 정확도(%) — 예측·실제 모두 정본 함수 재사용. */
        internal fun accuracy(rows: List<Row>, weights: Map<String, Double>): Double {
            if (rows.isEmpty()) return 0.0
            val correct = rows.count {
                MarketMoodLogService.inferDirectionWith(weights, it.changes) ==
                    MarketMoodLogService.classifyActual(it.kospiChg)
            }
            return correct * 100.0 / rows.size
        }

        internal fun renderText(r: MoodWeightValidationReport): String = buildString {
            appendLine("═══ MoodLog 가중치 실측(③) ═══")
            appendLine("기간 ${r.periodStart}~${r.periodEnd} (${r.kospiDays}거래일) · 홀드아웃 ${r.holdoutStart}~")
            appendLine("실제 분포: ${r.actualClassDist} · 다수 클래스 ${r.majorityClassPct}% (균등 33.3%)")
            appendLine()
            appendLine("── 지표별 단변량 (기대 부호 적용, ρ>0=현행 지지) ──")
            for (f in r.findings.sortedByDescending { it.spearman }) {
                appendLine("  [${f.verdict}] ${f.key}(w=${f.weight}): 일치 ${"%.1f".format(f.agreeRate * 100)}% " +
                    "(n=${f.nFiltered}/${f.n}) ρ=${f.spearman} t=${f.t} · 전반 판정 ${f.firstHalfVerdict}")
            }
            appendLine()
            appendLine("── 시스템 재현 (3분류 정확도) ──")
            for (rep in r.replays) {
                appendLine("  ${rep.label}: 전체 ${rep.accuracyFullPct}% · 전반 ${rep.accuracyFirstHalfPct}% · " +
                    "홀드아웃 ${rep.accuracyHoldoutPct}%(n=${rep.nHoldout}) · 예측 분포 ${rep.predClassDist}")
            }
            appendLine()
            appendLine("권고: ${r.recommendation}")
            appendLine("caveat: ${r.caveat}")
        }

        internal fun Double.round1() = kotlin.math.round(this * 10) / 10
        internal fun Double.round2() = kotlin.math.round(this * 100) / 100
        internal fun Double.round3() = kotlin.math.round(this * 1000) / 1000
    }
}
