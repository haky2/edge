package com.haky.edge.model

import kotlinx.serialization.Serializable

/** DART 배당사항 기반 배당 카드. GET /dividend/{code} 응답. 무배당이면 404 */
@Serializable
data class DividendCard(
    val code: String,
    val fiscalYear: Int,
    val dpsThis: Long,
    val dpsPrev: Long? = null,
    val dpsPrev2: Long? = null,
    val dpsYoyPct: Double? = null,
    val yieldPctAtRecord: Double? = null,
    val payoutPct: Double? = null,
    val settleMonth: Int? = null,
    val expectedYieldPct: Double? = null,
)
