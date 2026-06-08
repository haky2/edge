package com.haky.edge.model

import kotlinx.serialization.Serializable

/** PER/PBR 역사적 밴드 + 현재 백분위. GET /valuation-band/{code} 응답. */
@Serializable
data class ValuationBand(
    val code: String,
    val perCurrent: Double,
    val perMin: Double,
    val perMax: Double,
    val perMedian: Double,
    val perPercentile: Int,   // 0~100, -1=계산 불가
    val perLabel: String,
    val pbrCurrent: Double,
    val pbrMin: Double,
    val pbrMax: Double,
    val pbrMedian: Double,
    val pbrPercentile: Int,
    val pbrLabel: String,
    val yearsUsed: Int,
)
