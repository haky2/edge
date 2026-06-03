package com.haky.edge.model

import kotlinx.serialization.Serializable

/** 관심종목 1건(코드 + 이름). /quote 응답엔 이름이 없어, 이름은 여기서 들고 있다가 화면에서 합친다. */
@Serializable
data class WatchItem(val code: String, val name: String)

/**
 * 기본 관심종목 (CLAUDE.md 기준, 2026-06-03). 코드는 /search 로 검증함.
 * 지금은 하드코딩 — 다음 단계(1.3b)에서 SQLDelight 로컬 DB로 옮긴다.
 */
object Watchlist {
    val items: List<WatchItem> = listOf(
        WatchItem("018260", "삼성에스디에스"),
        WatchItem("329180", "HD현대중공업"),
        WatchItem("066570", "LG전자"),
        WatchItem("307950", "현대오토에버"),
        WatchItem("000660", "SK하이닉스"),
        WatchItem("005930", "삼성전자"),
        WatchItem("267260", "HD현대일렉트릭"),
        WatchItem("001440", "대한전선"),
        WatchItem("062040", "산일전기"),
        WatchItem("047810", "한국항공우주"),
        WatchItem("012450", "한화에어로스페이스"),
    )
}
