package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 브리핑 "오늘 시장 분위기" 카드용 Claude 코멘트 + 지표. */
@Serializable
data class MarketMood(
    val date: String,
    val comment: String,                  // Claude 시장 전체 분위기 해석
    val indicators: List<MacroIndicator>, // 코멘트 생성에 쓴 지표 전체
    val generatedAt: String = "",         // 캐시 최초 생성 시각 HH:mm (KST)
)
