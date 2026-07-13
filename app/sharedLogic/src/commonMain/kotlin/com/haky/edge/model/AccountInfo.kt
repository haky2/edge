package com.haky.edge.model

data class AccountInfo(
    val id: Long,
    val name: String,
    val sortOrder: Long,
    val isDefault: Long,
    val horizon: String,  // 'long'=장기, 'free'=자유
)
