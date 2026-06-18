package com.haky.edge.routes

import com.haky.edge.macro.EventSyncService
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.eventRoutes(eventSync: EventSyncService) {
    // GET /events?days=30  — 저장된 이벤트 중 오늘부터 N일 이내 반환 (기본 30일).
    // 막 지난 이벤트도 하루(어제)까지는 남겨 "지난 일정 복기"로 노출 → 다다음날 사라짐.
    get("/events") {
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 365) ?: 30
        call.respond(eventSync.getUpcoming(days, includePastDays = 1))
    }

    // POST /events/sync  — Claude 웹검색으로 향후 6주 이벤트 수집 + 저장. 스케줄러 or 수동 트리거.
    // rate limit(30k tokens/min) 주의: 주 1회 스케줄러 용도. 개발 중 연속 호출 금지.
    post("/events/sync") {
        val result = eventSync.sync()
        call.respond(result)
    }
}
