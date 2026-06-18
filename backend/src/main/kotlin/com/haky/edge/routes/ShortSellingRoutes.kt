package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.macro.KrxShortSellingClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.shortSellingRoutes(krx: KrxShortSellingClient) {
    // GET /short-selling/{code} — 종목별 공매도 거래량·잔고 요약. 당일 캐시.
    get("/short-selling/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val result = krx.getShortSelling(code)
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("공매도 데이터를 가져오지 못했습니다."))
        } else {
            call.respond(result)
        }
    }
}
