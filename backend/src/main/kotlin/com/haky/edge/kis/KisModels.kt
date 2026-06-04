package com.haky.edge.kis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 이 파일은 두 종류의 모델을 담는다:
//  (1) Quote  — 우리가 "앱에 내려주는" 깔끔한 모델(필드명도 우리가 정함)
//  (2) Kis*   — 한투가 "우리에게 주는" 원본 모델(필드명이 stck_prpr 처럼 한투 규약)
// 둘을 분리하는 이유: 한투 응답 포맷이 바뀌어도 앱이 보는 Quote 는 그대로 유지하기 위해(경계 격리).

/** 앱에 내려주는 정규화된 시세 DTO. 종목명은 여기 없다 — inquire-price엔 이름이 없어서 검색(StockMaster)에서 얻는다. */
@Serializable
data class Quote(
    val code: String,
    val price: Long,        // 현재가
    val change: Long,       // 전일 대비 (한투가 이미 부호 포함해 제공)
    val changeRate: Double, // 등락률 %
    val volume: Long,       // 누적 거래량
    val open: Long,         // 시가
    val high: Long,         // 당일 고가
    val low: Long,          // 당일 저가
    val high52w: Long,      // 52주 최고
    val low52w: Long,       // 52주 최저
    val per: Double,        // 주가수익비율 (적자 등으로 0/무의미일 수 있음)
    val pbr: Double,        // 주가순자산비율
    // EPS/BPS는 inquire-price 에선 빈 값(0)으로 와 신뢰 불가 → 정확값은 추후 DART 재무에서.
)

// 아래 한투 원본 모델들은 모든 필드에 기본값을 둔다.
// 한투가 어떤 필드를 빠뜨려 보내도 역직렬화가 깨지지 않게 하기 위함(에러 응답엔 정상 필드가 없을 수 있음).

/** 한투 OAuth 토큰 응답. 정상이면 access_token, 실패면 error_code/description 이 채워진다. */
@Serializable
data class KisTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 86400, // 보통 24시간(초)
    @SerialName("error_description") val errorDescription: String = "",
    @SerialName("error_code") val errorCode: String = "",
)

/** 토큰 발급 요청 바디. grant_type 은 항상 "client_credentials" 고정이라 기본값으로 둠. */
@Serializable
data class KisTokenRequest(
    // 기본값이지만 직렬화에 반드시 포함돼야 한다 → HttpClient 의 Json{encodeDefaults=true} 와 짝.
    @SerialName("grant_type") val grantType: String = "client_credentials",
    val appkey: String,
    val appsecret: String,
)

/**
 * 한투 현재가(inquire-price) 응답의 바깥 껍데기.
 * rt_cd 가 "0"이어야 성공(HTTP 200이어도 본문으로 성패를 알리는 한투 방식), msg1 은 사람이 읽는 메시지.
 */
@Serializable
data class KisPriceResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    val output: KisPriceOutput? = null,
)

/**
 * 실제 시세 값이 담기는 output. 한투는 숫자도 전부 "문자열"로 준다(그래서 타입이 String).
 * 응답엔 이 외에도 PER/PBR/EPS 등 수십 개 필드가 더 오지만, 지금 쓰는 것만 매핑한다(나머지는 무시됨).
 */
@Serializable
data class KisPriceOutput(
    @SerialName("stck_prpr") val price: String = "0",
    @SerialName("prdy_vrss") val change: String = "0", // 이미 부호 포함 (예: "-192000")
    @SerialName("prdy_ctrt") val changeRate: String = "0", // 이미 부호 포함 (예: "-9.58")
    @SerialName("acml_vol") val volume: String = "0",
    @SerialName("stck_oprc") val open: String = "0",
    @SerialName("stck_hgpr") val high: String = "0",
    @SerialName("stck_lwpr") val low: String = "0",
    @SerialName("w52_hgpr") val high52w: String = "0",
    @SerialName("w52_lwpr") val low52w: String = "0",
    @SerialName("per") val per: String = "0",
    @SerialName("pbr") val pbr: String = "0",
)

// ── 수급(외인/기관/개인) ──────────────────────────────────────────────
// 한투 inquire-investor(tr_id FHKST01010900, "주식현재가 투자자")는 종목별 "일별" 투자자 순매수를 준다.
// CLAUDE.md 원칙: 장후 확정 일별값을 쓴다(장중 당일 행은 추정치라 패턴 통계 오염 위험) → 최근 N일 추이로 표시.

/** 앱에 내려주는 정규화된 일별 수급 1건. 순매수 수량(주, 부호 포함: +매수 / -매도). */
@Serializable
data class InvestorFlow(
    val date: String,        // 영업일 YYYYMMDD
    val foreign: Long,       // 외국인 순매수 수량
    val institution: Long,   // 기관계 순매수 수량
    val individual: Long,    // 개인 순매수 수량
)

/** inquire-investor 응답 껍데기. output 이 일자별 배열(최신일이 앞). */
@Serializable
data class KisInvestorResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    val output: List<KisInvestorRow> = emptyList(),
)

/** 한투 원본 일별 수급 행. 숫자는 전부 문자열, 순매수 수량은 이미 부호 포함. */
@Serializable
data class KisInvestorRow(
    @SerialName("stck_bsop_date") val date: String = "",
    @SerialName("frgn_ntby_qty") val foreign: String = "0",   // 외국인 순매수 수량
    @SerialName("orgn_ntby_qty") val institution: String = "0", // 기관계 순매수 수량
    @SerialName("prsn_ntby_qty") val individual: String = "0",  // 개인 순매수 수량
)

/** 한투 연동 중 발생한 오류(상류 문제)를 일반 버그와 구분하기 위한 예외 — StatusPages에서 502로 매핑된다. */
class KisException(message: String) : RuntimeException(message)
