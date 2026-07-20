package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 시나리오 안 종목 1개의 조건부 손익. */
@Serializable
data class StressStock(
    val code: String,
    val name: String,
    val beta: Double,
    val pnlPct: Double,
    val pnlAmount: Long,
)

/** 코스피 등락 시나리오 1개. */
@Serializable
data class StressScenario(
    val label: String,
    val kospiMovePct: Double,
    val portfolioPnlPct: Double,
    val portfolioPnlAmount: Long,
    val stocks: List<StressStock> = emptyList(),
)

/** 상관 클러스터(r≥0.7) 동반 하락 — -10% 시나리오 기준. */
@Serializable
data class StressCluster(
    val names: List<String>,
    val avgCorr: Double,
    val combinedDropAmount: Long,
    val combinedDropPct: Double,
)

/** POST /portfolio-stress — 코스피 등락 시나리오 조건부 손익(LLM 0, 실측 베타만). */
@Serializable
data class PortfolioStress(
    val date: String,
    val windowDays: Int,
    val totalValue: Long,
    val portfolioBeta: Double? = null,
    val scenarios: List<StressScenario> = emptyList(),
    val clusters: List<StressCluster> = emptyList(),
    val betaExcluded: List<String> = emptyList(),
    val caveat: String = "",
)
