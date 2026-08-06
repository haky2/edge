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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * POST /thesis/sync — 앱(기기)이 현재 투자 논지를 백엔드에 등록한다.
 *
 * signals-scan이 이 등록분(활성 기기)을 대상으로 논지 파손을 push한다(pull→push).
 * watchlist sync와 동일한 무계정·deviceId 모델. 길이 가드는 /analysis와 같은 상수 재사용.
 */
fun Route.thesisSyncRoutes(
    registry: ThesisRegistry,
    recheck: com.haky.edge.ai.ThesisRecheckService? = null,
    master: com.haky.edge.master.StockMaster? = null,
) {
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

    // GET /thesis/recheck-test?code=&thesis=&change= — 수동 프롬프트 검증(운영 기능 아님).
    // signals-scan 게이트 뒤의 재점검을 물질적 신호 없이 직접 호출해 본다(실 LLM 1콜 발생 주의).
    // 프롬프트 튜닝·오탐 관찰 기간의 재현 도구 — websearch-test 전례.
    get("/thesis/recheck-test") {
        if (recheck == null || master == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("recheck 미배선"))
            return@get
        }
        val code = call.request.queryParameters["code"]?.trim().orEmpty()
        val thesisText = call.request.queryParameters["thesis"]?.trim().orEmpty()
        val change = call.request.queryParameters["change"]?.trim()
            ?.ifBlank { null } ?: "수동 테스트(물질적 변화 없음)"
        if (code.isBlank() || thesisText.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("code·thesis 필수"))
            return@get
        }
        if (thesisText.length > AnalysisService.THESIS_MAX_CHARS) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("논지는 ${AnalysisService.THESIS_MAX_CHARS}자 이내"))
            return@get
        }
        val name = runCatching { master.findByCode(code)?.name }.getOrNull() ?: code
        val synced = SyncedThesis(thesisText, emptyList(), java.time.Instant.now().toString())
        val verdict = recheck.recheck(code, name, synced, change)
        if (verdict == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("판정 파싱 실패"))
            return@get
        }
        call.respond(RecheckTestResponse(code, name, change, verdict.verdict, verdict.changedFact, verdict.reason))
    }
}

@Serializable
data class RecheckTestResponse(
    val code: String, val name: String, val change: String,
    val verdict: String, val changedFact: String, val reason: String,
)

private const val MAX_CODES = 100

@Serializable
data class ThesisSyncRequest(val deviceId: String, val theses: List<ThesisSyncItem>)

@Serializable
data class ThesisSyncItem(val code: String, val thesis: String, val thesisHistory: List<ThesisSnapshot> = emptyList())

@Serializable
data class ThesisSyncResponse(val ok: Boolean, val count: Int)
