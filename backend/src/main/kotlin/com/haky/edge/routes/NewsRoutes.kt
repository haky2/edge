package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.news.NaverNewsClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.newsRoutes(naver: NaverNewsClient) {
    // GET /news?q=삼성전자&display=5
    // q: 검색어(종목명). 앱이 WatchItem.name 을 그대로 넘긴다.
    // display: 결과 수 (기본 5, 최대 20).
    get("/news") {
        val q = call.request.queryParameters["q"].orEmpty().trim()
        if (q.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("q 파라미터가 필요합니다"))
            return@get
        }
        val display = call.request.queryParameters["display"]?.toIntOrNull() ?: 5
        call.respond(naver.search(q, display))
    }
}
