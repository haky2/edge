package com.stockapp.routes

import com.stockapp.ErrorResponse
import com.stockapp.kis.KisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""\d{6}""")

fun Route.quoteRoutes(kis: KisClient) {
    // GET /quote/{code} — 6자리 종목코드 현재가 조회
    get("/quote/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        call.respond(kis.getPrice(code))
    }
}
