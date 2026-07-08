package com.haky.edge.model

import kotlinx.serialization.Serializable

@Serializable
data class SectorStrength(
    val label: String,
    val ret5: Double,
    val ret20: Double,
    val rank5: Int,
    val rank20: Int,
    val rankDelta: Int,   // rank20 - rank5: 양수=단기 순위 상승=유입 조짐
)

@Serializable
data class SectorRotation(
    val date: String,
    val sectors: List<SectorStrength>,
    val inflow: List<String>,
    val outflow: List<String>,
    val factsText: String? = null,
)
