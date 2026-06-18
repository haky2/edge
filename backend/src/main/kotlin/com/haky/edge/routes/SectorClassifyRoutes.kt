package com.haky.edge.routes

import com.haky.edge.macro.MacroImpactService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.sectorClassifyRoutes(macroImpact: MacroImpactService) {
    // GET /sector-classify?codes=a,b,c
    // 종목 코드 → 대표 섹터 레이블. 포트폴리오 섹터 비중 계산용.
    // Claude 추론은 7일 캐시라 첫 호출 외엔 빠름. 새 종목만 수 초 지연 가능.
    get("/sector-classify") {
        val codes = call.request.queryParameters["codes"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.matches(Regex("""[0-9A-Z]{6}""")) }
            ?.distinct()
            ?: emptyList()
        call.respond(macroImpact.classifyStocks(codes))
    }
}
