package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 한 주체(외인/기관)의 순매수량 vs 당일 등락률 Pearson 상관 결과. */
@Serializable
data class FlowCorrelation(
    val investor: String,    // "외인" / "기관"
    val r: Double,           // Pearson r (-1.0~1.0)
    val label: String,       // "양의 중간 상관" 등
    val n: Int,              // 매칭된 표본일수
    val confident: Boolean,  // n >= 8
)

/** 종목별 수급-가격 민감도. GET /flow-sensitivity/{code} 응답. */
@Serializable
data class FlowSensitivity(
    val code: String,
    val items: List<FlowCorrelation>,
)
