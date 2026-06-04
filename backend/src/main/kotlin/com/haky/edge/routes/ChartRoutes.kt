package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.kis.KisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""\d{6}""")

fun Route.chartRoutes(kis: KisClient) {
    // GET /daily/{code}?bars=62 — 일봉(최신일이 앞). 이평·RSI·거래량 추세 계산용.
    get("/daily/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        val bars = (call.request.queryParameters["bars"]?.toIntOrNull() ?: 62).coerceIn(5, 120)
        call.respond(kis.getDailyChart(code, bars))
    }
}
