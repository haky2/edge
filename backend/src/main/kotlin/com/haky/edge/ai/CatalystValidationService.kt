package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.IndexPoint
import com.haky.edge.kis.KisClient
import com.haky.edge.master.StockMaster
import com.haky.edge.util.KST
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 판정 버킷 1개 × horizon 1개의 채점 결과. excess = 코스피 대비 초과수익률. */
@Serializable
data class JudgmentBucket(
    val label: String,          // "호재" | "악재" | "중립" | "상" | "중" | "하" | "fresh" | "reflected"
    val days: Int,              // horizon(1·5·20 거래일)
    val n: Int,
    val avgRawPct: Double,      // 원수익률 평균(참고)
    val avgExcessPct: Double,   // 초과수익률 평균(채점 기준)
    val winRatePct: Double,     // 초과수익 > 0 비율
    val directionalAccuracyPct: Double?, // 호재: excess>0 / 악재: excess<0 / 중립: |excess|<밴드. 강도·선반영 버킷은 null
)

/** GET /catalyst-validation 응답 — ②-1 catalyst 판정 실증(1회성 관리 라우트). */
@Serializable
data class CatalystValidationReport(
    val generatedAt: String,
    val totalEvents: Int,          // 로그 전체(백필 포함)
    val judgedEvents: Int,         // LLM 실시간 판정분(strength≠미상)
    val scoredEvents: Int,         // 날짜 정규화 + 일봉·코스피 조인까지 성공한 건
    val dateRange: String,         // 채점 대상 이벤트 날짜 범위
    val bySentiment: List<JudgmentBucket>,
    val byStrength: List<JudgmentBucket>,      // 호재 판정만(강도 단조성 검증: |반응| 상>중>하?)
    val byPreReflected: List<JudgmentBucket>,  // 호재 판정만 fresh vs reflected(선반영 개념 검증)
    val caveat: String,
)

/**
 * ②-1 catalyst 판정 실증 — catalyst_events.jsonl의 LLM 판정(호재/악재/중립·강도·선반영)을
 * 사후 주가 반응과 대조해 채점한다. LLM 0, 순수 계산.
 *
 * 핵심 설계: **원수익률이 아니라 코스피 대비 초과수익률로 채점** — 시장 급락 주간의 호재 판정이
 * 전부 오판으로 왜곡되는 것을 막는다(예: 2026-07-06 주간 코스피 -7.6%). 원수익률은 참고로 병기.
 * 백필분(strength="미상", 룰 판정)은 제외 — LLM 판정의 품질만 측정한다.
 */
