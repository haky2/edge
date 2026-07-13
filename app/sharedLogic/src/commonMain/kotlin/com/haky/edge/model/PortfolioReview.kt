package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 섹터 1개의 평가금액 비중. */
@Serializable
data class SectorWeight(val label: String, val weightPct: Double, val stockNames: List<String>)

/** 매크로 지표 1개에 대한 포트폴리오 구조 노출(비중 가중). */
@Serializable
data class MacroExposure(val label: String, val favorablePct: Double, val adversePct: Double)

/** 밸류 밴드 위치 1구간의 비중. */
@Serializable
data class ValuationBucket(val label: String, val weightPct: Double, val count: Int)

/** POST /portfolio-review 요청 바디 — 종목 1건. */
@Serializable
data class ReviewPositionEntry(
    val code: String,
    val avgPrice: Double,
    val qty: Long,
    val thesis: String? = null,
)

/** POST /portfolio-review 요청 바디 전체. */
@Serializable
data class PortfolioReviewRequest(
    val positions: List<ReviewPositionEntry>,
    val mode: String? = null,
    val refresh: Boolean = false,
    // "account" = 계좌 탭 범위(부분 포트폴리오) — 서버가 리밸런싱 스냅샷(R1)을 갱신하지 않는다.
    // null(기본) = 전체. 기본값이라 encodeDefaults=false에서 미전송 → 구버전 서버와도 호환.
    val scope: String? = null,
    // "long" = 장기 계좌 컨텍스트(장기 리밸런싱 관점 진단 + 캐시 분리). null = 기존 동작.
    val horizon: String? = null,
)

/** POST /portfolio-review 응답. 수치 필드는 전부 계산(사실), comment/summary만 Claude 해석. */
@Serializable
data class PortfolioReview(
    val date: String,
    val comment: String,
    val summary: String? = null,
    val generatedAt: String = "",
    val stockCount: Int,
    val totalValue: Long,
    val totalCost: Long,
    val totalPnl: Long,
    val totalPnlPct: Double,
    val topStockName: String? = null,
    val topStockWeightPct: Double? = null,
    val topSectorLabel: String? = null,
    val topSectorWeightPct: Double? = null,
    val sectors: List<SectorWeight> = emptyList(),
    val exposures: List<MacroExposure> = emptyList(),
    val valuationDist: List<ValuationBucket> = emptyList(),
)
