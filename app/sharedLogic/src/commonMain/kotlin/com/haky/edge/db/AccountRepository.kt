package com.haky.edge.db

import com.haky.edge.model.AccountInfo

class AccountRepository(driverFactory: DriverFactory) {
    private val db = EdgeDb(driverFactory.createDriver())
    private val q = db.accountQueries
    private val hq = db.holdingQueries

    companion object {
        const val HORIZON_LONG = "long"
        const val HORIZON_FREE = "free"

        // 프리셋 중 장기 성격 계좌. 커스텀 이름은 추론하지 않는다(항상 free 시작, 관리 UI에서 변경).
        private val LONG_TERM_PRESETS = setOf("ISA", "IRP개인연금", "퇴직연금")

        /** 프리셋 버튼으로 추가할 때의 기본 horizon. iOS·Android 관리 UI가 공유. */
        fun presetHorizon(presetName: String): String =
            if (presetName in LONG_TERM_PRESETS) HORIZON_LONG else HORIZON_FREE
    }

    fun all(): List<AccountInfo> =
        q.selectAll { id, name, sortOrder, isDefault, horizon ->
            AccountInfo(id, name, sortOrder, isDefault, horizon)
        }.executeAsList()

    fun countCustom(): Long = q.countCustom().executeAsOne()

    /** 기본 계좌 id. 프레시 설치엔 migration이 안 돌아 '기본'이 없으므로 자가 시드(HoldingRepository와 동일). */
    fun defaultId(): Long =
        q.defaultId().executeAsOneOrNull() ?: run {
            q.insert("기본", 0L, 1L, HORIZON_FREE)
            q.defaultId().executeAsOne()
        }

    /**
     * 새 계좌를 추가하고 삽입된 AccountInfo를 반환한다.
     * AUTOINCREMENT 보장이므로 삽입 후 all()에서 가장 큰 id가 신규 계좌다.
     */
    fun insertAndGet(name: String, horizon: String = HORIZON_FREE): AccountInfo {
        val nextOrder = (all().maxOfOrNull { it.sortOrder } ?: 0L) + 1L
        q.insert(name, nextOrder, 0L, horizon)
        return all().maxByOrNull { it.id }!!
    }

    /** 계좌 성격(장기/자유) 변경. 관리 UI 스위치용. */
    fun updateHorizon(id: Long, horizon: String) = q.updateHorizon(horizon, id)

    /**
     * 계좌를 삭제한다. 삭제 전 해당 계좌의 보유를 기본 계좌로 이전한다(같은 종목은 병합).
     * 기본 계좌(is_default=1)는 삭제하지 않는다.
     */
    fun deleteById(id: Long) {
        val defId = defaultId()
        if (id == defId) return
        mergeMoveHoldings(db, fromAccountId = id, toAccountId = defId)
        q.deleteById(id)
    }

    /** 기본 계좌의 보유 종목 수. 첫 계좌 생성 시 이전 제안 UI용. */
    fun countInDefault(): Long = hq.countInAccount(defaultId()).executeAsOne()
}
