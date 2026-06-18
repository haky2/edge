package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.CatalystService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.catalystRoutes(catalyst: CatalystService) {
    // GET /catalysts/{code}?days=7&refresh=true
    //   종목별 재료(DART 공시 + 뉴스)를 호재/악재·강도·선반영까지 구조화 판정해 반환.
    //   days: 조회 기간(기본 7). refresh=true: 캐시 건너뛰고 즉시 재생성.
    get("/catalysts/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 30) ?: 7
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(catalyst.catalysts(code, days, force = force))
    }

    // GET /catalyst-brief?codes=018260,329180,...
    //   캐시된 재료 판정을 섹터별로 묶어 브리핑용 한 줄씩 반환. Claude 호출 없음(즉시).
    //   판정이 아직 캐시되지 않은 종목은 조용히 제외.
    get("/catalyst-brief") {
        val codes = call.request.queryParameters["codes"].orEmpty()
            .split(",").map { it.trim() }.filter { CODE_REGEX.matches(it) }
        if (codes.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("codes 파라미터가 없거나 유효한 6자리 코드가 없습니다"))
            return@get
        }
        call.respond(catalyst.brief(codes))
    }
}
