package com.stockapp.kis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 앱에 내려주는 정규화된 시세 DTO (한투 응답을 깔끔하게 정리한 형태). */
@Serializable
data class Quote(
    val code: String,
    val name: String,
    val price: Long,        // 현재가
    val change: Long,       // 전일 대비 (부호 적용)
    val changeRate: Double, // 등락률 %
    val volume: Long,       // 누적 거래량
    val open: Long,         // 시가
    val high: Long,         // 당일 고가
    val low: Long,          // 당일 저가
    val high52w: Long,      // 52주 최고
    val low52w: Long,       // 52주 최저
)

/** 한투 OAuth 토큰 응답. */
@Serializable
data class KisTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 86400,
    @SerialName("error_description") val errorDescription: String = "",
    @SerialName("error_code") val errorCode: String = "",
)

@Serializable
data class KisTokenRequest(
    @SerialName("grant_type") val grantType: String = "client_credentials",
    val appkey: String,
    val appsecret: String,
)

/** 한투 현재가(inquire-price) 응답. */
@Serializable
data class KisPriceResponse(
    @SerialName("rt_cd") val rtCd: String = "",
    @SerialName("msg1") val msg1: String = "",
    val output: KisPriceOutput? = null,
)

@Serializable
data class KisPriceOutput(
    @SerialName("hts_kor_isnm") val name: String = "",
    @SerialName("stck_prpr") val price: String = "0",
    @SerialName("prdy_vrss") val change: String = "0",
    @SerialName("prdy_vrss_sign") val changeSign: String = "3", // 1상한 2상승 3보합 4하한 5하락
    @SerialName("prdy_ctrt") val changeRate: String = "0",
    @SerialName("acml_vol") val volume: String = "0",
    @SerialName("stck_oprc") val open: String = "0",
    @SerialName("stck_hgpr") val high: String = "0",
    @SerialName("stck_lwpr") val low: String = "0",
    @SerialName("w52_hgpr") val high52w: String = "0",
    @SerialName("w52_lwpr") val low52w: String = "0",
)

class KisException(message: String) : RuntimeException(message)
