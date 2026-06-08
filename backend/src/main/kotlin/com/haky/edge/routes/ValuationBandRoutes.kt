package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.ValuationBandService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.valuationBandRoutes(service: ValuationBandService) {
    // GET /valuation-band/{code} — PER/PBR 역사적 밴드 + 현재 백분위. 당일 캐시.
    get("/valuation-band/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!Regex("""\d{6}""").matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        val band = service.getValuationBand(code)
        if (band == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("밸류에이션 밴드 계산에 필요한 데이터가 부족합니다."))
        } else {
            call.respond(band)
        }
    }
}
