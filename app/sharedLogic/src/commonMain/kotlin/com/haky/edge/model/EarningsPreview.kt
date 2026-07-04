package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 과거 정기공시 접수일들의 시장 반응 통계(수익률 %). 백엔드 EarningsPreviewService와 일치. */
@Serializable
data class PastReactions(
    val n: Int,
    val day1AvgPct: Double,
    val day5AvgPct: Double,
    val day1WinRatePct: Double,
)

/** GET /earnings-preview/{code} — 실적 발표 프리뷰(F3). run-rate YoY + 과거 발표 반응. */
@Serializable
data class EarningsPreview(
    val code: String,
    val name: String,
    val date: String,
    val dDay: Int? = null,
    val nextReport: String? = null,
    val nextDate: String? = null,
    val runRateYoYPct: Double? = null,
    val runRateLabel: String? = null,
    val pastReactions: PastReactions? = null,
    val caveat: String,
)
