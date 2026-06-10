package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.ComparisonService
import com.haky.edge.macro.AnalysisMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""\d{6}""")

fun Route.comparisonRoutes(comparison: ComparisonService) {
    // GET /compare?codeA=&codeB=&mode=defensive|aggressive&refresh=true
    get("/compare") {
        val codeA = call.request.queryParameters["codeA"].orEmpty()
        val codeB = call.request.queryParameters["codeB"].orEmpty()
        if (!CODE_REGEX.matches(codeA) || !CODE_REGEX.matches(codeB)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("codeA, codeB 모두 6자리 숫자여야 합니다"))
            return@get
        }
        if (codeA == codeB) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("서로 다른 두 종목을 입력하세요"))
            return@get
        }
        val mode = AnalysisMode.from(call.request.queryParameters["mode"])
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(comparison.compare(codeA, codeB, mode, force))
    }
}
