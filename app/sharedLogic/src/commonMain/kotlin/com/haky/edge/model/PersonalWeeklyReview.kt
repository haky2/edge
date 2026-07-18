package com.haky.edge.model

import kotlinx.serialization.Serializable

/** B2 개인 주간 회고 응답 (POST /weekly-review/personal). */
@Serializable
data class PersonalWeeklyReview(
    val weekStart: String,
    val weekEnd: String,
    val factLines: String,
    val comment: String,
    val summary: String? = null,
    val generatedAt: String = "",
    val tradeCount: Int = 0,
)

/** 이번 주 매매 1건(buy/sell). date = YYYY-MM-DD(KST). */
@Serializable
data class WeeklyTradeEntry(
    val code: String,
    val name: String? = null,
    val action: String,
    val reason: String? = null,
    val price: Long? = null,
    val date: String,
)

/** 이번 주 논지 변경 1건. changedOn = YYYY-MM-DD(KST). */
@Serializable
data class WeeklyThesisChangeEntry(
    val code: String,
    val thesis: String,
    val changedOn: String,
)

/** POST /weekly-review/personal 요청 바디. */
@Serializable
data class PersonalWeeklyReviewRequest(
    val positions: List<PersonalWeeklyPositionEntry>,
    val trades: List<WeeklyTradeEntry> = emptyList(),
    val thesisChanges: List<WeeklyThesisChangeEntry> = emptyList(),
    val refresh: Boolean = false,
)

/** 보유 포지션 1건(평단·수량). avgPrice = 수량 가중평균(다계좌 병합 후). */
@Serializable
data class PersonalWeeklyPositionEntry(val code: String, val avgPrice: Double, val qty: Long)
