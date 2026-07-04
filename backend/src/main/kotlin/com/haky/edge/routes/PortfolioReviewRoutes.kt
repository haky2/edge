package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.PortfolioReviewService
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.HoldingPosition
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.portfolioReviewRoutes(service: PortfolioReviewService) {
    // GET /portfolio-review?positions=code:avg:qty,...&mode=defensive|aggressive&refresh=true
    //   보유 포지션 전체를 하나의 포트폴리오로 보고 구조(집중도·매크로 공통 노출·밸류 분포)를 진단.
    //   positions 포맷은 /macro-impact와 동일. 포지션이 입력이라 캐시는 개인별(날짜+포지션집합+모드).
    get("/portfolio-review") {
        val positions = call.request.queryParameters["positions"].toPositionMap()
        if (positions.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("positions가 비어 있습니다 (code:avg:qty,...)"))
            return@get
        }
        val mode = AnalysisMode.from(call.request.queryParameters["mode"])
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(service.review(positions, mode, force))
    }
}

// "code1:avg1:qty1,code2:avg2:qty2" → Map<code, HoldingPosition>. MacroRoutes와 동일 포맷.
private fun String?.toPositionMap(): Map<String, HoldingPosition> =
    this?.split(",")
        ?.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val code = parts[0].trim().takeIf { CODE_REGEX.matches(it) } ?: return@mapNotNull null
            val avg = parts[1].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val qty = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            code to HoldingPosition(avg, qty)
        }
        ?.toMap()
        ?: emptyMap()
