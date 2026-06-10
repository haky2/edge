package com.haky.edge.model

import kotlinx.serialization.Serializable

@Serializable
data class ComparisonDetail(
    val code: String,
    val name: String,
    val price: Long,
    val changeRate: Double,
    val per: Double,
    val pbr: Double,
    val week52PosPct: Double,
    val upsidePct: Double? = null,
    val valuationLabel: String? = null,
    val foreignNet3d: Long,
    val institutionNet3d: Long,
    val quarterlyYoy: Double? = null,
)

@Serializable
data class Comparison(
    val a: ComparisonDetail,
    val b: ComparisonDetail,
    val comment: String,
    val generatedAt: String = "",
)
