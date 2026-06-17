package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.CatalystService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""\d{6}""")

fun Route.catalystRoutes(catalyst: CatalystService) {
    // GET /catalysts/{code}?days=7&refresh=true
    //   종목별 재료(DART 공시 + 뉴스)를 호재/악재·강도·선반영까지 구조화 판정해 반환.
    //   days: 조회 기간(기본 7). refresh=true: 캐시 건너뛰고 즉시 재생성.
    get("/catalysts/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 30) ?: 7
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(catalyst.catalysts(code, days, force = force))
    }
}
