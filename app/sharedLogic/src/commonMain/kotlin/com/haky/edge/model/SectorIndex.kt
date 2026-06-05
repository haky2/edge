package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 브리핑 "섹터 동향" 섹션용 업종지수 1건. change/changeRate 는 부호 포함(상승 +, 하락 −). */
@Serializable
data class SectorIndex(
    val key: String,        // "sector_0014" 등
    val label: String,      // "전기전자", "기계" 등 표시용
    val value: Double,      // 현재 지수
    val change: Double,     // 전일 대비
    val changeRate: Double, // 등락률 %
)
