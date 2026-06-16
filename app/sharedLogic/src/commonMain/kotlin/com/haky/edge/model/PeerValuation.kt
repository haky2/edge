package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 한 지표(PER 또는 PBR)의 동종 상대 위치. */
@Serializable
data class PeerMetric(
    val current: Double,
    val peerMedian: Double,
    val peerMin: Double,
    val peerMax: Double,
    val diffPct: Double,   // (current - median) / median * 100
    val label: String,     // 동종 대비 낮음 / 동종과 비슷 / 동종 대비 높음
)

/** 동종(peer) 상대 밸류에이션. GET /peer-valuation/{code} 응답. */
@Serializable
data class PeerValuation(
    val code: String,
    val clusterLabel: String,  // 방산/조선/IT서비스 …
    val peerCount: Int,
    val per: PeerMetric? = null,
    val pbr: PeerMetric? = null,
)
