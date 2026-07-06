package com.haky.edge.model

data class Holding(
    val id: Long,
    val code: String,
    val name: String,
    val accountId: Long,
    val avgPrice: Double? = null,
    val qty: Long? = null,
    val targetPrice: Double? = null,
    val stopPrice: Double? = null,
)
