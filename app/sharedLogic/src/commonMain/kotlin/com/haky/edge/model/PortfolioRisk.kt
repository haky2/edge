package com.haky.edge.model

import kotlinx.serialization.Serializable

@Serializable
data class RiskStock(
    val code: String,
    val name: String,
    val weightPct: Double,
    val volPct: Double,
    val beta: Double? = null,
    val riskContribPct: Double,
    val value: Long = 0,
)

@Serializable
data class CorrPair(val nameA: String, val nameB: String, val corr: Double)

@Serializable
data class RiskCluster(val names: List<String>, val weightPct: Double, val avgCorr: Double)

@Serializable
data class PortfolioRisk(
    val date: String,
    val windowDays: Int,
    val stocks: List<RiskStock> = emptyList(),
    val portfolioVolPct: Double,
    val weightedAvgVolPct: Double,
    val diversificationRatio: Double,
    val portfolioBeta: Double? = null,
    val kospiVolPct: Double? = null,
    val avgCorr: Double? = null,
    val topPairs: List<CorrPair> = emptyList(),
    val clusters: List<RiskCluster> = emptyList(),
    val hhi: Int,
    val top2WeightPct: Double,
    val excluded: List<String> = emptyList(),
    val caveat: String = "",
)

@Serializable
data class PortfolioRiskEntry(val code: String, val qty: Long)

@Serializable
data class PortfolioRiskRequest(val positions: List<PortfolioRiskEntry> = emptyList())
