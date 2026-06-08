package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 종목별 공매도 요약(백엔드 `GET /short-selling/{code}` 응답).
 * KRX 공매도 종합 포탈에서 수집. 잔고는 T+2 영업일 지연 확정.
 */
@Serializable
data class ShortSellingSummary(
    val code: String,
    val recentVolume: Long,          // 최근 거래일 공매도 거래량 (주)
    val recentVolumeDate: String,    // 해당 거래일 ("2026/06/05")
    val balance: Long?,              // 최신 공매도 잔고 (주), null = 집계 중
    val balanceDate: String?,        // 잔고 기준일
    val balanceChangePct: Double?,   // 전 확정일 대비 잔고 변화율 (%)
)
