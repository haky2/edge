package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 재료 1건의 구조화 판정(백엔드 `GET /catalysts/{code}` 응답의 item과 1:1).
 * 뉴스·DART 공시를 호재/악재·강도·선반영까지 카드 단위로 떨어뜨린 것. 참고용 — 매매 판단·책임은 사용자.
 */
@Serializable
data class CatalystItem(
    val source: String,                   // "공시" | "뉴스"
    val category: String,                 // 수주·공급계약/실적/유상증자·CB/자사주/배당/정책·규제/소송·제재/지분변동/정정/기타
    val title: String,
    val sentiment: String,                // "호재" | "악재" | "중립"
    val strength: String,                 // "상" | "중" | "하"
    val reason: String,                   // 한 줄 이유
    val preReflected: Boolean = false,    // 이미 주가에 반영됐을 가능성
    val preReflectedNote: String? = null, // 선반영 근거(있을 때만)
    val url: String,
    val date: String,                     // YYYYMMDD(공시) 또는 RFC822(뉴스)
)

/** 종목별 재료 종합 리포트(백엔드 `GET /catalysts/{code}` 응답과 1:1). */
@Serializable
data class CatalystReport(
    val code: String,
    val name: String,
    val date: String,                     // 생성 기준일 YYYY-MM-DD
    val generatedAt: String = "",         // 생성 시각 HH:mm(KST)
    val netBias: String,                  // "호재우위" | "악재우위" | "혼조" | "중립"
    val summary: String,                  // 1~2문장 종합
    val items: List<CatalystItem> = emptyList(),
)
