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
    val summary: String? = null,        // 핵심 요약 2~3문장 (null이면 기존 코멘트만 표시)
    val generatedAt: String = "",       // 캐시 최초 생성 시각 HH:mm (KST)
    val generatedPrice: Double? = null, // 코멘트 생성 시점 현재가 — stale 감지용
    val factsRichness: FactsRichness? = null,
    val numberWarning: Boolean = false, // facts에 없는 수치가 응답에서 발견됨
    // 판단 변화 추적: 이번 스탠스(긍정/중립/부정, 미상이면 null)와 직전 생성분 스탠스·기준일.
    // 둘 다 있으면 카드에 "유지/전환" 배지 — 전환이 강조 대상. 첫 분석이면 prevStance=null(배지 없음).
    val stance: String? = null,
    val prevStance: String? = null,
    val prevStanceDate: String? = null,
)
