package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 관심종목 동기화 요청 — 앱이 현재 관심종목(codes)을 기기 UUID(deviceId)와 함께 백엔드에 올린다.
 * 백엔드는 기기별로 보관하고, 슬랙 신호·주간회고 스캔이 활성 기기 합집합을 대상으로 돈다.
 */
@Serializable
data class WatchlistSyncBody(val deviceId: String, val codes: List<String>)
