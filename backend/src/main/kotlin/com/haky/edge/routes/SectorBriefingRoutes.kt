package com.haky.edge.routes

import com.haky.edge.macro.SectorBriefingService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.sectorBriefingRoutes(service: SectorBriefingService) {
    // GET /sector-briefing?codes=a,b,c&refresh=true
    // 관심종목 코드 목록을 받아 오늘 섹터 트렌드 해석 + 주목 종목 반환. Claude 호출. 당일 캐시.
    // refresh=true: 캐시 bypass 재생성.
    get("/sector-briefing") {
        val codes = call.request.queryParameters["codes"]
            ?.split(",")?.map { it.trim() }
            ?.filter { it.matches(Regex("""\d{6}""")) }
            ?.distinct()
            ?: emptyList()
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(service.analyze(codes, force = force))
    }
}
