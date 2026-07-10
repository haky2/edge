package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.DeepResearchLimitException
import com.haky.edge.ai.DeepResearchService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.deepResearchRoutes(service: DeepResearchService) {
    // GET /deep-research/{code} — 웹검색 결합 심층 리포트. (code, 날짜) 공유 캐시, force 없음(당일 1회면 충분),
    //   일일 상한 초과 시 429. 해외 종목은 facts가 빈약해 제외(6자리 regex가 자연 차단).
    get("/deep-research/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        try {
            call.respond(service.research(code))
        } catch (e: DeepResearchLimitException) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(e.message ?: "오늘 딥리서치 한도를 모두 사용했습니다"))
        }
    }
}
