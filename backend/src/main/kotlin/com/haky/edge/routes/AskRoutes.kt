package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.AskDailyLimitException
import com.haky.edge.ai.AskTurn
import com.haky.edge.ai.Position
import com.haky.edge.macro.AnalysisMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

/** POST /ask/{code} 요청 바디. 포지션 필드는 GET /analysis 쿼리 파라미터와 동일 의미(있으면 facts에 내 포지션 포함). */
@Serializable
data class AskRequest(
    val question: String,
    val avgPrice: Double? = null,
    val qty: Long? = null,
    val targetPrice: Double? = null,
    val stopPrice: Double? = null,
    val mode: String? = null,                 // defensive(기본) | aggressive
    val history: List<AskTurn> = emptyList(), // 후속 질문 맥락 — 서버는 최근 3턴만 사용
    val thesis: String? = null,               // 투자 논지(선택) — facts에 "검증할 가설"로 주입
    val horizon: String? = null,              // "long" = 장기 계좌 컨텍스트(Q13 장기 관점 답변). 그 외 = 기존 동작
)

fun Route.askRoutes(analysis: AnalysisService) {
    // POST /ask/{code} — 종목 자유 질문 Q&A. analyze()와 같은 사실 데이터를 근거로 질문에만 답한다.
    //   자유 텍스트라 공유 캐시 없음 → 질문 길이 제한(400) + 서비스 일일 상한(429)으로 비용 방어.
    post("/ask/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@post
        }
        val req = call.receive<AskRequest>()
        val question = req.question.trim()
        if (question.length < 2) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("질문을 입력해 주세요"))
            return@post
        }
        if (question.length > AnalysisService.ASK_MAX_QUESTION_CHARS) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("질문은 ${AnalysisService.ASK_MAX_QUESTION_CHARS}자 이내로 입력해 주세요"))
            return@post
        }
        val thesis = req.thesis?.trim()?.takeIf { it.isNotBlank() }
        if (thesis != null && thesis.length > AnalysisService.THESIS_MAX_CHARS) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("투자 논지는 ${AnalysisService.THESIS_MAX_CHARS}자 이내로 입력해 주세요"))
            return@post
        }
        val position = if (req.avgPrice != null && req.qty != null) Position(
            avgPrice = req.avgPrice,
            qty = req.qty,
            targetPrice = req.targetPrice ?: 0.0,
            stopPrice = req.stopPrice ?: 0.0,
        ) else null
        val horizon = req.horizon?.takeIf { it == AnalysisService.HORIZON_LONG }
        try {
            call.respond(analysis.ask(code, question, position, AnalysisMode.from(req.mode), req.history, thesis, horizon))
        } catch (e: AskDailyLimitException) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(e.message ?: "오늘 질문 한도를 모두 사용했습니다"))
        }
    }
}
