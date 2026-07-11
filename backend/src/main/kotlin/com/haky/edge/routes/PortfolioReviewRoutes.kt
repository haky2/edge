package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.PortfolioReviewService
import com.haky.edge.ai.RebalanceService
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.HoldingPosition
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

/** POST /portfolio-review 바디의 포지션 1건. thesis(투자 논지)는 선택 — 한글 자유 텍스트라 GET 쿼리 대신 바디로 받는다. */
@Serializable
data class ReviewPositionEntry(
    val code: String,
    val avgPrice: Double,
    val qty: Long,
    val thesis: String? = null,
)

@Serializable
data class PortfolioReviewRequest(
    val positions: List<ReviewPositionEntry>,
    val mode: String? = null,      // defensive(기본) | aggressive
    val refresh: Boolean = false,
)

fun Route.portfolioReviewRoutes(service: PortfolioReviewService, rebalance: RebalanceService? = null) {
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
        // 리밸런싱 트리거(R1)용 포지션 스냅샷 — 응답 후 갱신이라 실패해도 진단에 영향 없음.
        rebalance?.let { r ->
            runCatching { r.recordSnapshot(positions) }
                .onFailure { println("[Rebalance] 스냅샷 기록 실패: ${it.message}") }
        }
    }

    // POST /portfolio-review — GET과 동일 진단 + 종목별 투자 논지(thesis) 지원.
    //   논지는 한글 자유 텍스트라 URL 인코딩 시 쿼리가 수 KB로 부풀어(10종목×200자×UTF-8 3바이트×%인코딩)
    //   URL 한도를 위협 → JSON 바디로 받는다. GET은 구버전 앱 호환용으로 유지.
    post("/portfolio-review") {
        val req = call.receive<PortfolioReviewRequest>()
        // S12: 중복 code 400 방어 — 같은 코드 2건이 오면 last-wins 조용한 병합 대신 명시적 에러.
        val rawCodes = req.positions.map { it.code.trim() }
        if (rawCodes.size != rawCodes.distinct().size) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("중복된 종목 코드가 있습니다"))
            return@post
        }
        val positions = req.positions.mapNotNull { e ->
            val code = e.code.trim().takeIf { CODE_REGEX.matches(it) } ?: return@mapNotNull null
            if (e.avgPrice <= 0 || e.qty <= 0) return@mapNotNull null
            code to HoldingPosition(e.avgPrice, e.qty)
        }.toMap()
        if (positions.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("positions가 비어 있습니다"))
            return@post
        }
        val theses = req.positions.mapNotNull { e ->
            val code = e.code.trim().takeIf { it in positions } ?: return@mapNotNull null
            val t = e.thesis?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (t.length > AnalysisService.THESIS_MAX_CHARS) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("투자 논지는 ${AnalysisService.THESIS_MAX_CHARS}자 이내로 입력해 주세요 (${code})"))
                return@post
            }
            code to t
        }.toMap()
        val mode = AnalysisMode.from(req.mode)
        call.respond(service.review(positions, mode, req.refresh, theses))
        rebalance?.let { r ->
            runCatching { r.recordSnapshot(positions) }
                .onFailure { println("[Rebalance] 스냅샷 기록 실패: ${it.message}") }
        }
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
