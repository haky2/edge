package com.haky.edge.routes

import com.haky.edge.master.StockMaster
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.searchRoutes(master: StockMaster) {
    // GET /search?q=삼성  또는  /search?q=0091
    // q 가 비어 있으면 StockMaster.search 가 빈 리스트를 반환한다(에러 아님 — 입력 전 상태).
    get("/search") {
        val q = call.request.queryParameters["q"].orEmpty()
        call.respond(master.search(q)) // List<StockInfo> → JSON 배열로 직렬화
    }
}
