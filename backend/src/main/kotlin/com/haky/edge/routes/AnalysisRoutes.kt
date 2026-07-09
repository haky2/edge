package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.Position
import com.haky.edge.macro.AnalysisMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.analysisRoutes(analysis: AnalysisService) {
    // GET /analysis/{code}?mode=defensive|aggressive&refresh=true — 종목 종합 코멘트(시세·52주·PER·수급·뉴스 → Claude 해석).
    //   mode 미지정 시 defensive 폴백. 공격 모드는 평단·신호에 묶은 개별 종목 매매 판단까지 제시. 당일·모드별 캐시.
    //   refresh=true: 캐시를 건너뛰고 즉시 재생성(사용자 수동 요청 시). 생성 후 새 결과로 캐시 덮어씀.
    get("/analysis/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val avgPrice = call.parameters["avgPrice"]?.toDoubleOrNull()
        val qty = call.parameters["qty"]?.toLongOrNull()
        val position = if (avgPrice != null && qty != null) Position(
            avgPrice = avgPrice,
            qty = qty,
            targetPrice = call.parameters["targetPrice"]?.toDoubleOrNull() ?: 0.0,
            stopPrice = call.parameters["stopPrice"]?.toDoubleOrNull() ?: 0.0,
        ) else null
        val mode = AnalysisMode.from(call.request.queryParameters["mode"])
        val force = call.request.queryParameters["refresh"] == "true"
        // 투자 논지(선택) — 있으면 facts에 "검증할 가설"로 주입되고 캐시가 논지별로 분리된다.
        val thesis = call.request.queryParameters["thesis"]?.trim()?.takeIf { it.isNotBlank() }
        if (thesis != null && thesis.length > AnalysisService.THESIS_MAX_CHARS) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("투자 논지는 ${AnalysisService.THESIS_MAX_CHARS}자 이내로 입력해 주세요"))
            return@get
        }
        call.respond(analysis.analyze(code, position, mode, force = force, thesis = thesis))
    }
}
