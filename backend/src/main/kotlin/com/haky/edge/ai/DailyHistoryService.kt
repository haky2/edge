package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.KisClient
import com.haky.edge.util.KST
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 종목 1개의 장기 일봉 이력 스냅샷(최신이 앞). updatedAt=effectiveMarketDate 기준. */
@Serializable
data class DailyHistory(
    val code: String,
    val updatedAt: String,          // YYYY-MM-DD — 이 날짜면 KIS 재호출 없이 그대로 사용
    val bars: List<DailyBar>,
)

/**
 * 장기 일봉 이력(F1 유사 국면 통계용, ~500거래일) — 슬라이스 1a.
 *
 * 한투 FHKST03010100은 단일 응답 최대 ~100건이라, end 날짜를 가장 오래된 봉 이전으로
 * 옮겨가며 페이지네이션한다. 결과는 종목별 파일로 저장해 하루 1회만 갱신:
 *  - 당일 캐시 히트 → KIS 호출 0
 *  - 당일 첫 조회 → 최신 페이지 1콜 + 캐시 병합(과거분 재호출 없음)
 *  - 캐시 없음/무효 → 전체 페이지네이션(500거래일 ≈ 5~6콜)
 *
 * ⚠️ 수정주가 함정: FID_ORG_ADJ_PRC=1은 액면분할·감자 등에서 **과거 전체가 재계산**되므로
 * "과거는 불변"이 성립하지 않는다. 병합 시 겹치는 날짜의 종가가 캐시와 다르면
 * 수정주가 재계산으로 보고 전체를 다시 받는다(mergeHistories → null).
 */
class DailyHistoryService(private val kis: KisClient) {
    private val dir = File("${System.getenv("CACHE_DIR") ?: ".cache"}/daily_history").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getHistory(code: String, minBars: Int = DEFAULT_MIN_BARS): List<DailyBar> {
        val today = effectiveMarketDate()
        val cached = load(code)
        if (cached != null && cached.updatedAt == today && cached.bars.size >= minBars) return cached.bars

        // 최신 페이지 1콜(기존 getDailyChart 재사용 — 7개월 창, ~100건).
        // 장마감 확정 전 오늘 봉은 제외 — 포함하면 ①당일 생성 통계에 장중 반쪽 값이 섞이고
        // ②동결 저장된 장중 종가가 다음날 병합에서 확정 종가와 불일치 → 수정주가 재계산으로
        // 오인해 전체 리페치(~6콜)가 매일 난다.
        val fresh = dropUnconfirmedToday(kis.getDailyChart(code, bars = PAGE_BARS))

        val merged = cached?.let { mergeHistories(fresh, it.bars) }
        val base = merged ?: fresh                       // merged=null → 수정주가 재계산 or 캐시 공백 → 처음부터
        val full = fetchBack(code, base, minBars)
        save(DailyHistory(code, today, full))
        return full
    }

    /** base(최신이 앞)의 가장 오래된 봉 이전 구간을 페이지네이션으로 채운다. */
    private suspend fun fetchBack(code: String, base: List<DailyBar>, minBars: Int): List<DailyBar> {
        var acc = base
        var pages = 0
        while (acc.size < minBars && pages++ < MAX_PAGES) {
            val oldest = acc.lastOrNull()?.date ?: break
            val end = LocalDate.parse(oldest, YMD).minusDays(1)
            val start = end.minusDays(PAGE_CAL_DAYS)     // 달력 ~200일 ≈ 영업일 ~135 > 응답 상한 100
            val page = kis.getDailyChartRange(code, start.format(YMD), end.format(YMD))
            if (page.isEmpty()) break                    // 상장 이전 도달
            acc = acc + page.filter { it.date < oldest } // 경계 중복 방어
        }
        return acc
    }

    private fun load(code: String): DailyHistory? = runCatching {
        val f = File(dir, "$code.json")
        if (f.exists()) json.decodeFromString(DailyHistory.serializer(), f.readText()) else null
    }.getOrNull()

    private fun save(h: DailyHistory) {
        runCatching { File(dir, "${h.code}.json").writeText(json.encodeToString(DailyHistory.serializer(), h)) }
    }

    companion object {
        const val DEFAULT_MIN_BARS = 500
        private const val PAGE_BARS = 100
        private const val MAX_PAGES = 8
        private const val PAGE_CAL_DAYS = 200L
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val CONFIRM_TIME: LocalTime = LocalTime.of(16, 0) // 종가 확정 간주 시각(수급 캐시 H1과 동일 기준)

        /** 확정 전(16:00 KST 이전) 오늘 날짜 봉 제거. bars는 최신이 앞. */
        internal fun dropUnconfirmedToday(
            bars: List<DailyBar>,
            now: LocalDateTime = LocalDateTime.now(KST),
        ): List<DailyBar> {
            if (now.toLocalTime() >= CONFIRM_TIME) return bars
            val todayYmd = now.toLocalDate().format(YMD)
            return bars.filter { it.date != todayYmd }
        }

        /**
         * 최신 페이지(fresh)와 캐시(cached)를 병합한다. 둘 다 최신이 앞.
         * null 반환 = 캐시를 신뢰할 수 없음(전체 리페치 신호):
         *  - 겹치는 날짜가 없음(캐시가 너무 낡아 공백 발생)
         *  - 겹치는 날짜의 종가 불일치(수정주가 재계산 — 분할·감자 등)
         */
        fun mergeHistories(fresh: List<DailyBar>, cached: List<DailyBar>): List<DailyBar>? {
            if (cached.isEmpty()) return fresh.ifEmpty { null }
            if (fresh.isEmpty()) return cached
            val cachedByDate = cached.associateBy { it.date }
            val overlap = fresh.firstOrNull { it.date in cachedByDate } ?: return null
            if (cachedByDate.getValue(overlap.date).close != overlap.close) return null
            val cutoff = fresh.last().date
            return fresh + cached.filter { it.date < cutoff }
        }
    }
}
