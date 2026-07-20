package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * M1 카드 사용량 이벤트 — 앱이 로컬 큐에 모아 포그라운드 진입 시 배치 POST(/usage-events)로 flush.
 * screen=화면, card=펼친 카드 제목(화면 진입 view는 ""), action=view|expand, at=이벤트 시각(ISO local, KST).
 * 단일 사용자 전제(백엔드 usage_events.jsonl 헤더 참조).
 */
@Serializable
data class UsageEvent(
    val screen: String,
    val card: String,
    val action: String,
    val at: String,
)

@Serializable
data class UsageEventBatch(val events: List<UsageEvent>)

@Serializable
data class UsageAck(val accepted: Int)
