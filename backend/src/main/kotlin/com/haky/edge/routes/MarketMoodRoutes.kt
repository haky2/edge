package com.haky.edge.routes

import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.MarketMoodService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.marketMoodRoutes(service: MarketMoodService) {
    // GET /market-mood?mode=defensive|aggressive&refresh=true — 브리핑 "오늘 시장 분위기" 카드용.
    // 기존 매크로 지표를 재사용해 코스피 방향을 Claude가 해석. 모드별 당일 공유 캐시.
    // mode 미지정·오타는 방어적으로 폴백. refresh=true: 캐시 bypass 재생성.
    get("/market-mood") {
        val mode = AnalysisMode.from(call.request.queryParameters["mode"])
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(service.get(mode, force = force))
    }
}
