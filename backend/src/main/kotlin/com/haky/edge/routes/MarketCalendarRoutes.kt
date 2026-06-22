package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.toss.TossClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.marketCalendarRoutes(toss: TossClient) {
    // GET /market-calendar — 국내(KRX) 오늘 휴장 여부 + 직전/다음 거래일. 토스 기반(한투 미제공).
    // 키 미설정/토스 오류 시 503(앱은 캘린더 없이도 동작 — 배너만 숨김).
    get("/market-calendar") {
        val cal = runCatching { toss.getMarketCalendar() }.getOrNull()
        if (cal == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("개장 캘린더를 가져오지 못했습니다"))
            return@get
        }
        call.respond(cal)
    }
}
