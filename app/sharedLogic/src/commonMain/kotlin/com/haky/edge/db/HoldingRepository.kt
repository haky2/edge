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
            db.accountQueries.insert("기본", 0L, 1L, AccountRepository.HORIZON_FREE)
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
     * 종목의 전 계좌 holding 행. 상세 화면 계좌 컨텍스트(배지 메뉴·계좌별 소계) 표시용.
     */
    fun byCode(code: String): List<Holding> =
        queries.selectByCode(code) { id, c, name, accountId, avgPrice, qty, targetPrice, stopPrice ->
            Holding(id, c, name, accountId, avgPrice, qty, targetPrice, stopPrice)
        }.executeAsList()

    /**
     * 관심종목 경로의 WatchItem(watchlist 기반 — G1 이후 포지션 필드는 항상 null)에 holding
     * 포지션을 얹는다. 다계좌 보유면 수량 합산·수량 가중평균 평단으로 합치고,
     * 목표/손절가는 기본 계좌 값 우선(없으면 첫 non-null). 보유가 없으면 원본 그대로.
     */
    fun hydrate(item: WatchItem): WatchItem {
        val rows = byCode(item.code)
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

    /** 계좌 삭제·보유 이전용. AccountRepository.deleteById 에서 호출. */
    fun moveToAccount(fromAccountId: Long, toAccountId: Long) =
        mergeMoveHoldings(db, fromAccountId, toAccountId)

    /** 관심종목 삭제 시 보유도 함께 제거. */
    fun removeByCode(code: String) = queries.deleteByCode(code)
}

/**
 * from 계좌의 보유 전부를 to 계좌로 이전한다. 같은 code가 to 계좌에 이미 있으면
 * 병합(수량 합·수량 가중평균 평단·목표/손절은 잔류 계좌 우선) — 단순 UPDATE는
 * UNIQUE(code, account_id) 충돌로 예외가 나므로 반드시 이 경로를 쓴다.
 * 가중평균 병합은 투자원금 합(Σ avg×qty)을 보존해 계좌별 소계 합 = 전체 정합이 유지된다.
 * AccountRepository/HoldingRepository 가 각자 EdgeDb 를 갖고 있어 공용 함수로 뺐다.
 */
internal fun mergeMoveHoldings(db: EdgeDb, fromAccountId: Long, toAccountId: Long) {
    if (fromAccountId == toAccountId) return
    val q = db.holdingQueries
    val mapper = { id: Long, code: String, name: String, accountId: Long,
                   avgPrice: Double?, qty: Long?, targetPrice: Double?, stopPrice: Double? ->
        Holding(id, code, name, accountId, avgPrice, qty, targetPrice, stopPrice)
    }
    db.transaction {
        val moving = q.selectAll(mapper).executeAsList().filter { it.accountId == fromAccountId }
        for (h in moving) {
            val existing = q.selectByCodeAndAccount(h.code, toAccountId, mapper).executeAsOneOrNull()
            val merged = if (existing == null) h else {
                val priced = listOf(existing, h).filter { (it.avgPrice ?: 0.0) > 0 && (it.qty ?: 0L) > 0 }
                val qtySum = priced.sumOf { it.qty!! }.takeIf { it > 0 }
                val avg = qtySum?.let { total -> priced.sumOf { it.avgPrice!! * it.qty!! } / total }
                existing.copy(
                    avgPrice = avg,
                    qty = if (existing.qty == null && h.qty == null) null
                          else (existing.qty ?: 0L) + (h.qty ?: 0L),
                    targetPrice = existing.targetPrice ?: h.targetPrice,
                    stopPrice = existing.stopPrice ?: h.stopPrice,
                )
            }
            q.upsert(merged.code, merged.name, toAccountId,
                     merged.avgPrice, merged.qty, merged.targetPrice, merged.stopPrice, nowMillis())
            q.deleteByCodeAndAccount(h.code, fromAccountId)
        }
    }
}
