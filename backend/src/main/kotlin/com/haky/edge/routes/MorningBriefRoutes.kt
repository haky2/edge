package com.haky.edge.routes

import com.haky.edge.slack.MorningBriefService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * 아침 브리핑 발송 라우트.
 * POST /slack/morning-brief — Cloud Scheduler 08:50 KST 잡이 호출.
 * GET  /slack/morning-brief — 수동 테스트·즉시 발송.
 */
fun Route.morningBriefRoutes(morningBrief: MorningBriefService) {
    post("/slack/morning-brief") {
        morningBrief.send()
        call.respond(HttpStatusCode.OK)
    }
    get("/slack/morning-brief") {
        morningBrief.send()
        call.respondText("morning brief sent")
    }
}
