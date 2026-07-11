package com.haky.edge.macro

import com.haky.edge.kis.MacroIndicator
import com.haky.edge.util.writeTextAtomic
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class MoodLogEntry(
    val date: String,
    val direction: String,        // BULLISH/BEARISH/NEUTRAL (예측)
    val actualDirection: String?, // null = PENDING (코스피 미개장)
    val isCorrect: Boolean?,      // null = PENDING
    val kospiChange: Double?,     // 실제 코스피 등락률
)

@Serializable
data class MoodAccuracyReport(
    val total: Int,
    val correct: Int,
    val pending: Int,
    val recentEntries: List<MoodLogEntry>,
)

/**
 * 시장 분위기 예측 → 실제 결과 채점 서비스.
 * 예측: 미국 지수·달러 지표 가중 합산. 실제: KOSPI 당일 등락.
 * 저장: .data/market_mood_log.json (`.cache/` 와 달리 삭제 대상 아님)
 */
class MarketMoodLogService {
    private val dataDir = File(System.getenv("DATA_DIR") ?: ".data").also { it.mkdirs() }
    private val logFile = File(dataDir, "market_mood_log.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(MoodLogEntry.serializer())
    private val seoulZone = ZoneId.of("Asia/Seoul")
    private val marketCloseKst = LocalTime.of(15, 30) // 코스피 정규장 마감

    init {
        cleanWeekendEntries()
    }

    /** 기존 로그에 남아있는 주말 항목을 제거한다. 배포 후 1회만 실질적으로 동작. */
    private fun cleanWeekendEntries() {
        val raw = runCatching {
            if (!logFile.exists()) return
            json.decodeFromString(listSerializer, logFile.readText())
        }.getOrElse { return }
        val cleaned = raw.filter { entry ->
            val d = runCatching { LocalDate.parse(entry.date) }.getOrNull() ?: return@filter true
            d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
        }
        if (cleaned.size != raw.size) saveLog(cleaned)
    }

    /** /market-mood-log 조기 반환 시 오늘 예측이 이미 기록됐는지 빠르게 확인. */
    fun hasTodayEntry(date: String): Boolean = loadLog().any { it.date == date }

    // 코스피 선행 지표 가중치. 음수=강달러·고환율은 코스피에 역방향.
    // 미국 지수선물(nqfut/esfut/ymfut)은 한국 장 전 미국 야간 흐름을 반영하는 가장 신선한 선행신호 —
    // 지수 종가(nasdaq/sp500/dow)와 상관 높지만 장 마감 후 변동까지 담아 갭 방향을 앞서 가리킨다.
    private val LEADING_WEIGHTS = mapOf(
        "nasdaq" to 3.0, "sp500" to 3.0, "dow" to 2.0,
        "ewy"    to 3.0, "sox"   to 1.0, "rut" to 1.0,
        "dxy"    to -2.0, "usdkrw" to -2.0,
        "nqfut"  to 2.0, "esfut"  to 2.0, "ymfut" to 1.0,
    )

    /** 미국 지수·환율 지표로 코스피 방향 예측. */
    fun inferDirection(indicators: List<MacroIndicator>): String {
        var weightedSum = 0.0
        var totalWeight = 0.0
        for (ind in indicators) {
            val w = LEADING_WEIGHTS[ind.key] ?: continue
            weightedSum += ind.changeRate * w
            totalWeight += kotlin.math.abs(w)
        }
        if (totalWeight == 0.0) return "NEUTRAL"
        val composite = weightedSum / totalWeight
        return when {
            composite > 0.5  -> "BULLISH"
            composite < -0.5 -> "BEARISH"
            else             -> "NEUTRAL"
        }
    }

    /**
     * 오늘 예측 기록. 이미 있으면 KOSPI가 채워진 경우(장 마감 후 재조회)에만 업데이트.
     * 장 전/장 중: PENDING. **장 마감(15:30 KST) 후**에만 실제값으로 자동 채점.
     * 주말(토/일)은 코스피 휴장이므로 기록 건너뜀.
     *
     * ⚠️ 시간 게이트 필수: 장 전엔 KIS가 KOSPI에 *전일 종가* 등락률을 돌려주고(0이 아님),
     * 장 중엔 미확정 장중값이라 — 둘 다 그대로 채점하면 오늘 예측에 어제/장중 값이 실제로 박힌다.
     * 오늘(KST) 날짜는 마감 후에만 채점하고, 그 전엔 KOSPI 값이 있어도 PENDING으로 둔다.
     */
    @Synchronized
    fun addOrUpdateEntry(date: String, direction: String, indicators: List<MacroIndicator>) {
        val dayOfWeek = runCatching { LocalDate.parse(date).dayOfWeek }.getOrNull()
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) return
        val log = loadLog().toMutableList()

        // 오늘(KST) 예측은 정규장 마감 전까지 채점 보류. 과거 날짜(백필)는 그대로 채점 허용.
        val nowKst = LocalDate.now(seoulZone).toString()
        val isToday = date == nowKst
        val marketClosed = LocalTime.now(seoulZone) >= marketCloseKst
        val canScore = !isToday || marketClosed

        val kospiChange = indicators.find { it.key == "kospi" }?.changeRate
        // 마감 후에는 0%대 보합 마감도 실제 결과로 채점한다. 예전 |변화|≥0.1% 게이트는 "장 전
        // 전일값 오채점" 방지용이었는데 시간 게이트(canScore)가 그 역할을 대체했고, 보합일을
        // 영구 PENDING으로 남겨 NEUTRAL 적중일이 표본에서 계통 제외되는 편향이 있었다(감사 M5).
        // 한계: 평일 공휴일은 KIS가 직전 거래일 등락을 돌려줘 오채점 여지 — 개장 캘린더 미도입으로 감수.
        val hasActualData = canScore && kospiChange != null
        val actualDirection = if (hasActualData && kospiChange != null) classifyActual(kospiChange) else null
        val isCorrect = actualDirection?.let { it == direction }

        val existing = log.indexOfFirst { it.date == date }
        if (existing >= 0) {
            val prev = log[existing]
            when {
                // 마감 전인데 오늘 항목이 이미 채점돼 있으면(과거 버그로 전일 종가가 박힌 케이스) PENDING 복구.
                isToday && !marketClosed && prev.isCorrect != null ->
                    log[existing] = prev.copy(actualDirection = null, isCorrect = null, kospiChange = null)
                // 정상 채점: 아직 PENDING이고 이제 실제값 확보(장 마감 후).
                prev.isCorrect == null && isCorrect != null ->
                    log[existing] = prev.copy(
                        actualDirection = actualDirection,
                        isCorrect = isCorrect,
                        kospiChange = kospiChange,
                    )
            }
        } else {
            log.add(0, MoodLogEntry(
                date = date,
                direction = direction,
                actualDirection = actualDirection,
                isCorrect = isCorrect,
                kospiChange = kospiChange,
            ))
        }
        saveLog(log)
    }

    fun getAccuracyReport(): MoodAccuracyReport {
        val log = loadLog()
        val scored = log.filter { it.isCorrect != null }
        return MoodAccuracyReport(
            total = scored.size,
            correct = scored.count { it.isCorrect == true },
            pending = log.count { it.isCorrect == null },
            recentEntries = log.take(30),
        )
    }

    private fun classifyActual(kospiChange: Double): String = when {
        kospiChange > 0.3  -> "BULLISH"
        kospiChange < -0.3 -> "BEARISH"
        else               -> "NEUTRAL"
    }

    private fun loadLog(): List<MoodLogEntry> {
        if (!logFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(listSerializer, logFile.readText())
        }.getOrElse { emptyList() }
    }

    private fun saveLog(entries: List<MoodLogEntry>) {
        runCatching {
            logFile.writeTextAtomic(json.encodeToString(listSerializer, entries))
        }
    }
}
