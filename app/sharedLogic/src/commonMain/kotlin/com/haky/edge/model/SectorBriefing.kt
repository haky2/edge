package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 브리핑 "섹터 분석" 섹션용 Claude 코멘트 + 주목 종목. */
@Serializable
data class SectorBriefing(
    val date: String,
    val comment: String,              // Claude 섹터 트렌드 해석
    val spotlight: List<SpotlightStock>, // 오늘 강세 섹터에 속한 관심종목
)

/** 섹터 브리핑 주목 종목 1건. */
@Serializable
data class SpotlightStock(
    val code: String,
    val name: String,
    val sectorLabel: String,
)
