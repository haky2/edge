package com.haky.edge.routes

import com.haky.edge.macro.MarketMoodLogService
import com.haky.edge.macro.MarketMoodService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.marketMoodLogRoutes(logService: MarketMoodLogService, marketMood: MarketMoodService) {
    // GET /market-mood-log — AI 시장 방향 예측 적중률 리포트.
    // 예측(미국 지수·환율 가중합) vs 실제(KOSPI 등락) 비교. PENDING = 당일 장 전 조회로 아직 채점 불가.
    // /market-mood와 동시 호출될 때 레이스 방지: 오늘 예측 항목을 먼저 보장한 뒤 반환.
    get("/market-mood-log") {
        marketMood.ensureTodayEntry()
        call.respond(logService.getAccuracyReport())
    }
}
