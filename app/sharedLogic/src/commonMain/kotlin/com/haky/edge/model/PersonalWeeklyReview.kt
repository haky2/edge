package com.haky.edge.model

import kotlinx.serialization.Serializable

/** B2 개인 주간 회고 응답 (POST /weekly-review/personal). */
@Serializable
data class PersonalWeeklyReview(
    val weekStart: String,
    val weekEnd: String,
    val factLines: String,
    val holdingMoves: List<HoldingMove> = emptyList(),
    val comment: String,
    val summary: String? = null,
    val generatedAt: String = "",
    val tradeCount: Int = 0,
)

/** 보유 종목 1건의 주간 등락 — 앱이 종목명(좌)·등락률(우, 색상) 행으로 렌더. */
@Serializable
data class HoldingMove(val name: String, val changePct: Double)

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
    // L1: 행동 데이터 — 전체 행동 로그(판단대조 서버 재채점)·규율 성적 요약(T1 스냅샷 기반 로컬 계산).
    val allTrades: List<JudgmentTradeEntry> = emptyList(),
    val discipline: DisciplineSummaryEntry? = null,
)

/** 손절/익절 규율 성적 요약(누적) — 앱 DisciplineRow 분류 카운트. */
@Serializable
data class DisciplineSummaryEntry(
    val pairs: Int,
    val targetReached: Int,
    val profitExit: Int,
    val stopRespected: Int,
    val stopViolated: Int,
)

/** 보유 포지션 1건(평단·수량). avgPrice = 수량 가중평균(다계좌 병합 후). */
@Serializable
data class PersonalWeeklyPositionEntry(val code: String, val avgPrice: Double, val qty: Long)
