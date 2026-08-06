package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.ThesisSnapshot
import com.haky.edge.thesis.SyncedThesis
import com.haky.edge.thesis.ThesisRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * POST /thesis/sync — 앱(기기)이 현재 투자 논지를 백엔드에 등록한다.
 *
 * signals-scan이 이 등록분(활성 기기)을 대상으로 논지 파손을 push한다(pull→push).
 * watchlist sync와 동일한 무계정·deviceId 모델. 길이 가드는 /analysis와 같은 상수 재사용.
 */
fun Route.thesisSyncRoutes(registry: ThesisRegistry) {
    post("/thesis/sync") {
        val req = call.receive<ThesisSyncRequest>()
        if (req.theses.size > MAX_CODES) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("논지는 최대 ${MAX_CODES}종목까지만 sync할 수 있습니다"))
            return@post
        }
        val bad = req.theses.firstOrNull {
            it.thesis.length > AnalysisService.THESIS_MAX_CHARS ||
                it.thesisHistory.size > AnalysisService.THESIS_HISTORY_MAX ||
                it.thesisHistory.any { s -> s.t.length > AnalysisService.THESIS_MAX_CHARS }
        }
        if (bad != null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("논지는 ${AnalysisService.THESIS_MAX_CHARS}자·이력 ${AnalysisService.THESIS_HISTORY_MAX}개 이내여야 합니다 (${bad.code})"))
            return@post
        }
        val now = java.time.Instant.now().toString()
        val map = req.theses
            .filter { it.code.isNotBlank() && it.thesis.isNotBlank() }
            .associate { it.code.trim() to SyncedThesis(it.thesis.trim(), it.thesisHistory, now) }
        registry.sync(req.deviceId, map)
        call.respond(ThesisSyncResponse(ok = true, count = map.size))
    }
}

private const val MAX_CODES = 100

@Serializable
data class ThesisSyncRequest(val deviceId: String, val theses: List<ThesisSyncItem>)

@Serializable
data class ThesisSyncItem(val code: String, val thesis: String, val thesisHistory: List<ThesisSnapshot> = emptyList())

@Serializable
data class ThesisSyncResponse(val ok: Boolean, val count: Int)
