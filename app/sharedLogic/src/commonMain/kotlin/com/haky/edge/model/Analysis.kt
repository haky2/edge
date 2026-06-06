package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 종목 종합 코멘트(백엔드 `GET /analysis/{code}` 응답).
 * 사실(시세·52주·PER·수급·뉴스)을 백엔드가 모아 Claude가 해석한 결과. 참고용 — 매매 판단·책임은 사용자.
 */
@Serializable
data class Analysis(
    val code: String,
    val name: String,
    val date: String,       // 생성 기준일 YYYY-MM-DD
    val comment: String,
    val generatedAt: String = "",  // 캐시 최초 생성 시각 HH:mm (KST)
)
