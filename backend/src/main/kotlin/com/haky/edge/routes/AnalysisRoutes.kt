package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalysisService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""\d{6}""")

fun Route.analysisRoutes(analysis: AnalysisService) {
    // GET /analysis/{code} — 종목 종합 코멘트(시세·52주·PER·수급·뉴스 → Claude 해석). 당일 캐시.
    get("/analysis/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        call.respond(analysis.analyze(code))
    }
}
