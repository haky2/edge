package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 국내(KRX) 개장 캘린더(백엔드 `GET /market-calendar` 응답). 토스 Open API 기반(한투 미제공).
 * 오늘 휴장 여부 + 직전/다음 거래일. 브리핑 "휴장/다음 거래일" 배너용.
 */
@Serializable
data class MarketCalendar(
    val date: String,                  // 오늘 (yyyy-MM-dd, KST)
    val isHoliday: Boolean,            // 오늘 정규장 휴장 여부
    val regularStart: String? = null,  // "09:00" (휴장이면 null)
    val regularEnd: String? = null,    // "15:30"
    val previousBusinessDay: String = "", // 직전 거래일
    val nextBusinessDay: String = "",     // 다음 거래일
)
