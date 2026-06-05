package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 종목 1건의 다음 정기공시 예정(백엔드 `GET /earnings` 응답과 1:1). 브리핑 "실적 일정" 섹션용.
 * daysUntil: 양수=남은 일수, 0=당일, 음수=제출 기한 지남.
 */
@Serializable
data class EarningsEntry(
    val code: String,
    val corpName: String,
    val reportName: String,  // "반기보고서 (2026.06)"
    val dueDate: String,     // "20260814"
    val daysUntil: Int,
)
