package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.DiscoveryValidationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /discovery-validation — 후보 발굴 가격 3신호 실증(②-2b). 운영 기능 아님,
 * 1회성 관리 라우트(anchor-validation 전례). peer 유니버스 750봉 × 코스피 초과수익 채점.
 */
fun Route.discoveryValidationRoutes(service: DiscoveryValidationService) {
    get("/discovery-validation") {
        runCatching { service.validate() }
            .onSuccess { call.respond(it) }
            .onFailure {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("검증 실패: ${it.message}"))
            }
    }
}
