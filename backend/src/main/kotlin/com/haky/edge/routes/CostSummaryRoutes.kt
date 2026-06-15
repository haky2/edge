package com.haky.edge.routes

import com.haky.edge.slack.CostSummaryService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Claude 일일 비용 요약 라우트 (S6).
 * POST /slack/cost-summary — Cloud Scheduler 21:00 KST 잡이 호출.
 * GET  /slack/cost-summary — 수동 테스트·즉시 발송.
 */
fun Route.costSummaryRoutes(costSummary: CostSummaryService) {
    post("/slack/cost-summary") {
        costSummary.send()
        call.respond(HttpStatusCode.OK)
    }
    get("/slack/cost-summary") {
        costSummary.send()
        call.respondText("cost summary sent")
    }
}
