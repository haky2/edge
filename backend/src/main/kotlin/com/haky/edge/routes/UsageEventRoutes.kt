package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.usage.UsageEvent
import com.haky.edge.usage.UsageEventLog
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** POST /usage-events 요청 바디 — 앱이 포그라운드 진입 시 모아둔 배치를 flush. */
@Serializable
data class UsageEventBatch(val events: List<UsageEvent> = emptyList())

@Serializable
data class UsageAck(val accepted: Int)

fun Route.usageEventRoutes(log: UsageEventLog) {
    // POST /usage-events — 카드 노출·펼침 배치 적재(멱등: (screen,card,action,at) 디듀프).
    //   과금 없음·LLM 없음. 배치 상한으로 폭주만 방지.
    post("/usage-events") {
        val batch = call.receive<UsageEventBatch>()
        if (batch.events.size > MAX_BATCH) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("배치는 최대 ${MAX_BATCH}건입니다"))
            return@post
        }
        call.respond(UsageAck(log.appendBatch(batch.events)))
    }

    // GET /usage-stats — 카드별 노출·펼침 수·최근 사용일(최근 90일). 대시보드 없음, 이 JSON이 정본.
    get("/usage-stats") {
        call.respond(log.stats())
    }
}

private const val MAX_BATCH = 500
