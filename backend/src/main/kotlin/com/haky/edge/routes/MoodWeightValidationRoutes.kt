package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.macro.MoodWeightValidationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /moodweight-validation — MoodLog 방향예측 가중치 실측(③). 운영 기능 아님,
 * 1회성 관리 라우트(sensitivity-validation 전례). Yahoo 2년 이력 × 홀드아웃 검증.
 */
fun Route.moodWeightValidationRoutes(service: MoodWeightValidationService) {
    get("/moodweight-validation") {
        runCatching { service.validate() }
            .onSuccess { call.respond(it) }
            .onFailure {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("검증 실패: ${it.message}"))
            }
    }
}
