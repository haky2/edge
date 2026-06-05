package com.haky.edge.routes

import com.haky.edge.dart.DartClient
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// "a,b,c" → [a,b,c] (6자리 숫자만). MacroRoutes.kt의 동일 함수와 중복이나 파일 내 private라 재선언.
private fun String?.toCodeList(): List<String> =
    this?.split(",")?.map { it.trim() }?.filter { it.matches(Regex("""\d{6}""")) }?.distinct() ?: emptyList()

fun Route.earningsRoutes(dart: DartClient) {
    // GET /earnings?codes=a,b,c
    //   - 각 종목의 다음 정기공시 예정일·D-day 반환. DART pblntf_ty=A(정기공시) 기반.
    //   - 개별 종목 실패는 무시(전체 목록에서 빠짐). D-90 초과는 제외.
    //   - daysUntil 오름차순(임박한 것이 먼저).
    get("/earnings") {
        val codes = call.request.queryParameters["codes"].toCodeList()
        val entries = coroutineScope {
            codes.map { code -> async { runCatching { dart.getEarningsSchedule(code) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
                .filter { it.daysUntil in -7..90 } // 지연 1주이내 + 90일 이내 예정만
                .sortedBy { it.daysUntil }
        }
        call.respond(entries)
    }
}
