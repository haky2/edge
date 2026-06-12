package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 종목 코드 → 대표 섹터 레이블 1건. /sector-classify 응답. */
@Serializable
data class SectorEntry(val code: String, val sectorLabel: String)
