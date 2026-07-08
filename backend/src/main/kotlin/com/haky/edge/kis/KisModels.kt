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
    val sectorName: String = "", // 업종명 (bstp_kor_isnm, e.g. "전기·전자")
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
    @SerialName("bstp_kor_isnm") val sectorName: String = "", // 업종명
    @SerialName("lstn_stcn") val listedShares: String = "0",  // 상장주식수 (ValuationBand 계산용)
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

// ── 일봉 차트(이평/거래량/RSI 계산용) ─────────────────────────────────
// 한투 inquire-daily-itemchartprice(tr_id FHKST03010100): 종목 일/주/월봉. 여기선 일봉(D)만.
// 지표(이평선·RSI·거래량 추세)는 원시 일봉을 앱(sharedLogic)에서 계산한다 — 테스트·Android 재사용.

/** 앱에 내려주는 일봉 1개. 최신일이 앞. */
@Serializable
data class DailyBar(
    val date: String,   // 영업일 YYYYMMDD
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
)

/** inquire-daily-itemchartprice 응답. output2 가 일자별 배열(최신일이 앞). */
@Serializable
data class KisDailyResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    @SerialName("output2") val output2: List<KisDailyBar> = emptyList(),
)

/** 한투 원본 일봉 행. 숫자는 전부 문자열. */
@Serializable
data class KisDailyBar(
    @SerialName("stck_bsop_date") val date: String = "",
    @SerialName("stck_oprc") val open: String = "0",
    @SerialName("stck_hgpr") val high: String = "0",
    @SerialName("stck_lwpr") val low: String = "0",
    @SerialName("stck_clpr") val close: String = "0",
    @SerialName("acml_vol") val volume: String = "0",
)

// ── 매크로 지표(지수·환율) ────────────────────────────────────────────
// 브리핑 "시장 지표" 섹션용. 한투 키로 바로 되는 것만 v1: 코스피·코스닥(국내 업종지수),
// 원/달러 환율·미국 주요지수(다우/나스닥/S&P, 해외 기간별시세). 유가·구리·국채·공포탐욕은 v2(별도 소스).

/** 앱에 내려주는 정규화된 매크로 지표 1건. change/changeRate 는 부호 포함(상승 +, 하락 −). */
@Serializable
data class MacroIndicator(
    val key: String,        // "kospi","kosdaq","usdkrw","dow","nasdaq","sp500","crude","fear_greed"
    val label: String,      // "코스피", "원/달러" 등 표시용
    val value: Double,      // 현재 지수/환율/점수
    val change: Double,     // 전일 대비 (부호 포함)
    val changeRate: Double, // 등락률 % (부호 포함)
    val tag: String = "",   // 부가 라벨. 공포탐욕지수는 "탐욕"/"공포" 등 텍스트 등급, 나머지 지표는 빈 문자열.
)

/**
 * 국내 업종 현재지수(inquire-index-price, tr_id FHPUP02100000) 응답.
 * 전일대비(bstp_nmix_prdy_vrss)·등락률(bstp_nmix_prdy_ctrt)은 부호가 없을 수 있어,
 * prdy_vrss_sign(1상한 2상승 3보합 4하한 5하락)으로 부호를 적용한다.
 */
@Serializable
data class KisIndexResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    val output: KisIndexOutput? = null,
)

@Serializable
data class KisIndexOutput(
    @SerialName("bstp_nmix_prpr") val price: String = "0",        // 지수 현재가
    @SerialName("bstp_nmix_prdy_vrss") val change: String = "0",  // 전일 대비
    @SerialName("prdy_vrss_sign") val sign: String = "3",         // 전일대비 부호(1~5)
    @SerialName("bstp_nmix_prdy_ctrt") val changeRate: String = "0", // 등락률
)

/**
 * 해외 지수/환율 기간별시세(inquire-daily-chartprice, tr_id FHKST03030100) 응답.
 * 현재값·전일대비 정보는 output1(요약 객체)에 담긴다(output2는 일자별 배열로 여기선 안 씀).
 */
@Serializable
data class KisOverseasResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    @SerialName("output1") val output1: KisOverseasOutput? = null,
)

@Serializable
data class KisOverseasOutput(
    @SerialName("ovrs_nmix_prpr") val price: String = "0",       // 현재가(지수/환율)
    @SerialName("ovrs_nmix_prdy_vrss") val change: String = "0", // 전일 대비
    @SerialName("prdy_vrss_sign") val sign: String = "3",        // 전일대비 부호(1~5)
    @SerialName("prdy_ctrt") val changeRate: String = "0",       // 등락률
)

