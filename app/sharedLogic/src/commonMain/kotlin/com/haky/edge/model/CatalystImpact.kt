package com.haky.edge.model

import kotlinx.serialization.Serializable

/** F2 수주 공시 임팩트 — horizon(1·5·20 거래일)별 forward return 통계. */
@Serializable
data class ImpactHorizon(
    val days: Int,
    val avgPct: Double,
    val winRatePct: Double,
    val n: Int,              // 해당 horizon 측정 가능 건수(forward 봉 부족분 제외됨)
)

@Serializable
data class ImpactStats(
    val n: Int,
    val horizons: List<ImpactHorizon>,
)

/** 선반영 여부별 분해 — null = 해당 그룹 이벤트 없음 */
@Serializable
data class ImpactSplit(
    val fresh: ImpactStats?,
    val reflected: ImpactStats?,
)

/** GET /catalyst-impact/{code} 응답. F2 수주 공시 임팩트 통계. */
@Serializable
data class CatalystImpact(
    val code: String,
    val name: String,
    val category: String,
    val n: Int,
    val horizons: List<ImpactHorizon>,
    val preReflectedSplit: ImpactSplit,
    val caveat: String,
)
