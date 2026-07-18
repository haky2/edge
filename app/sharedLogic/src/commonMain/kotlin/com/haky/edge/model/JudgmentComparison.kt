package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 집계 버킷 1개 — 방향 적중률(초과수익 기준) + 평균 초과수익. */
@Serializable
data class ComparisonBucket(
    val label: String,
    val n: Int,
    val wins: Int,
    val winRatePct: Double,
    val avgExcessPct: Double,
    val avgRawPct: Double,
)

/** 관심 후 미매수 기회비용 관찰. */
@Serializable
data class MissedInterestStats(
    val n: Int,
    val roseN: Int,
    val avgExcessPct: Double,
    val aiPositiveN: Int,
    val aiPositiveRoseN: Int,
)

/**
 * POST /judgment-comparison — "AI 말 들었으면?" 반사실 성적 대조.
 * 내 매매·AI 스탠스를 같은 잣대(20거래일 초과수익)로 채점해 나란히 놓는다.
 */
@Serializable
data class JudgmentComparison(
    val date: String,
    val horizonDays: Int,
    val myBuy: ComparisonBucket? = null,
    val mySell: ComparisonBucket? = null,
    val aiPositive: ComparisonBucket? = null,
    val aiNegative: ComparisonBucket? = null,
    val buyMatrix: List<ComparisonBucket> = emptyList(),
    val sellMatrix: List<ComparisonBucket> = emptyList(),
    val missedInterest: MissedInterestStats? = null,
    val pendingTrades: Int = 0,
    val caveat: String = "",
)

/** 행동 로그 1건 — POST /judgment-comparison 요청 바디의 원소. */
@Serializable
data class JudgmentTradeEntry(
    val code: String,
    val action: String,
    val date: String,
)

/** POST /judgment-comparison 요청 바디. */
@Serializable
data class JudgmentComparisonRequest(
    val trades: List<JudgmentTradeEntry> = emptyList(),
)
