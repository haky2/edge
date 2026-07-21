package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 집계 버킷 1개(전체/스탠스별/모드별/레짐별 공용). 백엔드 StanceStatsService와 필드 일치.
 * baseRatePct = 기저율(항상 같은 스탠스로 말했을 때의 적중률) — accuracyPct가 이보다 높아야 정보 있음.
 */
@Serializable
data class StanceBucket(
    val label: String,
    val n: Int,
    val correct: Int,
    val accuracyPct: Double,
    val avgExcessPct: Double? = null,   // 평균 초과수익(종목−코스피, %)
    val baseRatePct: Double? = null,
)

/**
 * GET /stance-stats — 종목 코멘트 스탠스(긍정/중립/부정) vs 20거래일 코스피 대비 초과수익 채점(F6→X4).
 * 브리핑의 "AI 적중률"(시장 방향 예측)과는 별도 지표. 판단 대조와 동일 잣대.
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
    val refN: Int = 15,       // 이 미만 버킷은 참고 수준(색·판정 유보)
    val caveat: String = "",
)
