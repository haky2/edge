package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.IndexPoint
import com.haky.edge.kis.KisClient
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 집계 버킷 1개(전체/스탠스별/모드별/레짐별 공용).
 * baseRatePct = 기저율: 같은 채점 표본 전체에 이 버킷의 스탠스 구성대로 "항상 그렇게" 말했을 때의
 * 적중률(스탠스별 기저율의 구성 가중 평균). accuracyPct가 이 값을 넘어야 정보가 있는 것.
 */
@Serializable
data class StanceBucket(
    val label: String,
    val n: Int,
    val correct: Int,
    val accuracyPct: Double,
    val avgExcessPct: Double? = null,   // 평균 초과수익(종목−코스피, %) — 판단대조와 동일 표기
    val baseRatePct: Double? = null,
)

/** GET /stance-stats — 종목 코멘트 스탠스 채점 집계(F6). 기존 "AI 적중률"(시장 방향)과 별도. */
@Serializable
data class StanceStats(
    val date: String,
    val horizonDays: Int,
    val neutralBandPct: Double,
    val scored: Int,           // 채점 완료(20거래일 경과) 건수
    val pending: Int,          // 아직 20거래일 미경과
    val unknown: Int,          // 스탠스 미상 등 채점 제외
    val overall: StanceBucket? = null,
    val byStance: List<StanceBucket> = emptyList(),
    val byMode: List<StanceBucket> = emptyList(),
    val byRegime: List<StanceBucket> = emptyList(),
    val refN: Int = 15,        // 이 미만 버킷은 참고 수준(색·판정 유보) — UI 규칙 단일 소스
    val caveat: String = "",
)

/**
 * 스탠스 채점(F6 슬라이스 6b → X4 개정) — stance_log.jsonl의 20거래일 경과 건에
 * **코스피 대비 초과수익**을 조인해 {긍정→excess>0, 부정→excess<0, 중립→|excess|<3%}로 채점하고
 * 전체/스탠스/모드/레짐별 집계.
 *
 * X4(2026-07): 채점 잣대를 판단대조(JudgmentComparisonService)와 동일한
 * CatalystValidationService.forwardPair(20거래일 초과수익)로 통일 — 원수익률 채점은 상승장에서
 * "항상 긍정"이 자동 55~60% 적중하는 기저율 함정이 있었다(이원화 금지, R6 교훈).
 * 기저율(항상-그-스탠스 가정의 적중률)을 버킷마다 병기해 UI가 기저율 상대로 색을 정한다.
 *
 * 스펙의 "주 1회 배치" 대신 조회 시 계산 + 당일 캐시 — 로그·일봉이 정본이라 저장할 상태가 없고
 * (멱등 재계산), DailyHistoryService가 F1용으로 이미 종목별 이력을 캐시하고 있어 비용이 낮다.
 */
