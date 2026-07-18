package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.PortfolioRiskService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

/** POST /portfolio-risk 바디의 보유 1건. avgPrice는 앱 빌더 재사용 편의로 받되 계산엔 안 쓴다. */
@Serializable
data class RiskPositionEntry(
    val code: String,
    val qty: Long,
    val avgPrice: Double? = null,
)

@Serializable
data class PortfolioRiskRequest(val positions: List<RiskPositionEntry> = emptyList())

fun Route.portfolioRiskRoutes(service: PortfolioRiskService) {
    // POST /portfolio-risk — 실측 상관 기반 리스크 스냅샷(LLM 0). 다계좌 같은 종목은 앱이 수량 합산 후 전송.
    post("/portfolio-risk") {
        val req = call.receive<PortfolioRiskRequest>()
        val rawCodes = req.positions.map { it.code.trim() }
        if (rawCodes.size != rawCodes.distinct().size) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("중복된 종목 코드가 있습니다"))
            return@post
        }
        val positions = req.positions.mapNotNull { e ->
            val code = e.code.trim().takeIf { CODE_REGEX.matches(it) } ?: return@mapNotNull null
            if (e.qty <= 0) return@mapNotNull null
            code to e.qty
        }.toMap()
        if (positions.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("positions가 비어 있습니다"))
            return@post
        }
        runCatching { service.analyze(positions) }
            .onSuccess { call.respond(it) }
            .onFailure { e ->
                if (e is IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "리스크 계산 불가"))
                } else throw e
            }
    }
}
