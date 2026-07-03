package com.haky.edge.news

import com.haky.edge.util.KST
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 특정 일자에 관측한 컨센서스 목표주가 한 점. price=그날 주가(돌파 이력용, 구 기록엔 없음 → null). */
@Serializable
data class TargetSnapshot(val date: String, val target: Long, val price: Long? = null)

/**
 * 목표가 이벤트 집계(최근 90일 스냅샷 기준). "매주 목표가가 올라간다"·"주가가 목표가를 뚫었다"
 * 같은 리레이팅 정황을 정량 사실로 만든다. 스냅샷이 쌓여야 의미가 생긴다(초기엔 대부분 0).
 */
@Serializable
data class TargetPriceEvents(
    val raisesIn90d: Int,        // 연속 스냅샷 대비 +1% 이상 상향된 횟수
    val cutsIn90d: Int,          // -1% 이상 하향된 횟수
    val breakthroughDays: Int,   // 주가 ≥ 목표가로 관측된 스냅샷 일수
    val avgRaiseGapDays: Int?,   // 돌파 관측 → 다음 상향까지 평균 일수(둘 다 있을 때만)
    val snapshotCount: Int,      // 집계에 쓴 스냅샷 수
)

/**
 * 컨센서스 목표가의 상향/하향 추세. 과거 스냅샷이 1개 이상 쌓여야 산출(없으면 null).
 * 애널이 목표가를 계속 올리면 "역사적 상단권이라도 시장은 더 위를 본다"는 강한 신호 —
 * 밸류 약세 편향을 사실로 반박한다([[edge-valuation-slices]] 밸류-B).
 */
@Serializable
data class TargetPriceTrend(
    val current: Long,
    val baseline: Long,        // 비교 기준이 된 과거 목표가
    val baselineDate: String,
    val changePct: Double,     // (current-baseline)/baseline*100
    val direction: String,     // 상향/하향/유지
    val snapshotCount: Int,    // 이 종목 누적 스냅샷 수
    val daySpan: Int,          // baselineDate ~ 오늘 일수
)

/**
 * 컨센서스 목표주가를 날짜별로 스냅샷 누적해 상향/하향 추세를 계산한다.
 * 목표가 소스(네이버 스크래핑)는 현재값만 주므로, 추세는 시간이 지나며 쌓이는 우리 기록으로만 만든다.
 * 저장: .data/target_price_log.json (`.cache/`와 달리 삭제 대상 아님). market_mood_log와 동일 패턴.
 */