class StanceStatsService(
    private val stanceLog: StanceLog,
    private val history: DailyHistoryService,
    private val kis: KisClient,
) {
    private val cache = ConcurrentHashMap<String, StanceStats>()
    private val fileCache = FileCache("stance_stats", StanceStats.serializer())

    suspend fun stats(): StanceStats {
        val today = effectiveMarketDate()
        // v2 = X4 초과수익 채점(원수익률 채점 캐시와 섞이지 않게 키 분리).
        val key = "$today|stance-stats-v2"
        cache[key]?.let { return it }
        fileCache.get(key)?.let { cache[key] = it; return it }

        val entries = stanceLog.readAll()
        val histories = entries.map { it.code }.distinct().associateWith { code ->
            runCatching { history.getHistory(code) }.getOrElse { emptyList() }
        }
        val minDate = entries.minOfOrNull { it.date.replace("-", "") } ?: today.replace("-", "")
        val kospiAsc = fetchKospiCloseAsc(kis, minDate)
        val stats = score(entries, histories, kospiAsc, today)
        cache[key] = stats; fileCache.put(key, stats)
        return stats
    }

    companion object {
        const val HORIZON_DAYS = 20
        const val NEUTRAL_BAND_PCT = 3.0
        const val REF_N = 15          // 참고 수준 컷(코드베이스 공통 규약)
        const val MIN_BUCKET_N = 3    // 이 미만 하위 버킷은 침묵(n=1 버킷 반환 방지)

        const val CAVEAT = "20거래일 코스피 대비 초과수익 기준(판단 대조와 동일 잣대). " +
            "기저율(항상 같은 스탠스로 말했을 때의 적중률)보다 높아야 정보가 있는 것. " +
            "중립은 초과수익 ±3% 밴드 적중이라 구조적으로 불리한 잣대(개별 종목 20거래일 변동성이 밴드보다 큼). " +
            "n<${REF_N} 버킷은 참고 수준."

        /**
         * 순수 채점 함수. historiesDesc는 종목별 일봉(최신이 앞), kospiAsc는 코스피 종가(오래된 순).
         * 같은 (code,date,mode)의 중복 생성(재생성·개인화 분리 캐시)은 마지막 것만 채택.
         * 기준봉·초과수익 규약은 CatalystValidationService.forwardPair(판단대조와 동일) —
         * 수정주가 봉 대 봉이라 분할·감자 안전(priceAtGen은 기록용으로만 유지).
         */
        fun score(
            entries: List<StanceEntry>,
            historiesDesc: Map<String, List<DailyBar>>,
            kospiAsc: List<IndexPoint>,
            today: String,
        ): StanceStats {
            val deduped = entries.associateBy { "${it.code}|${it.date}|${it.mode}" }.values
            var pending = 0
            var unknown = 0
            data class Scored(val entry: StanceEntry, val excess: Double, val correct: Boolean)
            val scored = mutableListOf<Scored>()

            fun winIf(stance: String, excess: Double): Boolean = when (stance) {
                "긍정" -> excess > 0
                "부정" -> excess < 0
                else -> abs(excess) < NEUTRAL_BAND_PCT
            }

            for (e in deduped) {
                if (e.stance !in setOf("긍정", "중립", "부정")) { unknown++; continue }
                val asc = historiesDesc[e.code].orEmpty().asReversed()
                val pair = CatalystValidationService.forwardPair(
                    e.date.replace("-", ""), asc, kospiAsc, HORIZON_DAYS)
                if (pair == null) { pending++; continue }
                scored += Scored(e, pair.second, winIf(e.stance, pair.second))
            }

            // 스탠스별 기저율: 전 채점 표본에 "항상 그 스탠스"를 적용했을 때의 적중률.
            val allExcess = scored.map { it.excess }
            val baseByStance: Map<String, Double> = if (allExcess.isEmpty()) emptyMap() else
                listOf("긍정", "중립", "부정").associateWith { s ->
                    allExcess.count { winIf(s, it) } * 100.0 / allExcess.size
                }

            fun bucket(label: String, list: List<Scored>): StanceBucket? {
                if (list.isEmpty()) return null
                val c = list.count { it.correct }
                // 버킷 기저율 = 스탠스 구성 가중 평균(혼합 버킷도 공정 비교 가능).
                val base = list.mapNotNull { baseByStance[it.entry.stance] }.average()
                return StanceBucket(
                    label = label, n = list.size, correct = c,
                    accuracyPct = kotlin.math.round(c * 1000.0 / list.size) / 10,
                    avgExcessPct = kotlin.math.round(list.map { it.excess }.average() * 100) / 100,
                    baseRatePct = kotlin.math.round(base * 10) / 10,
                )
            }
            // 하위 버킷은 MIN_BUCKET_N 미만이면 침묵(n=1 버킷 방지). 전체는 항상 반환.
            fun subBucket(label: String, list: List<Scored>): StanceBucket? =
                if (list.size < MIN_BUCKET_N) null else bucket(label, list)

            return StanceStats(
                date = today,
                horizonDays = HORIZON_DAYS,
                neutralBandPct = NEUTRAL_BAND_PCT,
                scored = scored.size,
                pending = pending,
                unknown = unknown,
                overall = bucket("전체", scored),
                byStance = listOf("긍정", "중립", "부정").mapNotNull { s -> subBucket(s, scored.filter { it.entry.stance == s }) },
                byMode = listOf("defensive", "aggressive").mapNotNull { m -> subBucket(m, scored.filter { it.entry.mode == m }) },
                byRegime = scored.mapNotNull { it.entry.regime }.distinct().sorted()
                    .mapNotNull { r -> subBucket(r, scored.filter { it.entry.regime == r }) },
                refN = REF_N,
                caveat = CAVEAT,
            )
        }
    }
}
