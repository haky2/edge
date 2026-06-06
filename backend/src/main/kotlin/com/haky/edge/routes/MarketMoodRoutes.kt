package com.haky.edge.routes

import com.haky.edge.macro.MarketMoodService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.marketMoodRoutes(service: MarketMoodService) {
    // GET /market-mood — 브리핑 "오늘 시장 분위기" 카드용.
    // 기존 10개 매크로 지표를 재사용해 코스피 방향을 Claude가 해석. 당일 공유 캐시.
    get("/market-mood") {
        call.respond(service.get())
    }
}
