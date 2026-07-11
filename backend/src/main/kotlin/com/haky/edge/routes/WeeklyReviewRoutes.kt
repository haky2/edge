package com.haky.edge.routes

import com.haky.edge.slack.WeeklyReviewService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * 주간 회고 발송 라우트.
 * POST /slack/weekly-review — Cloud Scheduler 토요일 09:00 KST 잡이 호출. 주 1회 캐시·발송 중복 방지.
 * GET  /slack/weekly-review?dry=true — 생성만 하고 발송 없이 전문 반환(수동 검증).
 *      ?force=true — 이번 주 캐시 무시하고 재생성(+dry 아니면 재발송).
 */
fun Route.weeklyReviewRoutes(weeklyReview: WeeklyReviewService) {
    post("/slack/weekly-review") {
        weeklyReview.send()
        call.respond(HttpStatusCode.OK)
    }
    get("/slack/weekly-review") {
        val force = call.request.queryParameters["force"] == "true"
        val dry = call.request.queryParameters["dry"] == "true"
        val record = weeklyReview.send(force = force, dryRun = dry)
        call.respondText(record.report)
    }
}
