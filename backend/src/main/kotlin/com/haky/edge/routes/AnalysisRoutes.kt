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
        // 논지 변천 이력(선택, C16 드리프트 점검) — JSON 배열 [{"d":"YYYY-MM-DD","t":"..."}].
        // 이력의 정본은 클라 로컬 DB(서버 무상태 원칙). 형식 오류는 400으로 명시(조용한 스킵 금지).
        val historyRaw = call.request.queryParameters["thesisHistory"]?.takeIf { it.isNotBlank() }
        val thesisHistory = if (historyRaw == null) emptyList() else {
            val parsed = runCatching {
                kotlinx.serialization.json.Json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(com.haky.edge.ai.ThesisSnapshot.serializer()), historyRaw)
            }.getOrNull()
            when {
                parsed == null -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("thesisHistory 형식이 올바르지 않습니다(JSON 배열)"))
                    return@get
                }
                parsed.size > AnalysisService.THESIS_HISTORY_MAX -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("논지 이력은 최근 ${AnalysisService.THESIS_HISTORY_MAX}개까지만 보낼 수 있습니다"))
                    return@get
                }
                parsed.any { it.t.length > AnalysisService.THESIS_MAX_CHARS } -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("논지 이력 항목은 ${AnalysisService.THESIS_MAX_CHARS}자 이내여야 합니다"))
                    return@get
                }
                else -> parsed
            }
        }
        // 계좌 성격(선택) — "long"이면 장기 계좌 컨텍스트(C18 장기 관점 코멘트 + 캐시 분리).
        // "long" 외 값(free 포함)은 null 정규화 = 기존 동작(구버전 앱 호환).
        val horizon = call.request.queryParameters["horizon"]?.takeIf { it == AnalysisService.HORIZON_LONG }
        call.respond(analysis.analyze(code, position, mode, force = force, thesis = thesis, thesisHistory = thesisHistory, horizon = horizon))
    }
}
