package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.master.StockMaster
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** 오늘의 상태 벡터(설명용 — 카드에 그대로 노출). */
@Serializable
data class AnalogVector(
    val pos52w: Double,       // 52주 위치(%)
    val ret20: Double,        // 최근 20거래일 수익률(%)
    val volumeRatio: Double,  // 당일 거래량 / 20일 평균
    val rsi14: Double,        // RSI(14, Wilder)
)

/** horizon 1개(5/20/60거래일)의 forward return 분포. 수익률은 %. */
@Serializable
data class AnalogHorizon(
    val days: Int,
    val winRate: Double,      // 양수 비율(0~100)
    val median: Double,
    val avg: Double,
    val min: Double,
    val max: Double,
)

/** GET /analog/{code} 응답. n=0이면 horizons 비어 있음(caveat에 사유). */
@Serializable
data class AnalogReport(
    val code: String,
    val name: String,
    val date: String,
    val vectorToday: AnalogVector? = null,
    val n: Int,
    val matchedDates: List<String> = emptyList(), // 채택된 유사일(YYYYMMDD, 최신순) — 카드 보조 표기용
    val horizons: List<AnalogHorizon> = emptyList(),
    val peersIncluded: Boolean = false,
    val caveat: String,
)

/**
 * 유사 국면 통계(F1) — 슬라이스 1b. LLM 호출 0, 전부 계산.
 *
 * 오늘의 상태 벡터(52주 위치·20일 수익률·거래량 비율·RSI14 — v1은 수급 제외 4피처)와
 * 유사했던 과거 시점들을 찾아 이후 5/20/60거래일 실제 수익률 분포를 돌려준다.
 * "지금 사면 어디로 가나"의 기저율 답변 — 예측이 아니라 과거 분포.
 *
 * 설계 가드(과최적화 방지, 스펙 고정): 피처 4개, 전부 동일 가중(튜닝 금지),
 * z-정규화는 해당 종목 자기 분포 기준, ±5거래일 클러스터로 같은 국면 중복 집계 방지.
 */
