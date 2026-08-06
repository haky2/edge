package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 관심종목 동기화 요청 — 앱이 현재 관심종목(codes)을 기기 UUID(deviceId)와 함께 백엔드에 올린다.
 * 백엔드는 기기별로 보관하고, 슬랙 신호·주간회고 스캔이 활성 기기 합집합을 대상으로 돈다.
 */
@Serializable
data class WatchlistSyncBody(val deviceId: String, val codes: List<String>)

/**
 * 논지 동기화 요청 — 앱이 기록한 투자 논지(+최근 이력)를 백엔드에 올린다(pull→push).
 * signals-scan이 물질적 사건 뒤 이 논지를 재점검해 약화/무효면 Slack으로 알린다.
 * 논지 정본은 앱 로컬 DB, 이건 서버 사본.
 */
@Serializable
data class ThesisSyncItemBody(val code: String, val thesis: String, val thesisHistory: List<ThesisSnapshot> = emptyList())

@Serializable
data class ThesisSyncBody(val deviceId: String, val theses: List<ThesisSyncItemBody>)
