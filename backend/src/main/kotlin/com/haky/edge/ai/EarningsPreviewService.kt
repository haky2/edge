package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.dart.QuarterlyIncome
import com.haky.edge.kis.DailyBar
import com.haky.edge.master.StockMaster
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** 과거 정기공시 접수일들의 시장 반응 통계. 수익률은 %. */
@Serializable
data class PastReactions(
    val n: Int,                 // 반응 측정 가능했던 과거 발표 수
    val day1AvgPct: Double,     // 접수일 다음 거래일 평균 수익률
    val day5AvgPct: Double,     // 5거래일 평균 수익률
    val day1WinRatePct: Double, // 익일 상승 비율(0~100)
)

/** GET /earnings-preview/{code} — 실적 발표 프리뷰(F3). 전부 계산, LLM 0. */
@Serializable
data class EarningsPreview(
    val code: String,
    val name: String,
    val date: String,
    val dDay: Int? = null,              // 다음 정기공시 D-day(null=예정 파악 불가)
    val nextReport: String? = null,     // "반기보고서 (2026.06)"
    val nextDate: String? = null,       // "20260814"
    val runRateYoYPct: Double? = null,  // 연환산 run-rate 유지 시 전년 연간 대비 YoY(%)
    val runRateLabel: String? = null,   // 근거 라벨("2026년 1분기 누적 연환산 vs 2025년 연간")
    val pastReactions: PastReactions? = null,
    val caveat: String,
)

/**
 * 실적 발표 프리뷰(F3 슬라이스 3a) — "실적을 들고 넘어갈까"에 데이터로 답한다.
 *  - 서프라이즈 여지: 분기 누적 연환산(run-rate) vs 전년 연간 순이익 → "run-rate 유지 시 YoY +X%".
 *    컨센서스 EPS는 무료 소스가 없어 추정치 비교는 하지 않는다(스펙 고정).
 *  - 과거 반응 패턴: 최근 18개월 정기공시 접수일 × 일봉(F1 DailyHistoryService 캐시 재사용) →
 *    접수일 다음 거래일/5거래일 수익률 통계. 발표 시각(장중/장후) 구분 없이 접수일 다음 거래일로 통일(v1).
 */
