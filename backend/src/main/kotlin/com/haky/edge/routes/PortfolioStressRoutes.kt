package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.PortfolioStressService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

private val STRESS_CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.portfolioStressRoutes(service: PortfolioStressService) {
    // POST /portfolio-stress — 코스피 등락 시나리오 조건부 손익(LLM 0). /portfolio-risk와 같은 바디.
    // 다계좌 같은 종목은 앱이 수량 합산 후 전송.
    post("/portfolio-stress") {
        val req = call.receive<PortfolioRiskRequest>()
        val rawCodes = req.positions.map { it.code.trim() }
        if (rawCodes.size != rawCodes.distinct().size) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("중복된 종목 코드가 있습니다"))
            return@post
        }
        val positions = req.positions.mapNotNull { e ->
            val code = e.code.trim().takeIf { STRESS_CODE_REGEX.matches(it) } ?: return@mapNotNull null
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "스트레스 계산 불가"))
                } else throw e
            }
    }
}
