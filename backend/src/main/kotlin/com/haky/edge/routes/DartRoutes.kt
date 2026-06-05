package com.haky.edge.routes

import com.haky.edge.dart.DartClient
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.dartRoutes(dart: DartClient) {
    // GET /dart/{code}?days=7
    // code: 종목코드 6자리. days: 조회 기간(기본 7일).
    get("/dart/{code}") {
        val code = call.parameters["code"] ?: return@get
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
        call.respond(dart.getDisclosures(code, days))
    }
}
