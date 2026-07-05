package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 무효화 조건 1개. threshold: 가격 타입=원, flow_exit=연속 순매도 일수. 백엔드와 일치. */
@Serializable
data class Invalidation(
    val type: String,
    val threshold: Double? = null,
    val anchor: String? = null,
    val description: String,
    val active: Boolean = true,
    val firedAt: String? = null,
)

/** 매수 프리모템(F5) — 매수 가설이 깨지는 조건 목록. 종목당 최신 1건. */
@Serializable
data class Premortem(
    val code: String,
    val name: String,
    val createdAt: String,
    val reason: String,
    val bullCase: String = "",
    val bearCase: String = "",
    val invalidations: List<Invalidation> = emptyList(),
)

/** POST /premortem/{code} 요청 본문. */
@Serializable
data class PremortemRequest(
    val reason: String,
    val avgPrice: Double? = null,
    val qty: Long? = null,
    val stopPrice: Double? = null,
)
