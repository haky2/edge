package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 백엔드 `/overseas/quote` 응답 모델. 가격은 Double(소수점, USD 등).
 * 국내 Quote(Long·원화)와 분리. code = "US:NAS:AAPL" 형식.
 */
@Serializable
data class OverseasQuote(
    val code: String,           // "US:NAS:AAPL"
    val symb: String,           // "AAPL"
    val price: Double,
    val change: Double,         // 전일 대비 (부호 포함)
    val changeRate: Double,     // 등락률 % (부호 포함)
    val open: Double,
    val high: Double,
    val low: Double,
    val high52w: Double,
    val low52w: Double,
    val volume: Long,
    val currency: String,       // "USD", "HKD" 등
    val delayed: Boolean = true, // 한투 해외 기본 15분 지연
)
