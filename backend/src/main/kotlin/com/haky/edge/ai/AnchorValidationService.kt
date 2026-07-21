package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.master.StockMaster
import com.haky.edge.util.KST
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 앵커 이벤트 버킷 1개(전 종목 풀링) × forward 5·10거래일 통계. */
@Serializable
data class AnchorBucket(
    val label: String,     // touchLow20 | breakoutHigh20 | touchMa20 | touchMa60 | ctlDown2 | ctlUp2 | baseline
    val n: Int,
    val avgFwd5Pct: Double,
    val win5Pct: Double,   // fwd5 > 0 비율
    val avgFwd10Pct: Double,
    val win10Pct: Double,
)

/** GET /anchor-validation 응답 — ②-3 기술적 앵커 룰 실증(1회성 관리 라우트). */
@Serializable
data class AnchorValidationReport(
    val generatedAt: String,
    val codes: Int,             // 검증 종목 수
    val tradingDaysPerCode: Int, // 대략적 표본 길이(최소 종목 기준)
    val buckets: List<AnchorBucket>,
    val caveat: String,
)

/**
 * ②-3 기술적 앵커 실증 — 공격 모드가 매매 레벨의 근거로 쓰는 "기술적 앵커"
 * (20일 저점/고점·MA20·MA60, **종가 기준** — AnalysisService.technicalAnchorsText와 동일 정의)가
 * 실제로 지지/저항/돌파 신호로 기능하는지 관심종목 2년 일봉으로 채점한다. LLM 0.
 *
 * ⚠️ K5(2026-07-21): **정본은 signal-lab anchor 수트**(GET /signal-lab?suite=anchor). 라우트 등록 해제됨.
 * 이 파일은 원 ②-3 리포트(docs/anchor-validation-2026-07.md, forward 5/10 **원수익률**) 재현·단위테스트용으로 동결.
 * signal-lab은 5/20 **코스피 초과수익**·클러스터 dedupe 기준이라 방법론이 다르다 —
 * touchLow20 판정이 원 리포트('약한 지지')와 signal-lab('반증')에서 뒤집힌다(R3 섹션에 기록). 신규 실측은 signal-lab을 쓸 것.
 *
 * 교란 통제: low20 터치일은 대개 하락일이다 — 무조건 baseline과만 비교하면 "하락 후 반등"
 * 일반 효과(평균회귀)와 "앵커 레벨" 고유 효과가 섞인다. 그래서 **하락일(-2%)·상승일(+2%)
 * 대조군**을 함께 채점한다: touchLow20이 ctlDown2보다 낫지 않으면 앵커의 고유 신호는 없다.
 * lookahead 차단: t일 이벤트의 앵커는 t-1까지의 20/60봉으로만 계산.
 */
