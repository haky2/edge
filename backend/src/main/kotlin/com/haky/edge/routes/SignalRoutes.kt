package com.haky.edge.routes

import com.haky.edge.slack.SignalService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * 신호 알림 스캔 라우트(S3a).
 * POST /slack/signals-scan — Cloud Scheduler(장 마감 후 수급 확정 시점) 잡이 호출.
 * GET  /slack/signals-scan — 수동 테스트. 응답 JSON으로 발화 신호를 확인(채널 미설정이어도 평가는 됨).
 */
fun Route.signalRoutes(signals: SignalService) {
    post("/slack/signals-scan") {
        call.respond(signals.scan())
    }
    get("/slack/signals-scan") {
        call.respond(signals.scan())
    }
}
