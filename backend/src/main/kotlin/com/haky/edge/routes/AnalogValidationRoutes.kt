package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalogValidationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /analog-validation — 유사 국면 카드 캘리브레이션 실증(②-2a). 운영 기능 아님,
 * 1회성 관리 라우트(anchor-validation 전례). walk-forward replay로 예측 분포 vs 실현 채점.
 */
fun Route.analogValidationRoutes(service: AnalogValidationService) {
    get("/analog-validation") {
        runCatching { service.validate() }
            .onSuccess { call.respond(it) }
            .onFailure {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("검증 실패: ${it.message}"))
            }
    }
}
