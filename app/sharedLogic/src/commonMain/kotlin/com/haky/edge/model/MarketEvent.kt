package com.haky.edge.model

import kotlinx.serialization.Serializable

@Serializable
data class MarketEvent(
    val date: String,           // YYYY-MM-DD
    val title: String,
    val category: String,       // "고비" | "온기"
    val impact: String,         // 한 줄 영향 설명
    val source: String? = null,
    val confirmed: Boolean = false,  // 룰 계산=true, 웹검색=false
)
