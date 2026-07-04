package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 오늘의 상태 벡터(설명용). 백엔드 AnalogService와 필드 일치. */
@Serializable
data class AnalogVector(
    val pos52w: Double,       // 52주 위치(%)
    val ret20: Double,        // 최근 20거래일 수익률(%)
    val volumeRatio: Double,  // 당일 거래량 / 20일 평균
    val rsi14: Double,        // RSI(14, Wilder)
)

/** horizon 1개(5/20/60거래일)의 forward return 분포. 수익률은 %. */
@Serializable
data class AnalogHorizon(
    val days: Int,
    val winRate: Double,      // 양수 비율(0~100)
    val median: Double,
    val avg: Double,
    val min: Double,
    val max: Double,
)

/** GET /analog/{code} — 유사 국면 통계(F1). n=0이면 horizons 비어 있음(caveat에 사유). */
@Serializable
data class AnalogReport(
    val code: String,
    val name: String,
    val date: String,
    val vectorToday: AnalogVector? = null,
    val n: Int,
    val matchedDates: List<String> = emptyList(),
    val horizons: List<AnalogHorizon> = emptyList(),
    val peersIncluded: Boolean = false,
    val caveat: String,
)
