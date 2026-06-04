package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 종목 일별 수급 1건(백엔드 `GET /investor/{code}` 응답과 1:1).
 * 순매수 수량(주), 부호 포함: +면 순매수, -면 순매도. 백엔드가 장후 확정 일별값만 내려준다(미확정 당일 제외).
 */
@Serializable
data class InvestorFlow(
    val date: String,        // 영업일 YYYYMMDD
    val foreign: Long,       // 외국인 순매수 수량
    val institution: Long,   // 기관 순매수 수량
    val individual: Long,    // 개인 순매수 수량
)
