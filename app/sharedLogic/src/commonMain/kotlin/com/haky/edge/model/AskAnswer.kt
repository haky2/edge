package com.haky.edge.model

import kotlinx.serialization.Serializable

/** Q&A 히스토리 1턴 — 후속 질문 시 이전 대화를 서버에 보내어 맥락을 이어간다(서버 무상태). */
@Serializable
data class AskTurn(val question: String, val answer: String)

/** POST /ask/{code} 응답. 자유 질문에 대한 사실 데이터 기반 답변. */
@Serializable
data class AskAnswer(
    val code: String,
    val name: String,
    val date: String,       // 기준 거래일 (YYYY-MM-DD)
    val question: String,
    val answer: String,
    val generatedAt: String, // 생성 시각 HH:mm (KST)
)

/** POST /ask/{code} 요청 바디. */
@Serializable
data class AskRequest(
    val question: String,
    val avgPrice: Double? = null,
    val qty: Long? = null,
    val targetPrice: Double? = null,
    val stopPrice: Double? = null,
    val mode: String? = null,
    val history: List<AskTurn> = emptyList(),
)
