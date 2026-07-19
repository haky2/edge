package com.haky.edge.model

import kotlinx.serialization.Serializable

/** POST /position-sizing 응답 — 리스크 기여 상한 역산(LLM 0). */
@Serializable
data class PositionSizing(
    val date: String,
    val candidateCode: String,
    val candidateName: String,
    val riskCapPct: Double,
    val price: Double,
    val maxShares: Long,
    val maxAmount: Long,
    val targetWeightPct: Double,
    val atRiskContributionPct: Double,
    val sigmaPct: Double,
    val approxByPeer: Boolean = false,
    val excluded: List<String> = emptyList(),
    val caveat: String = "",
)

@Serializable
data class PositionSizingEntry(val code: String, val qty: Long)

@Serializable
data class PositionSizingRequest(
    val positions: List<PositionSizingEntry> = emptyList(),
    val candidateCode: String = "",
    val riskCapPct: Double = 15.0,
)
