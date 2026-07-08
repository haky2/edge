package com.haky.edge.model

import kotlinx.serialization.Serializable

@Serializable
data class DiscoverySignal(val type: String, val detail: String)

@Serializable
data class DiscoveryCandidate(
    val code: String,
    val name: String,
    val sector: String,
    val price: Long,
    val changeRate: Double,
    val signals: List<DiscoverySignal>,
)

@Serializable
data class DiscoveryReport(
    val date: String,
    val universeSize: Int,
    val scannedSize: Int,
    val candidates: List<DiscoveryCandidate>,
    val caveat: String,
)
