package com.haky.edge.macro

import com.haky.edge.kis.MacroIndicator
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

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

    // 코스피 선행 지표 가중치. 음수=강달러·고환율은 코스피에 역방향.
    private val LEADING_WEIGHTS = mapOf(
        "nasdaq" to 3.0, "sp500" to 3.0, "dow" to 2.0,
        "ewy"    to 3.0, "sox"   to 1.0, "rut" to 1.0,
        "dxy"    to -2.0, "usdkrw" to -2.0,
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
     * 장 전 조회: KOSPI = 0 → PENDING. 장 마감 후 재조회: 실제값으로 자동 채점.
     */
    @Synchronized
    fun addOrUpdateEntry(date: String, direction: String, indicators: List<MacroIndicator>) {
        val log = loadLog().toMutableList()

        val kospiChange = indicators.find { it.key == "kospi" }?.changeRate
        val hasActualData = kospiChange != null && kotlin.math.abs(kospiChange) >= 0.1
        val actualDirection = if (hasActualData && kospiChange != null) classifyActual(kospiChange) else null
        val isCorrect = actualDirection?.let { it == direction }

        val existing = log.indexOfFirst { it.date == date }
        if (existing >= 0) {
            val prev = log[existing]
            if (prev.isCorrect == null && isCorrect != null) {
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
            logFile.writeText(json.encodeToString(listSerializer, entries))
        }
    }
}
