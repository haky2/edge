package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 해외 종목 검색 결과 1건. 백엔드 `/overseas/search` 응답(OverseasMaster).
 * code = "US:NAS:AAPL" 형식 — 앱이 watchlist 추가 및 시세 조회에 그대로 사용.
 */
@Serializable
data class OverseasStockInfo(
    val code: String,      // "US:NAS:AAPL"
    val symb: String,      // "AAPL"
    val name: String,      // 한글명 (없으면 영문명)
    val nameEn: String,    // 영문명
    val market: String,    // "NAS", "NYS", "AMS"
    val currency: String,  // "USD"
)
