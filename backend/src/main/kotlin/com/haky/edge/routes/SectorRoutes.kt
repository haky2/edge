package com.haky.edge.routes

import com.haky.edge.kis.KisClient
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.sectorRoutes(kis: KisClient) {
    // GET /sectors — 브리핑 "섹터 동향" 섹션용.
    // KOSPI 주요 업종지수(전기전자·기계·운수장비·전기가스업·서비스업·철강금속).
    // 개별 업종 실패는 무시되고 성공분만 반환(섹션 통째로 죽지 않게).
    get("/sectors") {
        call.respond(kis.getSectorIndices())
    }
}
