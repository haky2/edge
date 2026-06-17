package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 브리핑용: 섹터 단위로 묶은 재료 동향 한 줄(`GET /catalyst-brief` 응답 item). */
@Serializable
data class SectorCatalystLine(
    val sector: String,
    val bias: String,             // "호재우위" | "악재우위" | "혼조"
    val line: String,             // "종목A·종목B — 한 줄 요약"
    val stockNames: List<String>, // 비중립 종목 이름 목록
)

/** 브리핑용: 관심종목 재료 동향 집계(`GET /catalyst-brief` 응답). */
@Serializable
data class CatalystBriefReport(
    val date: String,
    val sectors: List<SectorCatalystLine>,
)
