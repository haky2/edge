package com.haky.edge.routes

import com.haky.edge.macro.SectorRotationService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.sectorRotationRoutes(sectorRotation: SectorRotationService) {
    // GET /sector-rotation — 섹터 자금 순환(C) 진단 결과(검증·디버그용).
    // 시장 분위기 브리핑에 facts로 주입되는 것과 동일한 계산. 앱은 직접 호출 안 함.
    get("/sector-rotation") {
        call.respond(sectorRotation.get())
    }
}
