package com.haky.edge.routes

import com.haky.edge.slack.EventReminderService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * 이벤트 D-day 리마인더 라우트 (S5).
 * POST /slack/event-reminder — Cloud Scheduler 08:48 KST 잡이 호출.
 * GET  /slack/event-reminder — 수동 테스트·즉시 발송.
 */
fun Route.eventReminderRoutes(eventReminder: EventReminderService) {
    post("/slack/event-reminder") {
        eventReminder.send()
        call.respond(HttpStatusCode.OK)
    }
    get("/slack/event-reminder") {
        eventReminder.send()
        call.respondText("event reminder sent")
    }
}
