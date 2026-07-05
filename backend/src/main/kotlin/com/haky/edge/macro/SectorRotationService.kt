package com.haky.edge.macro

import com.haky.edge.ai.FileCache
import com.haky.edge.ai.effectiveMarketDate
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.SectorHistory
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 업종 1개의 상대강도 요약. rank 는 1=가장 강함. rankDelta>0 = 20일 대비 단기 순위 상승(자금 유입). */
@Serializable
data class SectorStrength(
    val label: String,
    val ret5: Double,    // 5거래일 수익률 %
    val ret20: Double,   // 20거래일 수익률 %
    val rank5: Int,      // 5일 수익률 순위(1=최고)
    val rank20: Int,     // 20일 수익률 순위
    val rankDelta: Int,  // rank20 - rank5 (양수=단기 순위 상승=유입 조짐)
)

/**
 * 섹터 자금 순환 판정 결과.
 * sectors=ret5 내림차순. inflow/outflow=순환 신호가 뚜렷한 섹터 라벨.
 * factsText=null 이면 뚜렷한 순환 신호 없음(브리핑에서 주입 생략).
 */
@Serializable
data class SectorRotation(
    val date: String,
    val sectors: List<SectorStrength>,
    val inflow: List<String>,
    val outflow: List<String>,
    val factsText: String? = null,
)

/**
 * 섹터 자금 순환(C) — KOSPI 6개 업종지수의 5일/20일 상대강도로 "자금이 어디서 어디로 도는지" 판정.
 * LLM 0(순수 계산). 결과를 시장 분위기 브리핑 facts에 한 문단 주입해 코멘트가 순환을 인지하게 한다.
 *
 * 판정 원리(상대적 순환): 각 업종의 5일·20일 수익률로 두 창의 상대순위를 매기고,
 * 20일 대비 5일 순위가 오른(=단기 가속) 업종을 "유입 조짐", 내린 업종을 "이탈 조짐"으로 본다.
 * 절대 등락이 아니라 상대 순위 변화라, 시장 전체가 함께 움직여도 순환 방향이 드러난다.
 */
class SectorRotationService(private val kis: KisClient) {
    private val fileCache = FileCache("sector_rotation", SectorRotation.serializer())

    /** 당일 캐시(거래일 키). 6 KIS 콜이라 하루 1회만. */
    suspend fun get(): SectorRotation {
        val date = effectiveMarketDate()
        fileCache.get(date)?.let { return it }

        val end = LocalDate.now(KST)
        val start = end.minusDays(FETCH_CAL_DAYS)          // 달력 45일 ≈ 영업일 30 > 필요 21
        val histories = kis.getSectorHistories(start.format(YMD), end.format(YMD))
        val result = compute(date, histories)
        fileCache.put(date, result)
        return result
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
        private const val FETCH_CAL_DAYS = 45L

        internal const val SHORT = 5          // 단기 창(거래일)
        internal const val LONG = 20          // 장기 창(거래일)
        internal const val MIN_POINTS = LONG + 1  // 20일 수익률 계산에 필요한 최소 봉 수
        internal const val RANK_DELTA_MIN = 2 // 순위가 이만큼 이상 움직여야 신호로 인정(6개 중 노이즈 컷)

        /**
         * 순수 계산 — 업종별 5일/20일 수익률·순위·순위변화 → 유입/이탈 판정 + facts 문단.
         * points 는 최신일이 앞. MIN_POINTS 미만 업종은 창이 달라 공정 비교 불가라 제외한다.
         */
        internal fun compute(date: String, histories: List<SectorHistory>): SectorRotation {
            data class Raw(val label: String, val ret5: Double, val ret20: Double)

            val raws = histories.mapNotNull { h ->
                val p = h.points
                if (p.size < MIN_POINTS) return@mapNotNull null
                val c0 = p[0].close
                if (c0 <= 0) return@mapNotNull null
                val ret5 = (c0 / p[SHORT].close - 1) * 100
                val ret20 = (c0 / p[LONG].close - 1) * 100
                Raw(h.label, ret5, ret20)
            }
            if (raws.size < 2) return SectorRotation(date, emptyList(), emptyList(), emptyList(), null)

            // 순위: 수익률 내림차순, 1=최고. 동률은 안정 정렬로 결정적.
            val rank5 = raws.sortedByDescending { it.ret5 }.withIndex().associate { (i, r) -> r.label to i + 1 }
            val rank20 = raws.sortedByDescending { it.ret20 }.withIndex().associate { (i, r) -> r.label to i + 1 }

            val sectors = raws.map { r ->
                val r5 = rank5.getValue(r.label)
                val r20 = rank20.getValue(r.label)
                SectorStrength(r.label, round1(r.ret5), round1(r.ret20), r5, r20, r20 - r5)
            }.sortedByDescending { it.ret5 }

            // 유입=단기 순위 상승 + 단기 가속(ret5>ret20). 이탈=단기 순위 하락 + 단기 둔화.
            val inflow = sectors.filter { it.rankDelta >= RANK_DELTA_MIN && it.ret5 > it.ret20 }.map { it.label }
            val outflow = sectors.filter { it.rankDelta <= -RANK_DELTA_MIN && it.ret5 < it.ret20 }.map { it.label }

            val facts = if (inflow.isEmpty() && outflow.isEmpty()) null else buildFacts(sectors, inflow, outflow)
            return SectorRotation(date, sectors, inflow, outflow, facts)
        }

        /** 시장 분위기 프롬프트에 주입할 순환 문단. 신호 있을 때만 호출됨. */
        internal fun buildFacts(sectors: List<SectorStrength>, inflow: List<String>, outflow: List<String>): String {
            val sb = StringBuilder()
            sb.appendLine("섹터 자금 순환 (KOSPI 6개 업종, 5일/20일 수익률):")
            sectors.forEach { s ->
                sb.appendLine("  - ${s.label}: 5일 ${signed(s.ret5)}%, 20일 ${signed(s.ret20)}%")
            }
            if (inflow.isNotEmpty()) sb.appendLine("자금 유입 조짐(20일 대비 5일 상대순위 상승): ${inflow.joinToString(", ")}")
            if (outflow.isNotEmpty()) sb.appendLine("자금 이탈 조짐(상대순위 하락): ${outflow.joinToString(", ")}")
            return sb.toString().trimEnd()
        }

        private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
        private fun signed(v: Double): String = (if (v >= 0) "+" else "") + "%.1f".format(v)
    }
}
