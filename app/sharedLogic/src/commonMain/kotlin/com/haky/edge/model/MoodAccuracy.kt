package com.haky.edge.model

import kotlinx.serialization.Serializable

@Serializable
data class MoodLogEntry(
    val date: String,
    val direction: String,        // BULLISH/BEARISH/NEUTRAL (예측)
    val actualDirection: String?, // null = PENDING
    val isCorrect: Boolean?,      // null = PENDING
    val kospiChange: Double?,
)

@Serializable
data class MoodAccuracyReport(
    val total: Int,
    val correct: Int,
    val pending: Int,
    val recentEntries: List<MoodLogEntry>,
    // X1: 무정보 벤치마크 — 같은 기간 실제 방향의 다수 클래스를 매일 찍었을 때의 적중률(%).
    // 예측기 적중률이 이 값+2%p 이상이라야 정보력 있음. baselineDirection=다수 클래스(BULLISH 등).
    val baselineRate: Int = 0,
    val baselineDirection: String? = null,
)
