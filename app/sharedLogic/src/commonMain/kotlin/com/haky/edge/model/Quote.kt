package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 백엔드 `/quote/{code}` 응답 모델. 백엔드의 Quote DTO와 1:1로 맞춘다.
 * (백엔드: backend/.../kis/KisModels.kt 의 Quote)
 *
 * 종목명은 여기 없다 — 한투 현재가 API엔 이름이 없어, 이름은 검색(/search)에서 얻는다.
 */
@Serializable
data class Quote(
    val code: String,
    val price: Long,        // 현재가
    val change: Long,       // 전일 대비 (부호 포함)
    val changeRate: Double, // 등락률 %
    val volume: Long,       // 누적 거래량
    val open: Long,         // 시가
    val high: Long,         // 당일 고가
    val low: Long,          // 당일 저가
    val high52w: Long,      // 52주 최고
    val low52w: Long,       // 52주 최저
    val per: Double = 0.0,       // 주가수익비율 (0/음수면 적자 등으로 산정 불가)
    val pbr: Double = 0.0,       // 주가순자산비율
    val sectorName: String = "", // 업종명 (e.g. "전기·전자"), 백엔드가 bstp_kor_isnm 에서 채움
)
