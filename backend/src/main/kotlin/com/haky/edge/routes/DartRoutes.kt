package com.haky.edge.routes

import com.haky.edge.dart.DartClient
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.dartRoutes(dart: DartClient) {
    // GET /dart/{code}?days=7
    get("/dart/{code}") {
        val code = call.parameters["code"] ?: return@get
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
        call.respond(dart.getDisclosures(code, days))
    }

    // GET /dart/batch?codes=a,b,c&days=7 — 여러 종목 공시를 1회 요청으로 조회(병렬 처리).
    // 반환: 전 종목 공시 통합 목록(최신순). 개별 실패는 무시해 나머지 종목 공시는 정상 반환.
    get("/dart/batch") {
        val codes = call.request.queryParameters["codes"]
            ?.split(",")?.map { it.trim() }?.filter { CODE_REGEX.matches(it) }
            ?: emptyList()
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
        val all = coroutineScope {
            codes.map { code -> async { runCatching { dart.getDisclosures(code, days) }.getOrElse { emptyList() } } }
                .awaitAll()
        }.flatten().sortedByDescending { it.date }
        call.respond(all)
    }
}
