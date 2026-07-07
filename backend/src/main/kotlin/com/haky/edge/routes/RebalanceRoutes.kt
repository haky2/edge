package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.RebalanceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * 리밸런싱 트리거(R1) 라우트.
 * GET  /rebalance-check     — 룰 평가 결과 JSON(수동 검증·R3 앱 카드용). LLM 호출 없음.
 * POST /rebalance/baseline  — 현재 스냅샷을 드리프트 기준점으로 고정(의도한 매매 후 재설정).
 */
fun Route.rebalanceRoutes(service: RebalanceService) {
    get("/rebalance-check") {
        call.respond(service.check())
    }
    post("/rebalance/baseline") {
        runCatching { service.setBaseline() }
            .onSuccess { call.respond(it) }
            .onFailure { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "기준점 설정 실패")) }
    }
}
