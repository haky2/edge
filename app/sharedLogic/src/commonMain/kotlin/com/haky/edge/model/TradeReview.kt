package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 완결된 매매 복기. 수치는 전부 일봉 계산(백엔드), summary·comment만 Claude 해석. */
@Serializable
data class TradeReview(
    val code: String,
    val name: String,
    val buyDate: String,                  // YYYY-MM-DD
    val sellDate: String,
    val holdingTradingDays: Int,
    val realizedPct: Double,
    val realizedPnl: Long? = null,
    val periodHighClose: Long? = null,
    val periodHighDate: String? = null,
    val periodLowClose: Long? = null,
    val periodLowDate: String? = null,
    val sellVsHighPct: Double? = null,    // 음수 = 고점 대비 낮게 매도
    val afterSell5dPct: Double? = null,
    val afterSell20dPct: Double? = null,
    val partialHistory: Boolean = false,
    val summary: String? = null,
    val comment: String,
    val generatedAt: String,
)

/** POST /trade-review 요청 본문. */
@Serializable
internal data class TradeReviewRequest(
    val code: String,
    val buyDate: String,
    val buyPrice: Double,
    val sellDate: String,
    val sellPrice: Double,
    val qty: Long? = null,
    val buyReason: String? = null,
    val sellReason: String? = null,
    val thesis: String? = null,
    val refresh: Boolean = false,
)
