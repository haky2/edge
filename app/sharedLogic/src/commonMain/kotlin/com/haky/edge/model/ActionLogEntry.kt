package com.haky.edge.model

/** 행동 로그 1건. action: "interest" | "buy" | "sell". reason 은 선택. */
data class ActionLogEntry(
    val id: Long,
    val code: String,
    val action: String,
    val reason: String?,
    val createdAt: Long,  // epoch millis
)
