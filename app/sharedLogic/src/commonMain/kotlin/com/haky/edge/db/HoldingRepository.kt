package com.haky.edge.db

import com.haky.edge.model.Holding
import com.haky.edge.model.WatchItem

class HoldingRepository(driverFactory: DriverFactory) {
    private val db = EdgeDb(driverFactory.createDriver())
    private val queries = db.holdingQueries

    /**
     * 기본 계좌 id — account 테이블에서 해석하고, 없으면 여기서 자가 시드한다.
     * 프레시 설치는 migration(4.sqm)이 돌지 않아 '기본' 계좌가 없고, 그 상태에서 커스텀 계좌를
     * 먼저 만들면 id=1을 커스텀이 차지하므로 **id=1 상수 가정은 금지** — 항상 이 함수로 해석.
     */
    fun defaultAccountId(): Long =
        db.accountQueries.defaultId().executeAsOneOrNull() ?: run {
            db.accountQueries.insert("기본", 0L, 1L)
            db.accountQueries.defaultId().executeAsOne()
        }

    /** 전 계좌 보유 목록. PortfolioView 전체 집계용. */
    fun all(): List<Holding> =
        queries.selectAll { id, code, name, accountId, avgPrice, qty, targetPrice, stopPrice ->
            Holding(id, code, name, accountId, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsList()

    /** 특정 종목의 기본 계좌 포지션. PositionEditView 초기값 로드·StockDetailView 포지션 표시용. */
    fun getDefaultHolding(code: String): Holding? = getHolding(code, defaultAccountId())

    /**
     * 관심종목 경로의 WatchItem(watchlist 기반 — G1 이후 포지션 필드는 항상 null)에 holding
     * 포지션을 얹는다. 다계좌 보유면 수량 합산·수량 가중평균 평단으로 합치고,
     * 목표/손절가는 기본 계좌 값 우선(없으면 첫 non-null). 보유가 없으면 원본 그대로.
     */
    fun hydrate(item: WatchItem): WatchItem {
        val rows = queries.selectByCode(item.code) { id, c, name, accountId, avgPrice, qty, targetPrice, stopPrice ->
            Holding(id, c, name, accountId, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsList()
        if (rows.isEmpty()) return item

        val priced = rows.filter { (it.avgPrice ?: 0.0) > 0 && (it.qty ?: 0L) > 0 }
        val qtySum = priced.sumOf { it.qty!! }.takeIf { it > 0 }
        val avg = qtySum?.let { total -> priced.sumOf { it.avgPrice!! * it.qty!! } / total }

        val defId = defaultAccountId()
        val ordered = rows.sortedBy { if (it.accountId == defId) 0 else 1 }
        return item.copy(
            avgPrice = avg,
            qty = qtySum,
            targetPrice = ordered.firstNotNullOfOrNull { it.targetPrice },
            stopPrice = ordered.firstNotNullOfOrNull { it.stopPrice },
        )
    }

    /**
     * 기본 계좌 포지션 저장(upsert). 네 필드가 전부 null이면 행을 삭제해 "포지션 없음"으로
     * 되돌린다 — 평단 없이 목표/손절만 등록하는 사용(pre-G1부터 지원)도 행으로 유지해야 한다.
     */
    fun savePosition(
        code: String,
        name: String,
        avgPrice: Double?,
        qty: Long?,
        targetPrice: Double?,
        stopPrice: Double?,
    ) = savePositionForAccount(code, name, defaultAccountId(), avgPrice, qty, targetPrice, stopPrice)

    /** 특정 계좌의 포지션 조회. 계좌 피커가 있는 PositionEditView에서 계좌 전환 시 사용. */
    fun getHolding(code: String, accountId: Long): Holding? =
        queries.selectByCodeAndAccount(code, accountId) { id, c, name, accId, avgPrice, qty, targetPrice, stopPrice ->
            Holding(id, c, name, accId, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsOneOrNull()

    /** 특정 계좌에 포지션 저장(upsert). 네 필드 전부 null이면 해당 계좌 행을 삭제. */
    fun savePositionForAccount(
        code: String,
        name: String,
        accountId: Long,
        avgPrice: Double?,
        qty: Long?,
        targetPrice: Double?,
        stopPrice: Double?,
    ) {
        if (avgPrice == null && qty == null && targetPrice == null && stopPrice == null) {
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
}
