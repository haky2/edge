package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 집계 버킷 1개(전체/스탠스별/모드별/레짐별 공용). */
@Serializable
data class StanceBucket(
    val label: String,
    val n: Int,
    val correct: Int,
    val accuracyPct: Double,
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
)

/**
 * 스탠스 채점(F6 슬라이스 6b) — stance_log.jsonl의 20거래일 경과 건에 실제 수익률을 조인해
 * {긍정→상승?, 부정→하락?, 중립→|수익률|<3%?}로 채점하고 전체/스탠스/모드/레짐별 집계.
 *
 * 스펙의 "주 1회 배치" 대신 조회 시 계산 + 당일 캐시 — 로그·일봉이 정본이라 저장할 상태가 없고
 * (멱등 재계산), DailyHistoryService가 F1용으로 이미 종목별 이력을 캐시하고 있어 비용이 낮다.
 */
class StanceStatsService(
    private val stanceLog: StanceLog,
    private val history: DailyHistoryService,
) {
    private val cache = ConcurrentHashMap<String, StanceStats>()
    private val fileCache = FileCache("stance_stats", StanceStats.serializer())

    suspend fun stats(): StanceStats {
        val today = effectiveMarketDate()
        val key = "$today|stance-stats"
        cache[key]?.let { return it }
        fileCache.get(key)?.let { cache[key] = it; return it }

        val entries = stanceLog.readAll()
        val histories = entries.map { it.code }.distinct().associateWith { code ->
            runCatching { history.getHistory(code) }.getOrElse { emptyList() }
        }
        val stats = score(entries, histories, today)
        cache[key] = stats; fileCache.put(key, stats)
        return stats
    }

    companion object {
        const val HORIZON_DAYS = 20
        const val NEUTRAL_BAND_PCT = 3.0

        /**
         * 순수 채점 함수. historiesDesc는 종목별 일봉(최신이 앞).
         * 같은 (code,date,mode)의 중복 생성(재생성·개인화 분리 캐시)은 마지막 것만 채택.
         */
        fun score(entries: List<StanceEntry>, historiesDesc: Map<String, List<DailyBar>>, today: String): StanceStats {
            val deduped = entries.associateBy { "${it.code}|${it.date}|${it.mode}" }.values
            var pending = 0
            var unknown = 0
            data class Scored(val entry: StanceEntry, val correct: Boolean)
            val scored = mutableListOf<Scored>()

            for (e in deduped) {
                if (e.stance !in setOf("긍정", "중립", "부정") || e.priceAtGen <= 0) { unknown++; continue }
                val asc = historiesDesc[e.code].orEmpty().asReversed()
                val entryYmd = e.date.replace("-", "")
                // 기준봉 = 생성일 이후 첫 거래일(주말 생성이면 다음 월요일). exit = 기준봉 + 20거래일.
                val baseIdx = asc.indexOfFirst { it.date >= entryYmd }
                val exitIdx = if (baseIdx >= 0) baseIdx + HORIZON_DAYS else -1
                if (baseIdx < 0 || exitIdx > asc.lastIndex) { pending++; continue }
                // 수익률은 봉 대 봉(기준봉 종가→exit 종가) — 일봉은 수정주가라 분할·감자에도 양변이
                // 함께 조정된다. priceAtGen(생성 시점 가격)을 분모로 쓰면 수정주가 이벤트 때 채점이
                // 통째로 뒤집힌다(기록용으로만 유지).
                val base = asc[baseIdx].close
                if (base <= 0) { unknown++; continue }
                val ret = (asc[exitIdx].close.toDouble() / base - 1) * 100
                val correct = when (e.stance) {
                    "긍정" -> ret > 0
                    "부정" -> ret < 0
                    else -> abs(ret) < NEUTRAL_BAND_PCT
                }
                scored += Scored(e, correct)
            }

            fun bucket(label: String, list: List<Scored>): StanceBucket? {
                if (list.isEmpty()) return null
                val c = list.count { it.correct }
                return StanceBucket(label, list.size, c, kotlin.math.round(c * 1000.0 / list.size) / 10)
            }

            return StanceStats(
                date = today,
                horizonDays = HORIZON_DAYS,
                neutralBandPct = NEUTRAL_BAND_PCT,
                scored = scored.size,
                pending = pending,
                unknown = unknown,
                overall = bucket("전체", scored),
                byStance = listOf("긍정", "중립", "부정").mapNotNull { s -> bucket(s, scored.filter { it.entry.stance == s }) },
                byMode = listOf("defensive", "aggressive").mapNotNull { m -> bucket(m, scored.filter { it.entry.mode == m }) },
                byRegime = scored.mapNotNull { it.entry.regime }.distinct().sorted()
                    .mapNotNull { r -> bucket(r, scored.filter { it.entry.regime == r }) },
            )
        }
    }
}
