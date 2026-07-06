package com.haky.edge.db

import com.haky.edge.model.Holding

class HoldingRepository(driverFactory: DriverFactory) {
    private val db = EdgeDb(driverFactory.createDriver())
    private val queries = db.holdingQueries

    /** 전 계좌 보유 목록. PortfolioView 전체 집계용. */
    fun all(): List<Holding> =
        queries.selectAll { id, code, name, accountId, avgPrice, qty, targetPrice, stopPrice ->
            Holding(id, code, name, accountId, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsList()

    /** 특정 종목의 기본 계좌 포지션. PositionEditView 초기값 로드·StockDetailView 포지션 표시용. */
    fun getDefaultHolding(code: String): Holding? =
        queries.selectByCodeAndAccount(code, DEFAULT_ACCOUNT_ID) { id, c, name, accountId, avgPrice, qty, targetPrice, stopPrice ->
            Holding(id, c, name, accountId, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsOneOrNull()

    /**
     * 기본 계좌 포지션 저장(upsert). avgPrice/qty 가 모두 null 이면 holding 행을 삭제해
     * "포지션 없음" 상태로 되돌린다 — "미입력으로 되돌리기" 지원.
     */
    fun savePosition(
        code: String,
        name: String,
        avgPrice: Double?,
        qty: Long?,
        targetPrice: Double?,
        stopPrice: Double?,
    ) {
        if (avgPrice == null && qty == null) {
            queries.deleteByCodeAndAccount(code, DEFAULT_ACCOUNT_ID)
        } else {
            queries.upsert(code, name, DEFAULT_ACCOUNT_ID, avgPrice, qty, targetPrice, stopPrice, nowMillis())
        }
    }

    /** 특정 계좌의 포지션 조회. 계좌 피커가 있는 PositionEditView에서 계좌 전환 시 사용. */
    fun getHolding(code: String, accountId: Long): Holding? =
        queries.selectByCodeAndAccount(code, accountId) { id, c, name, accId, avgPrice, qty, targetPrice, stopPrice ->
            Holding(id, c, name, accId, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsOneOrNull()

    /** 특정 계좌에 포지션 저장(upsert). avgPrice/qty 가 모두 null 이면 해당 계좌 행을 삭제. */
    fun savePositionForAccount(
        code: String,
        name: String,
        accountId: Long,
        avgPrice: Double?,
        qty: Long?,
        targetPrice: Double?,
        stopPrice: Double?,
    ) {
        if (avgPrice == null && qty == null) {
            queries.deleteByCodeAndAccount(code, accountId)
        } else {
            queries.upsert(code, name, accountId, avgPrice, qty, targetPrice, stopPrice, nowMillis())
        }
    }

    /** 계좌 삭제 시 보유 이전용. AccountRepository.deleteById 에서 호출. */
    fun moveToAccount(fromAccountId: Long, toAccountId: Long) =
        queries.moveToAccount(newAccountId = toAccountId, oldAccountId = fromAccountId)

    /** 관심종목 삭제 시 보유도 함께 제거. */
    fun removeByCode(code: String) = queries.deleteByCode(code)

    companion object {
        const val DEFAULT_ACCOUNT_ID = 1L
    }
}
