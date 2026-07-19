package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.slack.SignalFiredLog
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.signalFiredRoutes(log: SignalFiredLog) {
    // GET /signal-fired/stats — 발화 로그 적재 현황(운영 확인용: 행수·종류별 수·기간).
    get("/signal-fired/stats") {
        call.respond(log.stats())
    }
}
