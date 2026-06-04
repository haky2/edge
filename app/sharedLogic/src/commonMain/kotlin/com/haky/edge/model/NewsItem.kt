package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 뉴스 1건(백엔드 `GET /news` 응답과 1:1). 제목·요약·출처·URL·발행일. */
@Serializable
data class NewsItem(
    val title: String,
    val description: String,
    val source: String,       // 언론사 도메인 (예: hankyung.com)
    val url: String,
    val publishedAt: String,  // RFC 822 형식 그대로
)
