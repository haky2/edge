package com.haky.edge.db

import com.haky.edge.model.ActionLogEntry

/** 행동 로그 영속화. WatchlistRepository 와 동일 패턴(각자 드라이버 소유). */
class ActionLogRepository(driverFactory: DriverFactory) {
    private val db = EdgeDb(driverFactory.createDriver())
    private val queries = db.actionLogQueries

    fun insert(code: String, action: String, reason: String?) {
        queries.insert(code, action, reason.takeIf { !it.isNullOrBlank() }, nowMillis())
    }

    /** 해당 종목의 최근 기록(최신 순, 최대 limit 건). */
    fun getByCode(code: String, limit: Int = 10): List<ActionLogEntry> =
        queries.selectByCode(code) { id, c, action, reason, createdAt ->
            ActionLogEntry(id, c, action, reason, createdAt)
        }.executeAsList().take(limit)
}