class AnchorValidationService(
    private val history: DailyHistoryService,
    private val master: StockMaster,
    private val codes: List<String>,
) {
    suspend fun validate(): AnchorValidationReport {
        val pooled = mutableMapOf<String, MutableList<Pair<Double, Double>>>() // label → [(fwd5, fwd10)]
        var minDays = Int.MAX_VALUE
        var used = 0
        for (code in codes) {
            val barsAsc = runCatching { history.getHistory(code).asReversed() }.getOrElse { emptyList() }
            if (barsAsc.size < 80) continue
            used++
            minDays = minOf(minDays, barsAsc.size)
            collectEvents(barsAsc).forEach { (label, list) ->
                pooled.getOrPut(label) { mutableListOf() } += list
            }
        }
        val order = listOf("touchLow20", "ctlDown2", "breakoutHigh20", "ctlUp2", "touchMa20", "touchMa60", "baseline")
        val buckets = order.mapNotNull { label ->
            val rs = pooled[label] ?: return@mapNotNull null
            if (rs.isEmpty()) return@mapNotNull null
            AnchorBucket(
                label = label, n = rs.size,
                avgFwd5Pct = round2(rs.map { it.first }.average()),
                win5Pct = round1(rs.count { it.first > 0 } * 100.0 / rs.size),
                avgFwd10Pct = round2(rs.map { it.second }.average()),
                win10Pct = round1(rs.count { it.second > 0 } * 100.0 / rs.size),
            )
        }
        return AnchorValidationReport(
            generatedAt = LocalDate.now(KST).toString(),
            codes = used,
            tradingDaysPerCode = if (minDays == Int.MAX_VALUE) 0 else minDays,
            buckets = buckets,
            caveat = "종가 기준 앵커(technicalAnchorsText 동일 정의). 대조군(ctlDown2/ctlUp2)보다 낫지 않으면 앵커 고유 신호 없음. 관심종목 표본 — 생존·상승 편향 가능.",
        )
    }

    companion object {
        const val TOUCH_TOLERANCE = 1.005   // 레벨 ±0.5% 이내 접근을 터치로
        const val CTL_MOVE = 0.02           // 대조군 하락/상승일 기준 ±2%

        /**
         * 한 종목의 이벤트별 (fwd5, fwd10) 수익률 수집. barsAsc = 오래된 순.
         * t 범위: [60, size-11] — 앵커 계산(직전 60봉)과 forward 10봉 확보.
         * 연속 발화 중복 제거: 같은 유형이 전일에도 발화했으면 건너뜀(첫 터치만).
         */
        internal fun collectEvents(barsAsc: List<DailyBar>): Map<String, List<Pair<Double, Double>>> {
            val out = mutableMapOf<String, MutableList<Pair<Double, Double>>>()
            val prevFired = mutableMapOf<String, Boolean>()
            for (t in 60..barsAsc.size - 11) {
                val prior20 = barsAsc.subList(t - 20, t).map { it.close }
                val prior60 = barsAsc.subList(t - 60, t).map { it.close }
                val low20 = prior20.min()
                val high20 = prior20.max()
                val ma20 = prior20.average()
                val ma60 = prior60.average()
                val bar = barsAsc[t]
                val prevClose = barsAsc[t - 1].close
                if (bar.close <= 0 || prevClose <= 0) continue

                val fwd = forwardPair(barsAsc, t) ?: continue
                val dayChange = bar.close.toDouble() / prevClose - 1

                val fired = mapOf(
                    "touchLow20" to (bar.low <= low20 * TOUCH_TOLERANCE),
                    "breakoutHigh20" to (bar.close > high20),
                    "touchMa20" to (prevClose > ma20 && bar.low <= ma20 * TOUCH_TOLERANCE),
                    "touchMa60" to (prevClose > ma60 && bar.low <= ma60 * TOUCH_TOLERANCE),
                    "ctlDown2" to (dayChange <= -CTL_MOVE),
                    "ctlUp2" to (dayChange >= CTL_MOVE),
                    "baseline" to true,
                )
                for ((label, hit) in fired) {
                    // baseline·대조군은 중복 제거 없음(무조건부 분포), 앵커 이벤트만 첫 터치 채택
                    val dedupe = label.startsWith("touch") || label.startsWith("breakout")
                    if (hit && (!dedupe || prevFired[label] != true)) {
                        out.getOrPut(label) { mutableListOf() } += fwd
                    }
                    prevFired[label] = hit
                }
            }
            return out
        }

        /** t일 종가 → t+5·t+10 종가 수익률(%). */
        internal fun forwardPair(barsAsc: List<DailyBar>, t: Int): Pair<Double, Double>? {
            if (t + 10 > barsAsc.lastIndex) return null
            val base = barsAsc[t].close
            if (base <= 0) return null
            val f5 = (barsAsc[t + 5].close.toDouble() / base - 1) * 100
            val f10 = (barsAsc[t + 10].close.toDouble() / base - 1) * 100
            return round2(f5) to round2(f10)
        }

        internal fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
        internal fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
    }
}