// ── 섹터 대시보드 ─────────────────────────────────────────────────────
// 브리핑 "섹터 동향" 섹션용. KOSPI 업종별 현재 지수 + 등락률.

/** 앱에 내려주는 업종지수 1건. change/changeRate 는 부호 포함(상승 +, 하락 −). */
@Serializable
data class SectorIndex(
    val key: String,        // "sector_0014" 등 (ISCD 기반)
    val label: String,      // "전기전자", "기계" 등 표시용
    val value: Double,      // 현재 지수
    val change: Double,     // 전일 대비
    val changeRate: Double, // 등락률 %
)

// ── 섹터 자금 순환(C) ─────────────────────────────────────────────────
// 업종지수 일별 이력으로 5/20일 상대강도 → 순환 판정. inquire-daily-indexchartprice(FHKUP03500100).

/** 업종지수 일별 종가 1점. rotation 계산은 종가만 쓴다. */
@Serializable
data class IndexPoint(
    val date: String,   // 영업일 YYYYMMDD
    val close: Double,  // 업종지수 종가(bstp_nmix_prpr)
)

/** 업종 1개의 일별 종가 이력(최신일이 앞). */
@Serializable
data class SectorHistory(
    val label: String,           // "전기전자" 등
    val points: List<IndexPoint>, // 최신일이 앞
)

/** inquire-daily-indexchartprice 응답. output2=일자별(최신 앞). 종가 필드가 주식과 다름(bstp_nmix_prpr). */
@Serializable
data class KisIndexChartResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    @SerialName("output2") val output2: List<KisIndexChartBar> = emptyList(),
)

@Serializable
data class KisIndexChartBar(
    @SerialName("stck_bsop_date") val date: String = "",
    @SerialName("bstp_nmix_prpr") val close: String = "0", // 업종지수 종가
)

/** 한투 연동 중 발생한 오류(상류 문제)를 일반 버그와 구분하기 위한 예외 — StatusPages에서 502로 매핑된다. */
class KisException(message: String) : RuntimeException(message)

// ── 해외 종목 시세(O1) ────────────────────────────────────────────────
// 해외주식 현재가 상세(overseas-price/v1/quotations/price-detail, tr_id HHDFS76200200).
// 국내 Quote는 Long(원화 정수)이라 소수점 못 담음 → 해외는 OverseasQuote(Double+currency) 분리.
// 한투 해외시세는 기본 15~20분 지연(실시간은 별도 신청) → delayed=true 기본값.

/** 앱에 내려주는 해외 종목 시세 DTO. code는 "US:NAS:AAPL" 형식 전체 코드. */
@Serializable
data class OverseasQuote(
    val code: String,           // "US:NAS:AAPL"
    val symb: String,           // "AAPL"
    val price: Double,          // 현재가 (소수점, USD 등)
    val change: Double,         // 전일 대비 (부호 포함)
    val changeRate: Double,     // 등락률 % (부호 포함)
    val open: Double,
    val high: Double,
    val low: Double,
    val high52w: Double,        // 52주 최고가
    val low52w: Double,         // 52주 최저가
    val volume: Long,
    val currency: String,       // "USD", "HKD" 등 통화코드
    val delayed: Boolean = true, // 한투 해외는 기본 15분 지연
)

/** 한투 해외주식 현재가 상세(HHDFS76200200) 응답 껍데기. */
@Serializable
data class KisOverseasStockResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    val output: KisOverseasStockOutput? = null,
)

/**
 * 해외주식 현재가 output. 숫자는 전부 문자열.
 * diff/rate는 절댓값으로 오고 sign(1~5)으로 부호를 적용한다(국내 macro와 동일 패턴).
 */
@Serializable
data class KisOverseasStockOutput(
    @SerialName("rsym") val rsym: String = "",         // 실시간 조회 종목코드 (예: DNASAAPL)
    @SerialName("last") val price: String = "0",       // 현재가
    @SerialName("sign") val sign: String = "3",        // 전일대비 부호 (1상한 2상승 3보합 4하한 5하락)
    @SerialName("diff") val change: String = "0",      // 전일 대비 (abs)
    @SerialName("rate") val changeRate: String = "0",  // 등락률 (abs)
    @SerialName("open") val open: String = "0",
    @SerialName("high") val high: String = "0",
    @SerialName("low") val low: String = "0",
    @SerialName("h52p") val high52w: String = "0",     // 52주 최고가
    @SerialName("l52p") val low52w: String = "0",      // 52주 최저가
    @SerialName("tvol") val volume: String = "0",      // 거래량
    @SerialName("crcd") val currency: String = "USD",  // 통화코드
)
