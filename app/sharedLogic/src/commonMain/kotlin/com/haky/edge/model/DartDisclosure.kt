package com.haky.edge.model

import kotlinx.serialization.Serializable

/** DART 공시 1건. 백엔드 `GET /dart/{code}` 응답과 1:1. */
@Serializable
data class DartDisclosure(
    val corpName: String,
    val reportName: String,
    val date: String,   // YYYYMMDD
    val url: String,
)