class TargetPriceLogService {
    private val dataDir = File(System.getenv("DATA_DIR") ?: ".data").also { it.mkdirs() }
    private val logFile = File(dataDir, "target_price_log.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), ListSerializer(TargetSnapshot.serializer()))

    /**
     * 오늘 목표가를 스냅샷으로 기록(같은 날은 최신값으로 갱신)하고, 과거 대비 추세를 반환한다.
     * 과거(오늘이 아닌) 스냅샷이 최근 [TREND_WINDOW_DAYS]일 내에 없으면 null(추세 산출 불가).
     */
    @Synchronized
    fun recordAndTrend(code: String, current: Long?, price: Long? = null): TargetPriceTrend? {
        if (current == null || current <= 0) return null
        val todayStr = LocalDate.now(KST).toString()
        val today = LocalDate.parse(todayStr)

        val all = loadLog().toMutableMap()
        val list = all[code]?.toMutableList() ?: mutableListOf()
        val idx = list.indexOfFirst { it.date == todayStr }
        val snap = TargetSnapshot(todayStr, current, price?.takeIf { it > 0 })
        if (idx >= 0) list[idx] = snap else list.add(snap)

        // 날짜 오름차순 정렬 + 오래된 기록(PRUNE_DAYS 초과) 제거로 파일 크기 제한.
        val pruneCutoff = today.minusDays(PRUNE_DAYS)
        val pruned = list
            .sortedBy { it.date }
            .filter { (parseOrNull(it.date)?.isAfter(pruneCutoff)) ?: true }
        all[code] = pruned
        saveLog(all)

        // 추세: 최근 TREND_WINDOW_DAYS일 내의 가장 오래된 *과거* 스냅샷을 기준선으로.
        val windowStart = today.minusDays(TREND_WINDOW_DAYS)
        val baseline = pruned
            .filter { it.date != todayStr && it.target > 0 }
            .filter { (parseOrNull(it.date)?.isAfter(windowStart.minusDays(1))) ?: false }
            .minByOrNull { it.date } ?: return null

        val changePct = (current - baseline.target).toDouble() / baseline.target * 100
        val direction = when {
            changePct >= MOVE_THRESHOLD  -> "상향"
            changePct <= -MOVE_THRESHOLD -> "하향"
            else                         -> "유지"
        }
        val daySpan = ChronoUnit.DAYS.between(LocalDate.parse(baseline.date), today).toInt()
        return TargetPriceTrend(
            current = current,
            baseline = baseline.target,
            baselineDate = baseline.date,
            changePct = changePct,
            direction = direction,
            snapshotCount = pruned.size,
            daySpan = daySpan,
        )
    }

    /** 최근 90일 스냅샷에서 목표가 이벤트 집계. 스냅샷 2개 미만이면 null. */
    @Synchronized
    fun events(code: String): TargetPriceEvents? {
        val today = LocalDate.now(KST)
        val snapshots = loadLog()[code] ?: return null
        return computeEvents(snapshots, today)
    }

    private fun parseOrNull(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

    private fun loadLog(): Map<String, List<TargetSnapshot>> {
        if (!logFile.exists()) return emptyMap()
        return runCatching { json.decodeFromString(mapSerializer, logFile.readText()) }.getOrElse { emptyMap() }
    }

    private fun saveLog(map: Map<String, List<TargetSnapshot>>) {
        runCatching { logFile.writeText(json.encodeToString(mapSerializer, map)) }
    }

    companion object {
        private const val TREND_WINDOW_DAYS = 30L  // 추세 비교 창(최근 N일)
        private const val PRUNE_DAYS = 180L         // 보관 기간
        private const val MOVE_THRESHOLD = 1.0      // ±1% 미만은 "유지"(노이즈 컷)
        private const val EVENTS_WINDOW_DAYS = 90L  // 이벤트 집계 창

        /**
         * 이벤트 집계 순수 함수(테스트 대상). 최근 [EVENTS_WINDOW_DAYS]일 스냅샷을 날짜순으로 보고
         * ① 연속 쌍 대비 ±1% 이상 변화 = 상향/하향 이벤트 ② price ≥ target = 돌파 관측일
         * ③ 각 돌파일 이후 첫 상향까지의 간격 평균. 스냅샷 2개 미만이면 null.
         */
        internal fun computeEvents(snapshots: List<TargetSnapshot>, today: LocalDate): TargetPriceEvents? {
            val cutoff = today.minusDays(EVENTS_WINDOW_DAYS)
            val window = snapshots
                .filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(cutoff.minusDays(1)) ?: false }
                .sortedBy { it.date }
            if (window.size < 2) return null

            var raises = 0
            var cuts = 0
            val raiseDates = mutableListOf<LocalDate>()
            window.zipWithNext { prev, cur ->
                if (prev.target > 0) {
                    val chg = (cur.target - prev.target).toDouble() / prev.target * 100
                    if (chg >= MOVE_THRESHOLD) { raises++; raiseDates.add(LocalDate.parse(cur.date)) }
                    if (chg <= -MOVE_THRESHOLD) cuts++
                }
            }

            val breakthroughDates = window
                .filter { it.price != null && it.target > 0 && it.price >= it.target }
                .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }

            // 돌파 → 그 이후 첫 상향까지의 간격. 상향이 먼저 오고 돌파가 나중이면 해당 돌파는 미집계.
            val gaps = breakthroughDates.mapNotNull { b ->
                raiseDates.filter { it.isAfter(b) }.minOrNull()
                    ?.let { ChronoUnit.DAYS.between(b, it).toInt() }
            }
            val avgGap = if (gaps.isEmpty()) null else gaps.average().toInt()

            return TargetPriceEvents(
                raisesIn90d = raises,
                cutsIn90d = cuts,
                breakthroughDays = breakthroughDates.size,
                avgRaiseGapDays = avgGap,
                snapshotCount = window.size,
            )
        }
    }
}
