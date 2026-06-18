package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.investorRoutes(kis: KisClient) {
    // GET /investor/{code}?days=5 — 종목별 일별 외인/기관/개인 순매수(최근 N일, 최신일이 앞).
    get("/investor/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 5).coerceIn(1, 30)
        call.respond(kis.getInvestorFlow(code, days))
    }

    // GET /investor/batch?codes=a,b,c&days=3 — 여러 종목 수급을 1회 요청으로 조회(병렬 처리).
    // 반환: { "code": [flows...] } 맵. 개별 실패 종목은 키 자체가 빠짐(앱은 null 처리).
    get("/investor/batch") {
        val codes = call.request.queryParameters["codes"]
            ?.split(",")?.map { it.trim() }?.filter { CODE_REGEX.matches(it) }
            ?: emptyList()
        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 3).coerceIn(1, 30)
        val result: Map<String, List<InvestorFlow>> = coroutineScope {
            codes.map { code ->
                async { code to runCatching { kis.getInvestorFlow(code, days) }.getOrNull() }
            }.awaitAll()
        }.mapNotNull { (code, flows) -> flows?.let { code to it } }.toMap()
        call.respond(result)
    }
}
