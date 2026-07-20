package com.haky.edge.routes

import com.haky.edge.ai.CommentSmokeService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.commentSmokeRoutes(svc: CommentSmokeService) {
    // GET /comment-smoke — 당일 캐시 코멘트 금지 패턴 스캔(발송 없음, 수동 검증용). LLM 0.
    get("/comment-smoke") {
        call.respond(svc.scan())
    }
    // POST /comment-smoke — 스캔 + 발견 시에만 #알림-운영오류 발송(0건 침묵). 토 10:00 KST 잡이 호출.
    post("/comment-smoke") {
        call.respond(svc.scanAndReport())
    }
}
