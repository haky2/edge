package com.haky.edge.ui

import android.content.Context
import com.haky.edge.api.EdgeApi
import com.haky.edge.db.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 관심종목을 백엔드에 동기화 — 슬랙 신호·주간회고 스캔 대상(기기별 등록, 활성 합집합).
 * 관심종목 화면 로드 때 호출(진입·추가·삭제 후 모두 여기로 돌아옴). 직전과 같으면 skip(중복 POST 방지).
 * 국내 6자리 코드만 전송(백엔드 신호 스캔은 국내 전용, 해외 US: 접두 제외).
 */
object WatchlistSync {
    @Volatile private var lastSynced: Set<String>? = null

    /** 기기 UUID — 최초 1회 생성해 로컬 보관(계정/로그인 없음). */
    private fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("edge_device", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    @Volatile private var lastThesisSig: String? = null

    fun push(ctx: Context, api: EdgeApi, scope: CoroutineScope, repo: WatchlistRepository, codes: List<String>) {
        val id = deviceId(ctx)
        val domestic = codes.filter { !it.startsWith("US:") }
        val set = domestic.toSet()
        if (set != lastSynced) {          // 변화 없으면 skip
            lastSynced = set
            scope.launch { runCatching { api.syncWatchlist(id, domestic) } }
        }
        // 논지도 함께 동기화(pull→push 재점검 대상). 국내만, 직전과 같으면 skip.
        val theses = repo.allTheses(5).filter { !it.code.startsWith("US:") }
        val sig = theses.joinToString("|") { "${it.code}:${it.thesis}" }
        if (sig != lastThesisSig) {
            lastThesisSig = sig
            scope.launch { runCatching { api.syncThesis(id, theses) } }
        }
    }
}
