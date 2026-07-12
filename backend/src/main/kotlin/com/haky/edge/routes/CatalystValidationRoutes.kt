package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.CatalystValidationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /catalyst-validation — catalyst 판정 실증(②-1). 운영 기능 아님,
 * 1회성 관리 라우트(sensitivity-validation 전례). 초과수익률(vs 코스피) 기준 채점.
 */
fun Route.catalystValidationRoutes(service: CatalystValidationService) {
    get("/catalyst-validation") {
        runCatching { service.validate() }
            .onSuccess { call.respond(it) }
            .onFailure {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("검증 실패: ${it.message}"))
            }
    }
}
