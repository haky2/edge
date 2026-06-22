package com.haky.edge.toss

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 이 파일은 두 종류의 모델을 담는다(KisModels 와 같은 경계 격리 원칙):
//  (1) Toss* — 토스가 "우리에게 주는" 원본 모델(필드명이 토스 규약).
//  (2) 앱에 내려주는 정규화 DTO 는 슬라이스가 진행되며 추가한다(슬라이스0은 인증/연결 확인만).
//
// 토스 OpenAPI 메모:
//  - 인증: OAuth2 client_credentials. POST /oauth2/token, content-type=application/x-www-form-urlencoded.
//  - 시세 등 읽기 호출: Authorization: Bearer {access_token} 헤더만 요구(계좌/주문만 X-Tossinvest-Account 추가).
//  - 정상 응답은 보통 { "result": ... } 래퍼. 에러는 { "error": { requestId, code, message, data? } }.

/** 토스 OAuth 토큰 응답. 정상이면 accessToken, 실패면 error/errorDescription 이 채워진다. */
@Serializable
data class TossTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 86400, // 보통 24시간(초)
    // 실패 응답(400/401): { "error": "invalid_client", "error_description": "..." }
    @SerialName("error") val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)

/** GET /api/v1/prices 응답 래퍼. result 가 종목별 현재가 배열. */
@Serializable
data class TossPricesResponse(
    val result: List<TossPrice> = emptyList(),
)

/**
 * 토스 현재가 1건. lastPrice 만 준다(한투 inquire-price 와 달리 등락률/거래량/52주 없음).
 * price 는 문자열로 오므로(예: "72000") 숫자가 필요하면 toLongOrNull 등으로 변환해 쓴다.
 */
@Serializable
data class TossPrice(
    val symbol: String = "",
    val timestamp: String = "",
    val lastPrice: String = "",
    val currency: String = "",
)

// ── 투자유의(warnings) ──────────────────────────────────────────────
// GET /api/v1/stocks/{symbol}/warnings → { "result": [ { warningType, exchange, startDate, endDate } ] }
// 한투엔 이만큼 정돈된 API가 없어 토스로만 채우는 신규 데이터(시장경보·단기과열·정리매매·VI).

/** 토스 warnings 응답 래퍼. 발동 항목이 없으면 빈 배열. */
@Serializable
data class TossWarningsResponse(
    val result: List<TossWarning> = emptyList(),
)

/** 토스 원본 투자유의 1건. endDate 가 null/빈값이면 진행 중(해제일 미정). */
@Serializable
data class TossWarning(
    val warningType: String = "",
    val exchange: String = "",
    val startDate: String = "",
    val endDate: String? = null,
)

/**
 * 앱에 내려주는 정규화 투자유의. label(한글)·severity(칩 색)를 백엔드에서 한 번 매핑해
 * iOS/Android 가 동일하게 표시하도록 한다(매핑 중복 방지).
 * severity: "danger"(빨강) | "warn"(주황) | "info"(회색).
 */
@Serializable
data class StockWarning(
    val type: String,          // 토스 원본 enum (안정적 참조용)
    val label: String,         // 한글 표시명 (예: "투자경고")
    val severity: String,      // danger | warn | info
    val startDate: String = "",
    val endDate: String? = null,
)

/** 토스 warningType → (한글 label, severity). 미정의 enum 은 원본 문자열 + info 로 폴백(깨지지 않게). */
fun TossWarning.toStockWarning(): StockWarning {
    val (label, severity) = when (warningType) {
        "INVESTMENT_RISK" -> "투자위험" to "danger"
        "INVESTMENT_WARNING" -> "투자경고" to "danger"
        "LIQUIDATION_TRADING" -> "정리매매" to "danger"
        "OVERHEATED" -> "단기과열" to "warn"
        "VI_STATIC" -> "정적VI" to "info"
        "VI_DYNAMIC" -> "동적VI" to "info"
        "VI_STATIC_AND_DYNAMIC" -> "VI(정적·동적)" to "info"
        "STOCK_WARRANTS" -> "신주인수권증권" to "info"
        else -> warningType to "info"
    }
    return StockWarning(type = warningType, label = label, severity = severity, startDate = startDate, endDate = endDate)
}

class TossException(message: String) : RuntimeException(message)
