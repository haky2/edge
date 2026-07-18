package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.PersonalWeeklyReviewService
import com.haky.edge.ai.WeeklyThesisChange
import com.haky.edge.ai.WeeklyTrade
import com.haky.edge.macro.HoldingPosition
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

/**
 * POST /weekly-review/personal — 개인 주간 회고(B2).
 *   포트폴리오·매매로그·논지 변화는 앱 로컬 SQLite에만 있어 앱이 바디로 보낸다(portfolio-review 패턴).
 *   서버가 보유 종목 주간 등락·AI 스탠스·목표가·다음 주 이벤트를 합쳐 Opus 해석 → 개인 회고 반환.
 */
@Serializable
data class PersonalWeeklyReviewRequest(
    val positions: List<PositionEntry> = emptyList(),
    val trades: List<WeeklyTrade> = emptyList(),
    val thesisChanges: List<WeeklyThesisChange> = emptyList(),
    val refresh: Boolean = false,
) {
    @Serializable
    data class PositionEntry(val code: String, val avgPrice: Double, val qty: Long)
}

fun Route.personalWeeklyReviewRoutes(service: PersonalWeeklyReviewService) {
    post("/weekly-review/personal") {
        val req = call.receive<PersonalWeeklyReviewRequest>()
        // 유효 포지션만 — 코드 형식·양수 가격/수량. 중복 코드는 마지막 값으로 접는다(집합 성격이라 무해).
        val positions = req.positions.mapNotNull { e ->
            val code = e.code.trim().takeIf { CODE_REGEX.matches(it) } ?: return@mapNotNull null
            if (e.avgPrice <= 0 || e.qty <= 0) return@mapNotNull null
            code to HoldingPosition(e.avgPrice, e.qty)
        }.toMap()
        val trades = req.trades.filter { it.code.trim().let { c -> CODE_REGEX.matches(c) } && it.action in ACTIONS }
        val theses = req.thesisChanges.filter { it.code.trim().let { c -> CODE_REGEX.matches(c) } && it.thesis.isNotBlank() }

        if (positions.isEmpty() && trades.isEmpty() && theses.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("회고할 데이터가 없습니다(포지션·매매·논지 모두 비어 있음)"))
            return@post
        }
        call.respond(service.review(positions, trades, theses, req.refresh))
    }
}

private val ACTIONS = setOf("buy", "sell")
