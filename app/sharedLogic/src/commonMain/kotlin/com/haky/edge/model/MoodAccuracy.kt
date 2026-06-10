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
)
