package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 네이버 금융 컨센서스 목표주가. basis 는 기준 설명(최근 3개월 증권사 평균). */
@Serializable
data class TargetPriceInfo(
    val code: String,
    val price: Long,
    val basis: String,
)