class CatalystValidationService(
    private val eventLog: CatalystEventLog,
    private val history: DailyHistoryService,
    private val kis: KisClient,
    private val master: StockMaster,
) {
    suspend fun validate(): CatalystValidationReport {
        val all = eventLog.readAll()
        // LLM 실시간 판정분만 + 날짜 정규화(뉴스 RFC 표기 → YYYYMMDD)
        val judged = all
            .filter { it.strength != "미상" }
            .mapNotNull { e -> CatalystImpactService.normalizeDate(e.date)?.let { e.copy(date = it) } }
        if (judged.isEmpty()) {
            return CatalystValidationReport(
                generatedAt = LocalDate.now(KST).toString(),
                totalEvents = all.size, judgedEvents = 0, scoredEvents = 0, dateRange = "-",
                bySentiment = emptyList(), byStrength = emptyList(), byPreReflected = emptyList(),
                caveat = "LLM 판정 이벤트가 없습니다(백필분만 존재).",
            )
        }

        // 코스피(0001) 일별 종가 — 이벤트 범위 + forward 20거래일 여유. 90일 청크로 나눠 병합.
        val minDate = judged.minOf { it.date }
        val kospiAsc = fetchKospiAsc(minDate)

        // 종목별 일봉(F1 캐시 재사용, 오래된 순)
        val byCode = judged.groupBy { it.code }
        val barsByCode = mutableMapOf<String, List<DailyBar>>()
        for (code in byCode.keys) {
            barsByCode[code] = runCatching { history.getHistory(code).asReversed() }.getOrElse { emptyList() }
        }

        // 이벤트별 (raw, excess) 수익률 — horizon마다
        val scored = scoreEvents(judged, barsByCode, kospiAsc)
        val scoredEventKeys = scored.map { it.event.url }.distinct().size

        fun buckets(events: List<ScoredEvent>, label: String, directional: ((Double, Int) -> Boolean)?): List<JudgmentBucket> =
            HORIZONS.mapNotNull { h ->
                val rs = events.mapNotNull { it.returns[h] }
                if (rs.isEmpty()) return@mapNotNull null
                JudgmentBucket(
                    label = label, days = h, n = rs.size,
                    avgRawPct = round2(rs.map { it.first }.average()),
                    avgExcessPct = round2(rs.map { it.second }.average()),
                    winRatePct = round1(rs.count { it.second > 0 } * 100.0 / rs.size),
                    directionalAccuracyPct = directional?.let { f ->
                        round1(rs.count { f(it.second, h) } * 100.0 / rs.size)
                    },
                )
            }

        val bySentiment = listOf(
            buckets(scored.filter { it.event.sentiment == "호재" }, "호재") { e, _ -> e > 0 },
            buckets(scored.filter { it.event.sentiment == "악재" }, "악재") { e, _ -> e < 0 },
            buckets(scored.filter { it.event.sentiment == "중립" }, "중립") { e, h -> kotlin.math.abs(e) < neutralBand(h) },
        ).flatten()

        val positives = scored.filter { it.event.sentiment == "호재" }
        val byStrength = listOf("상", "중", "하").flatMap { s ->
            buckets(positives.filter { it.event.strength == s }, s, null)
        }
        val byPreReflected = listOf(
            buckets(positives.filter { !it.event.preReflected }, "fresh", null),
            buckets(positives.filter { it.event.preReflected }, "reflected", null),
        ).flatten()

        return CatalystValidationReport(
            generatedAt = LocalDate.now(KST).toString(),
            totalEvents = all.size,
            judgedEvents = judged.size,
            scoredEvents = scoredEventKeys,
            dateRange = "$minDate ~ ${judged.maxOf { it.date }}",
            bySentiment = bySentiment,
            byStrength = byStrength,
            byPreReflected = byPreReflected,
            caveat = "초과수익률(종목-코스피) 기준. n<10 버킷은 방향 참고만. 20일 horizon은 최근 판정일수록 미확정(제외됨).",
        )
    }

    /** 코스피 일별 종가(오래된 순). minDate 20일 전 ~ 오늘. 90일 청크 병합(KIS 단일 응답 행 제한 방어). */
    private suspend fun fetchKospiAsc(minDate: String): List<IndexPoint> {
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        var start = LocalDate.parse(minDate, fmt).minusDays(30)
        val end = LocalDate.now(KST)
        val merged = mutableMapOf<String, IndexPoint>()
        while (start <= end) {
            val chunkEnd = minOf(start.plusDays(89), end)
            runCatching {
                kis.getSectorIndexChartRange("0001", start.format(fmt), chunkEnd.format(fmt))
            }.getOrElse { emptyList() }.forEach { merged[it.date] = it }
            start = chunkEnd.plusDays(1)
        }
        return merged.values.sortedBy { it.date }
    }

    private data class ScoredEvent(
        val event: CatalystEvent,
        val returns: Map<Int, Pair<Double, Double>>, // horizon → (raw%, excess%)
    )

    private fun scoreEvents(
        events: List<CatalystEvent>,
        barsByCode: Map<String, List<DailyBar>>,
        kospiAsc: List<IndexPoint>,
    ): List<ScoredEvent> = events.mapNotNull { e ->
        val bars = barsByCode[e.code] ?: return@mapNotNull null
        val returns = HORIZONS.mapNotNull { h ->
            forwardPair(e.date, bars, kospiAsc, h)?.let { h to it }
        }.toMap()
        if (returns.isEmpty()) null else ScoredEvent(e, returns)
    }

    companion object {
        val HORIZONS = listOf(1, 5, 20)

        /** 중립 판정 허용 밴드(초과수익 절대값, %) — horizon별 휴리스틱(스탠스 채점 20일=3%와 정합). */
        internal fun neutralBand(horizon: Int): Double = when (horizon) {
            1 -> 1.0; 5 -> 2.0; else -> 3.0
        }

        /**
         * 이벤트일 기준 forward (raw, excess) 수익률. 기준봉 = 이벤트일 이전(당일 포함) 마지막 거래일
         * (CatalystImpactService.computeHorizons와 동일 규약), 코스피도 같은 방식으로 자기 배열에서 산출.
         * forward 봉 부족(최근 이벤트) 또는 코스피 조인 실패 시 null.
         */
        internal fun forwardPair(
            eventYmd: String,
            stockBarsAsc: List<DailyBar>,
            kospiAsc: List<IndexPoint>,
            horizon: Int,
        ): Pair<Double, Double>? {
            val sIdx = stockBarsAsc.indexOfLast { it.date <= eventYmd }
            if (sIdx < 0 || sIdx + horizon > stockBarsAsc.lastIndex) return null
            val sBase = stockBarsAsc[sIdx].close
            if (sBase <= 0) return null
            val raw = (stockBarsAsc[sIdx + horizon].close.toDouble() / sBase - 1) * 100

            val kIdx = kospiAsc.indexOfLast { it.date <= eventYmd }
            if (kIdx < 0 || kIdx + horizon > kospiAsc.lastIndex) return null
            val kBase = kospiAsc[kIdx].close
            if (kBase <= 0) return null
            val kospi = (kospiAsc[kIdx + horizon].close / kBase - 1) * 100

            return round2(raw) to round2(raw - kospi)
        }

        internal fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
        internal fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
    }
}
