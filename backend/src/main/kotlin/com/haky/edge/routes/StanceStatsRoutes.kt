package com.haky.edge.routes

import com.haky.edge.ai.StanceStatsService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.stanceStatsRoutes(stanceStats: StanceStatsService) {
    // GET /stance-stats
    //   종목 코멘트 스탠스(긍정/중립/부정) vs 20거래일 후 실제 수익률 채점 집계(F6).
    //   기존 /market-mood-log(시장 방향 예측 적중률)와는 별도 지표. LLM 0, 당일 캐시.
    get("/stance-stats") {
        call.respond(stanceStats.stats())
    }
}
