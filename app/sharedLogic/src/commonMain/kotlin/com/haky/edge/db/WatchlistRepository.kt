package com.haky.edge.db

import com.haky.edge.model.WatchItem
import com.haky.edge.model.Watchlist

/**
 * 관심종목 로컬 영속화(SQLDelight). 화면(iOS/Android)은 하드코딩 대신 이 리포지토리에서 읽는다.
 *
 * 생성: 플랫폼에서 DriverFactory를 넘긴다(iOS=Swift, Android=MainActivity).
 * 첫 실행이면 ensureSeeded()로 기본 11종목을 채우고, 이후엔 DB가 정본.
 */
class WatchlistRepository(driverFactory: DriverFactory) {
    private val db = EdgeDb(driverFactory.createDriver())
    private val queries = db.watchlistQueries

    /** 비어 있을 때만 기본 관심종목(CLAUDE.md 우선순위순)을 시드. 그 외엔 손대지 않는다. */
    fun ensureSeeded() {
        if (queries.count().executeAsOne() > 0L) return
        val now = nowMillis()
        Watchlist.defaultItems.forEachIndexed { i, item ->
            queries.insert(item.code, item.name, i.toLong(), now)
        }
    }

    /** sort_order 순으로 전체 반환(화면 리스트용). 포지션 필드 포함. */
    fun all(): List<WatchItem> =
        queries.selectAll { code, name, avgPrice, qty, targetPrice, stopPrice ->
            WatchItem(code, name, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsList()

    /**
     * 내 포지션(평단가·수량·목표가·손절가) 저장. 각 값은 null이면 미입력으로 비운다.
     * 상세 화면의 입력 UI(1.5b)가 호출.
     */
    fun updatePosition(
        code: String,
        avgPrice: Double?,
        qty: Long?,
        targetPrice: Double?,
        stopPrice: Double?,
    ) = queries.updatePosition(avgPrice, qty, targetPrice, stopPrice, code)

    /** 관심종목 추가(검색 1.4b 연동용). 기존 코드면 이름/순서 갱신. 끝에 붙인다. */
    fun add(code: String, name: String) {
        val order = queries.count().executeAsOne()
        queries.insert(code, name, order, nowMillis())
    }

    /** 관심종목 삭제. */
    fun remove(code: String) = queries.deleteByCode(code)
}
