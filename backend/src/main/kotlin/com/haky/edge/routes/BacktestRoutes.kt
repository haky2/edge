package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.BacktestService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.backtestRoutes(service: BacktestService) {
    // GET /backtest/{code} — 기존 일봉+수급으로 신호별 익일 적중률 측정. 당일 캐시.
    get("/backtest/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!Regex("""\d{6}""").matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        val result = service.getBacktest(code)
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("백테스트에 필요한 일봉 데이터가 부족합니다."))
        } else {
            call.respond(result)
        }
    }
}
