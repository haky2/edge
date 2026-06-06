package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 매크로 → 내 종목 영향 분석(백엔드 `GET /macro-impact` 응답과 1:1).
 * comment = 보유/관심을 묶어 해석한 Claude 종합 코멘트. 사실(방향)은 백엔드 계산, Claude는 해석만.
 */
@Serializable
data class MacroImpact(
    val date: String,
    val comment: String,
    val indicators: List<MacroIndicator>,
    val holdings: List<StockImpact>,
    val watchlist: List<StockImpact>,
    val generatedAt: String = "",  // 캐시 최초 생성 시각 HH:mm (KST)
)

/** 종목 1개에 대한 매크로 영향(계산 기반). net: "우호적"/"부담"/"중립"/"-"(매핑 없음). */
@Serializable
data class StockImpact(
    val code: String,
    val name: String,
    val sectorLabel: String,
    val net: String,
    val signals: List<MacroSignal>,
)

/** 종목 × 지표 1건의 방향 신호. direction: +1 우호 / 0 중립 / -1 부담. */
@Serializable
data class MacroSignal(
    val indicator: String,
    val changeRate: Double,
    val direction: Int,
    val note: String,
)
