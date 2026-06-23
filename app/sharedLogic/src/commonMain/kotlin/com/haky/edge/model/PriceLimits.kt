package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 종목 가격 제한폭(백엔드 `GET /price-limits/{code}` 응답). 토스 Open API 기반(한투 미제공).
 * 둘 다 null이면 제한폭 없는 시장(미국 등) → 카드 숨김.
 */
@Serializable
data class PriceLimits(
    val upper: Long? = null,  // 상한가
    val lower: Long? = null,  // 하한가
    val currency: String = "KRW",
)
