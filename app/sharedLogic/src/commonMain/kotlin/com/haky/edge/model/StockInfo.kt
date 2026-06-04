package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 종목 검색 결과 1건. 백엔드 `GET /search` 응답(StockMaster)과 같은 형태로,
 * 앱은 이걸 그대로 받아 관심종목 추가(code+name)에 쓴다. market은 표시용(KOSPI|KOSDAQ).
 */
@Serializable
data class StockInfo(
    val code: String,
    val name: String,
    val market: String,
)
