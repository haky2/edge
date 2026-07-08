package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 종목 1개의 기준점 대비 비중 변화. GET /rebalance-check drifts 항목. */
@Serializable
data class DriftEntry(
    val code: String,
    val name: String,
    val baselinePct: Double,
    val currentPct: Double,
    val deltaPp: Double,
    val fired: Boolean,
)

/** GET /rebalance-check 응답. 전부 계산(룰) — LLM 0. */
@Serializable
data class RebalanceCheck(
    val date: String,
    val evaluated: Boolean,
    val reason: String? = null,
    val snapshotUpdatedAt: String? = null,
    val baselineSetAt: String? = null,
    val drifts: List<DriftEntry> = emptyList(),
    val topBandWeightPct: Double? = null,
    val topBandStocks: List<String> = emptyList(),
    val topBandFired: Boolean = false,
    val signals: List<String> = emptyList(),
    val caveat: String,
)
