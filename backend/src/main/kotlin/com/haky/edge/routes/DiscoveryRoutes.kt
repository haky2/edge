package com.haky.edge.routes

import com.haky.edge.ai.DiscoveryService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * 지켜볼 후보 발굴(D1) 라우트.
 * GET /discovery?refresh=true — peer 바스켓 유니버스 스캔 결과(당일 캐시). LLM 호출 없음.
 */
fun Route.discoveryRoutes(service: DiscoveryService) {
    get("/discovery") {
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(service.discover(force))
    }
}
