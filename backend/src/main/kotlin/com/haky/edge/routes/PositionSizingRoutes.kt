package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.PositionSizingService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

/** POST /position-sizing 바디의 기존 보유 1건. avgPrice는 앱 빌더 재사용 편의로 받되 계산엔 안 쓴다. */
@Serializable
data class SizingPositionEntry(
    val code: String,
    val qty: Long,
    val avgPrice: Double? = null,
)

@Serializable
data class PositionSizingRequest(
    val positions: List<SizingPositionEntry> = emptyList(),
    val candidateCode: String = "",
    val riskCapPct: Double = PositionSizingService.DEFAULT_CAP,
)

fun Route.positionSizingRoutes(service: PositionSizingService) {
    // POST /position-sizing — 리스크 기여 상한 역산(LLM 0). 다계좌 같은 종목은 앱이 수량 합산 후 전송.
    post("/position-sizing") {
        val req = call.receive<PositionSizingRequest>()
        val candidate = req.candidateCode.trim().uppercase()
        if (!CODE_REGEX.matches(candidate)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("candidateCode가 올바르지 않습니다"))
            return@post
        }
        val rawCodes = req.positions.map { it.code.trim() }
        if (rawCodes.size != rawCodes.distinct().size) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("중복된 종목 코드가 있습니다"))
            return@post
        }
        val positions = req.positions.mapNotNull { e ->
            val code = e.code.trim().uppercase().takeIf { CODE_REGEX.matches(it) } ?: return@mapNotNull null
            if (e.qty <= 0) return@mapNotNull null
            code to e.qty
        }.toMap()
        if (positions.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("보유 포지션이 비어 있습니다(리스크 기준 역산은 기존 포트폴리오가 필요)"))
            return@post
        }
        runCatching { service.size(positions, candidate, req.riskCapPct) }
            .onSuccess { call.respond(it) }
            .onFailure { e ->
                if (e is IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "사이징 계산 불가"))
                } else throw e
            }
    }
}
