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

    /** sort_order 순으로 전체 반환(화면 리스트용). 포지션 및 논지 필드 포함. */
    fun all(): List<WatchItem> =
        queries.selectAll { code, name, avgPrice, qty, targetPrice, stopPrice, thesis ->
            WatchItem(code, name, avgPrice, qty, targetPrice, stopPrice, thesis)
        }.executeAsList()

    // 포지션 쓰기(updatePosition)는 G1에서 HoldingRepository.savePosition*으로 이관·제거됨.
    // watchlist의 포지션 컬럼은 G1 마이그레이션 이후 항상 NULL — 표시는 HoldingRepository.hydrate가 얹는다.

    /**
     * 관심종목 추가(검색 1.4b 연동용). 새 코드는 끝에 붙이고, 기존 코드는 이름만 갱신해
     * 행을 보존한다 — insert가 OR REPLACE라 기존 행에 쓰면 thesis가 NULL로 지워진다.
     */
    fun add(code: String, name: String) {
        if (queries.existsByCode(code).executeAsOne() > 0L) {
            queries.updateName(name, code)
            return
        }
        queries.insert(code, name, queries.count().executeAsOne(), nowMillis())
    }

    /**
     * 투자 논지 저장. 빈 문자열은 null(논지 없음). 관심목록에 행이 없으면(관심 해제 후
     * 보유만 남은 종목) 행을 만들어 저장한다 — 없는 행에 UPDATE만 하면 조용히 유실된다.
     *
     * C16 드리프트: 논지가 실제로 바뀌면 thesis_history에 스냅샷 append.
     * 첫 변경 시 히스토리가 비어 있으면 기존 논지를 먼저 백필해 "이전→현재" 쌍을 보존한다
     * (기존 사용자의 첫 논지는 기록 시점을 몰라 오늘 날짜로 접힘 — 순서는 id로 보존).
     * 삭제(null)는 스냅샷이 아니다(텍스트 비교 불가 상태는 기록 가치 없음).
     */
    fun updateThesis(code: String, name: String, thesis: String?) {
        val t = thesis?.trim()?.ifBlank { null }
        val old = if (queries.existsByCode(code).executeAsOne() == 0L) {
            if (t == null) return
            queries.insert(code, name, queries.count().executeAsOne(), nowMillis())
            null
        } else {
            queries.thesisByCode(code).executeAsOneOrNull()?.thesis
        }
        queries.updateThesis(t, code)
        if (t != null && t != old) {
            val history = db.thesisHistoryQueries
            if (old != null && history.countByCode(code).executeAsOne() == 0L) {
                history.insertSnapshot(code, old, todayIso())
            }
            history.insertSnapshot(code, t, todayIso())
        }
    }

    /**
     * 논지 변경 이력(오래된 순, 최근 limit개). 분석 요청 시 thesisHistory 파라미터로 보낸다 —
     * 2건 미만이면 변천이 없어 백엔드가 무시하므로 호출부에서 그대로 전달해도 된다.
     */
    fun thesisHistory(code: String, limit: Long = 5): List<com.haky.edge.model.ThesisSnapshot> =
        db.thesisHistoryQueries.recentByCode(code, limit) { thesis, changedOn ->
            com.haky.edge.model.ThesisSnapshot(d = changedOn, t = thesis)
        }.executeAsList().reversed()

    /** 이번 주(date 이후) 논지 변경 전체. B2 개인 주간 회고 POST에 사용. date = "YYYY-MM-DD". */
    fun thesisChangesSince(date: String): List<com.haky.edge.model.WeeklyThesisChangeEntry> =
        db.thesisHistoryQueries.sinceDateAll(date) { code, thesis, changedOn ->
            com.haky.edge.model.WeeklyThesisChangeEntry(code, thesis, changedOn)
        }.executeAsList()

    /** 관심종목 삭제. */
    fun remove(code: String) = queries.deleteByCode(code)
}
