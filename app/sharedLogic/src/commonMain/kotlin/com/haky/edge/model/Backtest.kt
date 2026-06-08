package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 단일 신호의 익일 성과. GET /backtest/{code} 응답의 한 항목. */
@Serializable
data class SignalResult(
    val signal: String,        // "외인 순매수" 등
    val n: Int,                // 표본수
    val winRate: Int,          // 익일 상승확률 % (0~100), n==0이면 -1
    val avgReturn: Double,     // 익일 평균 수익률 %
    val edge: Double,          // 평소 대비 초과 %p
    val confident: Boolean,    // n>=8 일 때만 신뢰
)

/** 신호별 익일 적중률 백테스트. GET /backtest/{code} 응답. */
@Serializable
data class Backtest(
    val code: String,
    val tradingDays: Int,
    val flowDays: Int,
    val baselineWinRate: Int,
    val baselineAvgReturn: Double,
    val signals: List<SignalResult>,
)
