package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.macro.SensitivityValidationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /sensitivity-validation — SENSITIVITY 테이블 실측 검증(D1). 운영 기능 아님,
 * 로컬에서 1회성 실행하는 관리 라우트(websearch-test 전례). 결과는 콘솔에도 출력.
 */
fun Route.sensitivityValidationRoutes(service: SensitivityValidationService) {
    get("/sensitivity-validation") {
        runCatching { service.validate() }
            .onSuccess { call.respond(it) }
            .onFailure {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("검증 실패: ${it.message}"))
            }
    }
}
