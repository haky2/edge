package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.kis.KisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""\d{6}""")

fun Route.investorRoutes(kis: KisClient) {
    // GET /investor/{code}?days=5 — 종목별 일별 외인/기관/개인 순매수(최근 N일, 최신일이 앞).
    get("/investor/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        // 기본 5일, 과하게 큰 값은 방어(한투 응답은 보통 30일 내외).
        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 5).coerceIn(1, 30)
        call.respond(kis.getInvestorFlow(code, days))
    }
}
