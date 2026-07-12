package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnchorValidationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /anchor-validation — 기술적 앵커 룰 실증(②-3). 운영 기능 아님,
 * 1회성 관리 라우트(catalyst-validation 전례). 대조군(±2% 등락일) 대비 고유 신호 검증.
 */
fun Route.anchorValidationRoutes(service: AnchorValidationService) {
    get("/anchor-validation") {
        runCatching { service.validate() }
            .onSuccess { call.respond(it) }
            .onFailure {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("검증 실패: ${it.message}"))
            }
    }
}
