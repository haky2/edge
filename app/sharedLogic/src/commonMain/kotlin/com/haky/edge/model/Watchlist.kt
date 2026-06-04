package com.haky.edge.model

import kotlinx.serialization.Serializable

/**
 * 관심종목 1건. /quote 응답엔 이름이 없어 이름은 여기서 들고 있다가 화면에서 합친다.
 * 1.5: 내 포지션 필드(평단가·수량·목표가·손절가)는 입력 전이면 null. 시드/검색추가 땐 code·name만 채운다.
 */
@Serializable
data class WatchItem(
    val code: String,
    val name: String,
    val avgPrice: Double? = null,
    val qty: Long? = null,
    val targetPrice: Double? = null,
    val stopPrice: Double? = null,
)

/**
 * 기본 관심종목 시드 (CLAUDE.md 기준, 2026-06-03). 코드는 /search 로 검증함.
 * 1.3b부터 정본은 SQLDelight DB(watchlist 테이블). 이 목록은 **첫 실행 시 시드값**으로만 쓰인다
 * (WatchlistRepository.ensureSeeded). 화면은 DB에서 읽는다.
 */
object Watchlist {
    val defaultItems: List<WatchItem> = listOf(
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
