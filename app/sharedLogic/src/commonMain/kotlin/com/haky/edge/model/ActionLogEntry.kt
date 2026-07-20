package com.haky.edge.model

/** 행동 로그 1건. action: "interest" | "buy" | "sell". reason 은 선택. */
data class ActionLogEntry(
    val id: Long,
    val code: String,
    val name: String?,    // 종목명. 구버전 DB 행은 null → UI에서 nameMap 폴백.
    val action: String,
    val reason: String?,
    val price: Long?,        // 기록 시점 현재가(원). 구버전 DB 행은 null.
    val stopPrice: Long?,    // 기록 시점 holding 손절가 스냅샷(원). 없으면 null(T1).
    val targetPrice: Long?,  // 기록 시점 holding 목표가 스냅샷(원). 없으면 null(T1).
    val createdAt: Long,  // epoch millis
)
