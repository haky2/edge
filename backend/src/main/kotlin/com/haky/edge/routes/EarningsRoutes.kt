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
    this?.split(",")?.map { it.trim() }?.filter { it.matches(Regex("""[0-9A-Z]{6}""")) }?.distinct() ?: emptyList()

fun Route.earningsPreviewRoutes(previewSvc: com.haky.edge.ai.EarningsPreviewService) {
    // GET /earnings-preview/{code}
    //   실적 발표 프리뷰(F3): run-rate 유지 시 YoY + 과거 발표일 반응 통계. LLM 0, 당일 캐시.
    get("/earnings-preview/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!code.matches(Regex("""[0-9A-Z]{6}"""))) {
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, com.haky.edge.ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        call.respond(previewSvc.preview(code))
    }
}

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
