package com.haky.edge.db

import com.haky.edge.model.ActionLogEntry

/** 행동 로그 영속화. WatchlistRepository 와 동일 패턴(각자 드라이버 소유). */
class ActionLogRepository(driverFactory: DriverFactory) {
    private val db = EdgeDb(driverFactory.createDriver())
    private val queries = db.actionLogQueries

    /**
     * 로그 저장. price = 기록 시점 현재가(원). 0 이하이면 null로 저장(미기록).
     * Swift: Long(비nullable)으로 받아 0을 "없음" 센티널로 사용 → boxing 불필요.
     */
    fun insert(code: String, name: String? = null, action: String, reason: String?, price: Long = 0L) {
        queries.insert(
            code = code,
            name = name.takeIf { !it.isNullOrBlank() },
            action = action,
            reason = reason.takeIf { !it.isNullOrBlank() },
            price = price.takeIf { it > 0L },
            created_at = nowMillis(),
        )
    }

    /** 해당 종목의 최근 기록(최신 순, 최대 limit 건). */
    fun getByCode(code: String, limit: Int = 10): List<ActionLogEntry> =
        queries.selectByCode(code) { id, c, name, action, reason, price, createdAt ->
            ActionLogEntry(id, c, name, action, reason, price, createdAt)
        }.executeAsList().take(limit)

    /** 전체 로그(최신 순). 통계·패턴 분석용. */
    fun getAll(): List<ActionLogEntry> =
        queries.selectAll { id, c, name, action, reason, price, createdAt ->
            ActionLogEntry(id, c, name, action, reason, price, createdAt)
        }.executeAsList()

    fun delete(id: Long) {
        queries.deleteById(id)
    }
}
