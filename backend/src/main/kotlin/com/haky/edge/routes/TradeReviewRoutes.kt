package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.TradeReviewService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.LocalDate

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

/**
 * POST /trade-review 요청 바디. 사유·논지는 한글 자유 텍스트라 JSON 바디(포폴 POST 전례).
 * v1은 단일 매수→매도 쌍 — 분할 매수는 클라가 평균가로 합쳐 보낸다.
 * qty 선택: action_log에 수량이 없어 클라가 모를 수 있음(없으면 손익 금액 생략, 수익률만).
 */
@Serializable
data class TradeReviewRequest(
    val code: String,
    val buyDate: String,        // YYYY-MM-DD
    val buyPrice: Double,
    val sellDate: String,
    val sellPrice: Double,
    val qty: Long? = null,
    val buyReason: String? = null,
    val sellReason: String? = null,
    val thesis: String? = null, // 당시 투자 논지(선택)
    val refresh: Boolean = false,
)

fun Route.tradeReviewRoutes(service: TradeReviewService) {
    // POST /trade-review — 완결된 매매 1건 복기(계산된 가격 경로 + Claude 과정/결과 분리 해석).
    post("/trade-review") {
        val req = call.receive<TradeReviewRequest>()
        val code = req.code.trim()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("올바르지 않은 종목코드입니다 (6자리 영숫자)"))
            return@post
        }
        val buy = runCatching { LocalDate.parse(req.buyDate) }.getOrNull()
        val sell = runCatching { LocalDate.parse(req.sellDate) }.getOrNull()
        if (buy == null || sell == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("날짜는 YYYY-MM-DD 형식으로 입력해 주세요"))
            return@post
        }
        if (sell.isBefore(buy)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("매도일이 매수일보다 빠릅니다"))
            return@post
        }
        if (sell.isAfter(LocalDate.now(com.haky.edge.util.KST))) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("매도일이 미래입니다"))
            return@post
        }
        if (req.buyPrice <= 0 || req.sellPrice <= 0) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("가격은 0보다 커야 합니다"))
            return@post
        }
        for ((label, text) in listOf("매수 사유" to req.buyReason, "매도 사유" to req.sellReason, "투자 논지" to req.thesis)) {
            if (text != null && text.length > TradeReviewService.REASON_MAX_CHARS) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("${label}는 ${TradeReviewService.REASON_MAX_CHARS}자 이내로 입력해 주세요"))
                return@post
            }
        }
        call.respond(
            service.review(
                code = code,
                buyDate = req.buyDate, buyPrice = req.buyPrice,
                sellDate = req.sellDate, sellPrice = req.sellPrice,
                qty = req.qty?.takeIf { it > 0 },
                buyReason = req.buyReason, sellReason = req.sellReason,
                thesis = req.thesis,
                force = req.refresh,
            )
        )
    }
}
