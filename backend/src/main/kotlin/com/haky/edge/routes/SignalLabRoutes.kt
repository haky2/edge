package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.lab.SignalLabService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * GET /signal-lab?suite=anchor|discovery|volume&universe=peer|watch — 전략 실험실.
 * 선언적 신호 수트를 유니버스 리플레이 + 대조군 + 초과수익 채점으로 실측한다.
 */
fun Route.signalLabRoutes(service: SignalLabService) {
    get("/signal-lab") {
        val suite = call.request.queryParameters["suite"]
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("suite 필요 (가능: ${SignalLabService.SUITES.keys.joinToString(", ")})"),
            )
        val universe = call.request.queryParameters["universe"] ?: "peer"
        runCatching { service.run(suite, universe) }
            .onSuccess { call.respond(it) }
            .onFailure { e ->
                if (e is IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "잘못된 요청"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("실험 실패: ${e.message}"))
                }
            }
    }
}
