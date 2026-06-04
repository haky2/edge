package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 일봉 1개(백엔드 `GET /daily/{code}` 응답과 1:1). 최신일이 앞. */
@Serializable
data class DailyBar(
    val date: String,  // YYYYMMDD
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
)
