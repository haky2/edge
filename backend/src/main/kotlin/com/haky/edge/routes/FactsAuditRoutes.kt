package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.PortfolioReviewService
import com.haky.edge.ai.Position
import com.haky.edge.ai.ThesisSnapshot
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.MarketMoodService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

// ── /facts-audit 응답 DTO ────────────────────────────────────────────────

@Serializable
data class SectionSize(val label: String, val chars: Int)

@Serializable
data class StockFactsAudit(val code: String, val name: String, val totalChars: Int, val sections: List<SectionSize>)

/** 블록 하나의 종목 간 집계. shareOfAvgTotalPct = 이 블록 평균이 전체 평균 총량에서 차지하는 비율. */
@Serializable
data class SectionStat(
    val label: String,
    val presentIn: Int,
    val avgChars: Int,        // 존재하는 종목들만의 평균
    val maxChars: Int,
    val shareOfAvgTotalPct: Double,
)

@Serializable
data class FactsAuditReport(
    val date: String,
    val stockCount: Int,
    val avgTotalChars: Int,
    val maxTotalChars: Int,
    /** 트리거별 프롬프트(system) 상수 크기 — system은 프롬프트 캐시(90% 할인) 대상, facts는 매 호출 정가. */
    val prompts: List<SectionSize>,
    /** 블록별 집계, 평균 char 내림차순. */
    val sectionStats: List<SectionStat>,
    val stocks: List<StockFactsAudit>,
    /** 메타 블록 최대 콤보(합성: 포지션+논지 200자+변천 5건+장기 계좌) — 개인화 요청의 상한 크기. */
    val maxComboStock: StockFactsAudit? = null,
    val note: String,
)

/**
 * GET /facts-audit — facts 다이어트 1a 계측(운영 기능 아님, 1회성 관리 라우트).
 * 관심 종목 전체의 collectFacts를 실행해 블록별 char를 실측한다. LLM 호출 없음(수집 API 비용만).
 * char→token 환산은 실호출 usage 표본으로 별도 검증(정본 리포트 docs/facts-diet-2026-07.md).
 */
fun Route.factsAuditRoutes(analysis: AnalysisService, codes: List<String>) {
    get("/facts-audit") {
        runCatching {
            val stocks = mutableListOf<StockFactsAudit>()
            // KIS rate limit 배려 — collectFacts 내부가 이미 17콜 병렬이라 종목 간은 순차.
            for (code in codes) {
                val (name, sections) = analysis.auditFactsSections(code)
                stocks.add(StockFactsAudit(
                    code = code, name = name,
                    totalChars = sections.sumOf { it.text.length },
                    sections = sections.map { SectionSize(it.label, it.text.length) },
                ))
            }
            // 메타 블록 최대 콤보 — 실제 사용자 입력은 서버 무상태라 알 수 없으니 상한을 합성으로.
            val comboCode = codes.firstOrNull()
            val maxCombo = comboCode?.let { code ->
                val thesis200 = "가".repeat(AnalysisService.THESIS_MAX_CHARS)
                val history = (1..AnalysisService.THESIS_HISTORY_MAX).map {
                    ThesisSnapshot("2026-0${(it % 6) + 1}-15", thesis200)
                }
                val (name, sections) = analysis.auditFactsSections(
                    code,
                    position = Position(avgPrice = 70_000.0, qty = 100, targetPrice = 120_000.0, stopPrice = 60_000.0),
                    thesis = thesis200,
                    thesisHistory = history,
                    horizonLong = true,
                )
                StockFactsAudit(
                    code = code, name = name,
                    totalChars = sections.sumOf { it.text.length },
                    sections = sections.map { SectionSize(it.label, it.text.length) },
                )
            }

            val avgTotal = if (stocks.isEmpty()) 0 else stocks.sumOf { it.totalChars } / stocks.size
            val sectionStats = stocks.flatMap { it.sections }
                .groupBy { it.label }
                .map { (label, sizes) ->
                    val avg = sizes.sumOf { it.chars } / sizes.size
                    SectionStat(
                        label = label,
                        presentIn = sizes.size,
                        avgChars = avg,
                        maxChars = sizes.maxOf { it.chars },
                        // 분모는 전 종목 평균 총량 — "이 블록이 평균 요청에서 몇 %인가"
                        shareOfAvgTotalPct = if (avgTotal > 0)
                            Math.round(avg.toDouble() * sizes.size / stocks.size / avgTotal * 1000) / 10.0 else 0.0,
                    )
                }
                .sortedByDescending { it.avgChars * it.presentIn }

            val prompts = buildList {
                AnalysisService.promptCharSizes().forEach { (k, v) -> add(SectionSize(k, v)) }
                add(SectionSize("portfolio_defensive", PortfolioReviewService.promptFor(AnalysisMode.DEFENSIVE).length))
                add(SectionSize("portfolio_aggressive", PortfolioReviewService.promptFor(AnalysisMode.AGGRESSIVE).length))
                MarketMoodService.promptCharSizes().forEach { (k, v) -> add(SectionSize(k, v)) }
            }

            FactsAuditReport(
                date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString(),
                stockCount = stocks.size,
                avgTotalChars = avgTotal,
                maxTotalChars = stocks.maxOfOrNull { it.totalChars } ?: 0,
                prompts = prompts,
                sectionStats = sectionStats,
                stocks = stocks,
                maxComboStock = maxCombo,
                note = "char 기준(한국어 ≈ 1.3~1.6 token/char은 실호출 usage로 별도 환산). " +
                    "prompts=system(캐시 대상), sections=user facts(매 호출 정가). " +
                    "maxComboStock은 합성 상한(논지 ${AnalysisService.THESIS_MAX_CHARS}자·변천 ${AnalysisService.THESIS_HISTORY_MAX}건·포지션·장기 계좌).",
            )
        }.onSuccess { call.respond(it) }
            .onFailure {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("계측 실패: ${it.message}"))
            }
    }
}
