package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.JudgmentComparisonService
import com.haky.edge.ai.JudgmentTrade
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")
private val ACTIONS = setOf("buy", "sell", "interest")
private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * POST /judgment-comparison — 판단 대조("AI 말 들었으면?").
 * 행동 로그는 앱 로컬 SQLite에만 있어 앱이 전체 이력을 바디로 보낸다(B2 패턴).
 * 서버가 stance_log·일봉·코스피와 교차해 같은 잣대(20거래일 초과수익) 성적 대조를 반환.
 */
@Serializable
data class JudgmentComparisonRequest(val trades: List<JudgmentTrade> = emptyList())

fun Route.judgmentComparisonRoutes(service: JudgmentComparisonService) {
    post("/judgment-comparison") {
        val req = call.receive<JudgmentComparisonRequest>()
        val trades = req.trades.filter {
            CODE_REGEX.matches(it.code.trim()) && it.action in ACTIONS && DATE_REGEX.matches(it.date)
        }
        if (trades.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("대조할 행동 로그가 없습니다"))
            return@post
        }
        call.respond(service.compare(trades))
    }
}
