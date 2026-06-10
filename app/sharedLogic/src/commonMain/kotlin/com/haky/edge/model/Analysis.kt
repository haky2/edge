package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 코멘트 생성 시 사용된 데이터 소스 유무. 앱에서 "근거 두께" 표시에 쓴다. */
@Serializable
data class FactsRichness(
    val newsCount: Int = 0,
    val hasInvestorFlow: Boolean = false,
    val hasFinancials: Boolean = false,
    val hasQuarterlyIncome: Boolean = false,
    val hasShortSelling: Boolean = false,
    val hasValuationBand: Boolean = false,
    val hasBacktest: Boolean = false,
    val hasFlowSensitivity: Boolean = false,
)

/**
 * 종목 종합 코멘트(백엔드 `GET /analysis/{code}` 응답).
 * 사실(시세·52주·PER·수급·뉴스)을 백엔드가 모아 Claude가 해석한 결과. 참고용 — 매매 판단·책임은 사용자.
 */
@Serializable
data class Analysis(
    val code: String,
    val name: String,
    val date: String,       // 생성 기준일 YYYY-MM-DD
    val comment: String,
    val generatedAt: String = "",       // 캐시 최초 생성 시각 HH:mm (KST)
    val generatedPrice: Double? = null, // 코멘트 생성 시점 현재가 — stale 감지용
    val factsRichness: FactsRichness? = null,
    val numberWarning: Boolean = false, // facts에 없는 수치가 응답에서 발견됨
)
