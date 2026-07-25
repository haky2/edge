package com.haky.edge.routes

import com.haky.edge.watchlist.WatchlistRegistry
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * POST /watchlist/sync — 앱(기기)이 현재 관심종목을 백엔드에 등록한다.
 *
 * 스케줄 신호·주간회고 스캔이 이 등록분(활성 기기 합집합)을 대상으로 돈다.
 * 계정 없음 — deviceId(앱이 최초 실행 시 생성해 로컬 보관하는 UUID)로만 구분.
 */
fun Route.watchlistRoutes(registry: WatchlistRegistry) {
    post("/watchlist/sync") {
        val req = call.receive<WatchlistSyncRequest>()
        registry.sync(req.deviceId, req.codes)
        call.respond(WatchlistSyncResponse(ok = true, count = req.codes.distinct().size))
    }
}

@Serializable
data class WatchlistSyncRequest(val deviceId: String, val codes: List<String>)

@Serializable
data class WatchlistSyncResponse(val ok: Boolean, val count: Int)
