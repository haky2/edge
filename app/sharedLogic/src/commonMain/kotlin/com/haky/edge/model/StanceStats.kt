package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 집계 버킷 1개(전체/스탠스별/모드별/레짐별 공용). 백엔드 StanceStatsService와 필드 일치. */
@Serializable
data class StanceBucket(
    val label: String,
    val n: Int,
    val correct: Int,
    val accuracyPct: Double,
)

/**
 * GET /stance-stats — 종목 코멘트 스탠스(긍정/중립/부정) vs 20거래일 후 실제 수익률 채점(F6).
 * 브리핑의 "AI 적중률"(시장 방향 예측)과는 별도 지표.
 */
@Serializable
data class StanceStats(
    val date: String,
    val horizonDays: Int,
    val neutralBandPct: Double,
    val scored: Int,
    val pending: Int,
    val unknown: Int,
    val overall: StanceBucket? = null,
    val byStance: List<StanceBucket> = emptyList(),
    val byMode: List<StanceBucket> = emptyList(),
    val byRegime: List<StanceBucket> = emptyList(),
)
