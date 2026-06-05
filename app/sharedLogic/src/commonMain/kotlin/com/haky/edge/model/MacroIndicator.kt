package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 매크로 지표 1건(백엔드 `GET /macro` 응답과 1:1). 브리핑 "시장 지표" 섹션용.
 * change/changeRate 는 부호 포함(상승 +, 하락 −).
 */
@Serializable
data class MacroIndicator(
    val key: String,        // "kospi","kosdaq","usdkrw","dow","nasdaq","sp500","crude","fear_greed"
    val label: String,      // "코스피", "원/달러" 등 표시용
    val value: Double,      // 현재 지수/환율/점수
    val change: Double,     // 전일 대비 (부호 포함)
    val changeRate: Double, // 등락률 % (부호 포함)
    val tag: String = "",   // 부가 라벨. 공포탐욕지수는 "탐욕"/"공포" 등, 나머지는 빈 문자열.
)
