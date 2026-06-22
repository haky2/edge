package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 종목 투자유의(백엔드 `GET /warnings/{code}` 응답). 토스 Open API 기반(한투 미제공).
 * 시장경보(투자주의/경고/위험)·단기과열·정리매매·VI 등. 발동 항목이 없으면 빈 리스트.
 * label·severity 는 백엔드가 매핑해 내려준다 — 앱은 그대로 표시만(매핑 중복 방지).
 */
@Serializable
data class StockWarning(
    val type: String,          // 토스 원본 enum (안정적 참조용)
    val label: String,         // 한글 표시명 (예: "투자경고")
    val severity: String,      // "danger"(빨강) | "warn"(주황) | "info"(회색)
    val startDate: String = "",
    val endDate: String? = null, // null/빈값 = 진행 중(해제일 미정)
)