class EarningsPreviewService(
    private val dart: DartClient,
    private val history: DailyHistoryService,
    private val master: StockMaster,
) {
    private val cache = ConcurrentHashMap<String, EarningsPreview>()
    private val fileCache = FileCache("earnings_preview", EarningsPreview.serializer())

    suspend fun preview(code: String): EarningsPreview = coroutineScope {
        val today = effectiveMarketDate()
        val key = "$today|$code"
        cache[key]?.let { return@coroutineScope it }
        fileCache.get(key)?.let { cache[key] = it; return@coroutineScope it }

        val scheduleD    = async { runCatching { dart.getEarningsSchedule(code) }.getOrNull() }
        val filingsD     = async { runCatching { dart.getPeriodicFilingDates(code) }.getOrElse { emptyList() } }
        val quarterlyD   = async { runCatching { dart.getQuarterlyIncome(code) }.getOrNull() }
        val financialsD  = async { runCatching { dart.getFinancials(code) }.getOrNull() }
        val barsD        = async { runCatching { history.getHistory(code) }.getOrElse { emptyList() } }
        val name = master.findByCode(code)?.name ?: code

        val schedule = scheduleD.await()
        val quarterly = quarterlyD.await()
        val lastAnnualNet = financialsD.await()?.netIncome
        val reactions = computeReactions(filingsD.await(), barsD.await().asReversed())
        val (yoy, yoyLabel) = runRateYoY(quarterly, lastAnnualNet, financialsD.await()?.fiscalYear)

        val preview = EarningsPreview(
            code = code, name = name, date = today,
            dDay = schedule?.daysUntil,
            nextReport = schedule?.reportName,
            nextDate = schedule?.dueDate,
            runRateYoYPct = yoy,
            runRateLabel = yoyLabel,
            pastReactions = reactions,
            caveat = buildCaveat(reactions),
        )
        cache[key] = preview; fileCache.put(key, preview)
        preview
    }

    /** F3 리뷰(3c) 결과 — Slack 신호 메시지용. 금액은 억원. */
    data class EarningsReview(
        val periodLabel: String,   // "2026년 반기"
        val actualEok: Long,
        val expectedEok: Long,
        val diffPct: Double,
        val verdict: String,       // "상회" | "부합" | "하회"
    )

    /**
     * 새 정기보고서 접수 감지 시 호출(3c): 실제 누적 순이익 vs 직전 보고서 run-rate 예상.
     * "직전 예상"은 저장해 둘 필요 없이 리뷰 시점에 직전 보고서 누적 × 배수로 재계산(멱등).
     * 적자 구간·데이터 없음·보고서명 파싱 실패는 null(조용히 skip).
     */
    suspend fun review(code: String, reportName: String): EarningsReview? {
        val plan = reviewPlan(reportName) ?: return null
        val actual = dart.getCumulativeNetIncome(code, plan.year, plan.reprtCode) ?: return null
        val prior = dart.getCumulativeNetIncome(code, plan.priorYear, plan.priorReprtCode) ?: return null
        return reviewVerdict(plan, actual, prior)
    }

    /** 리뷰 계산 계획: 새 보고서 → (조회 대상, 직전 보고서, run-rate 환산 배수). */
    internal data class ReviewPlan(
        val year: Int, val reprtCode: String,
        val priorYear: Int, val priorReprtCode: String,
        val factor: Double,        // expected = 직전 누적 × factor
        val periodLabel: String,
    )

    companion object {
        const val DAY5 = 5
        const val REVIEW_BAND_PCT = 10.0   // ±10% 안이면 "부합"

        /**
         * 새 보고서명("분기보고서 (2026.03)" 등) → 리뷰 계산 계획(순수 함수).
         * 1Q←전년 연간÷4, 반기←1Q×2, 3Q누적←반기×1.5, 연간←3Q×4/3. 정정 공시는 제외.
         */
        internal fun reviewPlan(reportName: String): ReviewPlan? {
            if (reportName.contains("정정")) return null
            val m = Regex("""\((\d{4})\.(\d{2})\)""").find(reportName) ?: return null
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            return when {
                reportName.contains("분기보고서") && month == 3 ->
                    ReviewPlan(year, "11013", year - 1, "11011", 0.25, "${year}년 1분기")
                reportName.contains("반기보고서") && month == 6 ->
                    ReviewPlan(year, "11012", year, "11013", 2.0, "${year}년 반기")
                reportName.contains("분기보고서") && month == 9 ->
                    ReviewPlan(year, "11014", year, "11012", 1.5, "${year}년 3분기")
                reportName.contains("사업보고서") && month == 12 ->
                    ReviewPlan(year, "11011", year, "11014", 4.0 / 3.0, "${year}년 연간")
                else -> null
            }
        }

        /** 실제 vs run-rate 예상 판정(순수 함수). 적자·0 구간은 null(비율 판정이 무의미). */
        internal fun reviewVerdict(plan: ReviewPlan, actualCum: Long, priorCum: Long): EarningsReview? {
            if (actualCum <= 0 || priorCum <= 0) return null
            val expected = priorCum * plan.factor
            if (expected <= 0) return null
            val diffPct = (actualCum / expected - 1) * 100
            val verdict = when {
                diffPct >= REVIEW_BAND_PCT -> "상회"
                diffPct <= -REVIEW_BAND_PCT -> "하회"
                else -> "부합"
            }
            return EarningsReview(
                periodLabel = plan.periodLabel,
                actualEok = actualCum / 100_000_000,
                expectedEok = (expected / 100_000_000).toLong(),
                diffPct = round1(diffPct),
                verdict = verdict,
            )
        }

        /**
         * 과거 발표 반응 통계(순수 함수). filingDates=YYYYMMDD 최신순, barsAsc=일봉 오래된 순.
         * 기준봉 = 접수일 이전(당일 포함) 마지막 거래일 종가, 반응 = 접수일 *다음* 거래일부터.
         * forward 봉이 부족한 발표(직전 발표 등)는 해당 horizon에서 제외.
         */
        fun computeReactions(filingDates: List<String>, barsAsc: List<DailyBar>): PastReactions? {
            if (filingDates.isEmpty() || barsAsc.size < DAY5 + 2) return null
            val day1 = mutableListOf<Double>()
            val day5 = mutableListOf<Double>()
            for (ymd in filingDates) {
                val baseIdx = barsAsc.indexOfLast { it.date <= ymd }
                if (baseIdx < 0) continue                        // 이력 범위 밖(너무 오래됨)
                val base = barsAsc[baseIdx].close
                if (base <= 0) continue
                if (baseIdx + 1 <= barsAsc.lastIndex) day1 += (barsAsc[baseIdx + 1].close.toDouble() / base - 1) * 100
                if (baseIdx + DAY5 <= barsAsc.lastIndex) day5 += (barsAsc[baseIdx + DAY5].close.toDouble() / base - 1) * 100
            }
            if (day1.isEmpty()) return null
            return PastReactions(
                n = day1.size,
                day1AvgPct = round2(day1.average()),
                day5AvgPct = round2(if (day5.isEmpty()) 0.0 else day5.average()),
                day1WinRatePct = round1(day1.count { it > 0 } * 100.0 / day1.size),
            )
        }

        /**
         * run-rate YoY(순수 함수): 분기 누적 연환산(1Q×4·반기×2·3Q×4/3) vs 전년 *연간* 순이익.
         * "지금 속도가 유지되면 올해는 작년 대비 +X%" — 적자·데이터 부족은 null.
         */
        fun runRateYoY(quarterly: QuarterlyIncome?, lastAnnualNet: Long?, lastAnnualYear: Int?): Pair<Double?, String?> {
            val ni = quarterly?.netIncome ?: return null to null
            if (lastAnnualNet == null || lastAnnualNet <= 0 || ni <= 0) return null to null
            val multiplier = when {
                quarterly.label.contains("1분기") -> 4.0
                quarterly.label.contains("반기")  -> 2.0
                quarterly.label.contains("3분기") -> 4.0 / 3.0
                else -> return null to null
            }
            val yoy = round1((ni * multiplier / lastAnnualNet - 1) * 100)
            val yearLabel = lastAnnualYear?.let { "${it}년" } ?: "작년"
            return yoy to "${quarterly.label} 누적 연환산 vs $yearLabel 연간"
        }

        fun buildCaveat(reactions: PastReactions?): String = buildString {
            append("연환산은 단순 계산(계절성 미반영)이고 컨센서스 추정치 비교가 아닙니다. ")
            if (reactions != null && reactions.n < 15) append("과거 반응 표본 ${reactions.n}건 — 참고 수준입니다. ")
            append("과거 통계일 뿐 이번 발표 반응을 보장하지 않습니다.")
        }

        private fun round1(v: Double) = kotlin.math.round(v * 10) / 10
        private fun round2(v: Double) = kotlin.math.round(v * 100) / 100
    }
}