class AnalogService(
    private val history: DailyHistoryService,
    private val master: StockMaster,
) {
    private val cache = ConcurrentHashMap<String, AnalogReport>()
    private val fileCache = FileCache("analog", AnalogReport.serializer())

    suspend fun analog(code: String): AnalogReport {
        val today = effectiveMarketDate()
        val key = "$today|$code"
        cache[key]?.let { return it }
        fileCache.get(key)?.let { cache[key] = it; return it }

        val name = master.findByCode(code)?.name ?: code
        val bars = history.getHistory(code)              // 최신이 앞
        val report = compute(code, name, today, bars)
        cache[key] = report; fileCache.put(key, report)
        return report
    }

    companion object {
        const val K_NEAREST = 30          // 거리 하위 k(클러스터 전)
        const val CLUSTER_GAP = 5         // ±5거래일 이내는 같은 국면
        const val MIN_HISTORY = 252       // 52주 위치 계산에 필요한 최소 이력
        val HORIZONS = listOf(5, 20, 60)
        // ②-2a 실측(2026-07, 관심 11종목 885회 walk-forward replay): 예측 승률 버킷과 실현 양수율 무관
        // (단조성 없음·Spearman 0.06·부호 일치율 51%=나이브 동률) — 중앙값도 같은 기각 대상(X2: 배지 무채색).
        const val CAVEAT_BASE = "과거 분포일 뿐 미래를 보장하지 않습니다. 실측(11종목 885회 재현, 2026-07)에서 예측 통계(승률·중앙값)의 예측력은 확인되지 않았습니다 — 범위(최소~최대) 참고용."

        /** bars: 최신이 앞(KIS 응답 그대로). 순수 함수 — 테스트 대상. */
        fun compute(code: String, name: String, today: String, bars: List<DailyBar>): AnalogReport {
            val asc = bars.reversed()                    // 오래된 순
            val closes = asc.map { it.close.toDouble() }
            val n = asc.size
            val maxHorizon = HORIZONS.max()

            val todayIdx = n - 1
            val todayVec = vectorAt(asc, closes, todayIdx)
            if (n < MIN_HISTORY + maxHorizon + 1 || todayVec == null) {
                return AnalogReport(
                    code = code, name = name, date = today,
                    vectorToday = todayVec, n = 0,
                    caveat = "이력이 부족해 유사 국면을 계산할 수 없습니다(필요 ${MIN_HISTORY + maxHorizon + 1}거래일, 보유 $n). $CAVEAT_BASE",
                )
            }

            // 후보: 벡터 계산 가능(idx≥MIN_HISTORY-1) + 60일 forward 확정(idx≤n-1-60)
            data class Cand(val idx: Int, val vec: DoubleArray)
            val candidates = ((MIN_HISTORY - 1)..(todayIdx - maxHorizon - 1)).mapNotNull { i ->
                vectorAt(asc, closes, i)?.let { Cand(i, it.toArray()) }
            }
            if (candidates.isEmpty()) {
                return AnalogReport(
                    code = code, name = name, date = today, vectorToday = todayVec, n = 0,
                    caveat = "유사 국면 후보가 없습니다. $CAVEAT_BASE",
                )
            }

            // z-정규화(자기 분포 기준: 후보 + 오늘). 분산 0 피처는 차이 0으로 처리(std=1 대체).
            val todayArr = todayVec.toArray()
            val all = candidates.map { it.vec } + listOf(todayArr)
            val dims = todayArr.size
            val mean = DoubleArray(dims) { d -> all.sumOf { it[d] } / all.size }
            val std = DoubleArray(dims) { d ->
                val v = all.sumOf { (it[d] - mean[d]) * (it[d] - mean[d]) } / all.size
                sqrt(v).takeIf { it > 1e-9 } ?: 1.0
            }
            fun dist(a: DoubleArray, b: DoubleArray): Double {
                var s = 0.0
                for (d in 0 until dims) { val z = (a[d] - b[d]) / std[d]; s += z * z }
                return sqrt(s)
            }

            // 거리 하위 k → ±CLUSTER_GAP 클러스터(가까운 것 우선 채택)
            val ranked = candidates.map { it to dist(it.vec, todayArr) }.sortedBy { it.second }.take(K_NEAREST)
            val accepted = mutableListOf<Int>()
            for ((cand, _) in ranked) {
                if (accepted.none { kotlin.math.abs(it - cand.idx) <= CLUSTER_GAP }) accepted.add(cand.idx)
            }

            val horizons = HORIZONS.map { h ->
                val rets = accepted.map { i -> (closes[i + h] / closes[i] - 1) * 100 }
                AnalogHorizon(
                    days = h,
                    winRate = round1(rets.count { it > 0 } * 100.0 / rets.size),
                    median = round1(median(rets)),
                    avg = round1(rets.average()),
                    min = round1(rets.min()),
                    max = round1(rets.max()),
                )
            }

            val count = accepted.size
            val caveat = buildString {
                if (count < 15) append("표본 ${count}건 — 참고 수준입니다(15건 미만). ")
                append(CAVEAT_BASE)
            }
            return AnalogReport(
                code = code, name = name, date = today,
                vectorToday = todayVec, n = count,
                matchedDates = accepted.sortedDescending().map { asc[it].date },
                horizons = horizons, peersIncluded = false, caveat = caveat,
            )
        }

        /**
         * asc(오래된 순) 기준 idx 시점의 상태 벡터. 해당 시점까지의 데이터만 사용(look-ahead 금지).
         * null = 이력 부족(idx < MIN_HISTORY-1 등).
         */
        fun vectorAt(asc: List<DailyBar>, closes: List<Double>, idx: Int): AnalogVector? {
            if (idx < MIN_HISTORY - 1 || idx >= asc.size) return null
            val win52 = asc.subList(idx - MIN_HISTORY + 1, idx + 1)
            val hi = win52.maxOf { it.high }.toDouble()
            val lo = win52.minOf { it.low }.toDouble()
            if (hi <= lo || closes[idx] <= 0) return null
            val pos52w = (closes[idx] - lo) / (hi - lo) * 100

            if (closes[idx - 20] <= 0) return null
            val ret20 = (closes[idx] / closes[idx - 20] - 1) * 100

            val volAvg = (idx - 20 until idx).sumOf { asc[it].volume.toDouble() } / 20
            if (volAvg <= 0) return null
            val volumeRatio = asc[idx].volume.toDouble() / volAvg

            val rsi = rsiWilder(closes.subList(0, idx + 1), 14) ?: return null
            return AnalogVector(round1(pos52w), round1(ret20), round2(volumeRatio), round1(rsi))
        }

        /**
         * RSI(Wilder) — sharedLogic TechnicalIndicators.rsi와 동일 산식(값 일치 테스트로 고정).
         * 입력은 오래된 순(sharedLogic은 최신이 앞이라 뒤집는 것만 다름).
         */
        fun rsiWilder(closesAsc: List<Double>, n: Int): Double? {
            if (closesAsc.size < n + 1) return null
            val changes = (1 until closesAsc.size).map { closesAsc[it] - closesAsc[it - 1] }
            if (changes.size < n) return null
            val first = changes.take(n)
            var avgGain = first.filter { it > 0 }.average().takeIf { first.any { c -> c > 0 } } ?: 0.0
            var avgLoss = first.filter { it < 0 }.map { -it }.average().takeIf { first.any { c -> c < 0 } } ?: 0.0
            for (i in n until changes.size) {
                val gain = if (changes[i] > 0) changes[i] else 0.0
                val loss = if (changes[i] < 0) -changes[i] else 0.0
                avgGain = (avgGain * (n - 1) + gain) / n
                avgLoss = (avgLoss * (n - 1) + loss) / n
            }
            if (avgLoss == 0.0) return 100.0
            val rs = avgGain / avgLoss
            return 100.0 - 100.0 / (1.0 + rs)
        }

        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        private fun AnalogVector.toArray() = doubleArrayOf(pos52w, ret20, volumeRatio, rsi14)
        private fun round1(v: Double) = kotlin.math.round(v * 10) / 10
        private fun round2(v: Double) = kotlin.math.round(v * 100) / 100
    }
}
