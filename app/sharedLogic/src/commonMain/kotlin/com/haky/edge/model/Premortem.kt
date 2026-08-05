package com.haky.edge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 무효화 조건 1개. threshold: 가격 타입=원, flow_exit=연속 순매도 일수. 백엔드와 일치. */
@Serializable
data class Invalidation(
    val type: String,
    val threshold: Double? = null,
    val anchor: String? = null,
    // 프로퍼티명은 desc: Swift/ObjC의 NSObject.description(=toString)과 충돌해 iOS에서
    // inv.description이 프로퍼티 대신 객체 toString을 반환하는 버그가 있어 이름을 바꾼다.
    // JSON 와이어 포맷은 백엔드와 동일하게 "description" 유지.
    @SerialName("description") val desc: String,
    val active: Boolean = true,
    val firedAt: String? = null,
    // T2: signals-scan이 실제로 자동 평가하는 타입 여부.
    //   watched(true): price_below/above·flow_exit(가격·수급) + target_cut·event_before(목표가 하향·임박 이벤트).
    //   false: 미지 타입 등 — 저장은 되지만 자동 감시 안 됨. UI에서 "기록만" 표시.
    val evaluable: Boolean = false,
)

/** 매수 프리모템(F5) — 매수 가설이 깨지는 조건 목록. 종목당 최신 1건. */
@Serializable
data class Premortem(
    val code: String,
    val name: String,
    val createdAt: String,
    val reason: String,
    val bullCase: String = "",
    val bearCase: String = "",
    val invalidations: List<Invalidation> = emptyList(),
)

/** POST /premortem/{code} 요청 본문. */
@Serializable
data class PremortemRequest(
    val reason: String,
    val avgPrice: Double? = null,
    val qty: Long? = null,
    val stopPrice: Double? = null,
)
