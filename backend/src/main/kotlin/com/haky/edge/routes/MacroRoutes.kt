package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.kis.KisClient
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.CopperClient
import com.haky.edge.macro.EcosClient
import com.haky.edge.macro.FearGreedClient
import com.haky.edge.macro.HoldingPosition
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.YahooMacroClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.macroRoutes(
    kis: KisClient,
    fearGreed: FearGreedClient,
    copper: CopperClient,
    ecos: EcosClient,
    yahoo: YahooMacroClient,
) {
    // GET /macro — 브리핑 "시장 지표" 섹션용.
    // KIS: 코스피·코스닥·원/달러·다우·나스닥·S&P500·WTI유가. Yahoo: 구리·미10년물·달러인덱스·EWY. CNN: 공포탐욕지수. ECOS: 국고채3년.
    // 개별 지표 실패는 무시되고 성공분만 반환(섹션 통째로 죽지 않게).
    get("/macro") {
        val kisItems = kis.getMacroIndicators()
        val extras = listOfNotNull(copper.get(), fearGreed.get(), ecos.get()) + yahoo.get()
        call.respond((kisItems + extras).sortedBy { MACRO_DISPLAY_ORDER.indexOf(it.key).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE })
    }
}

fun Route.macroImpactRoutes(service: MacroImpactService) {
    // GET /macro-impact?holdings=a,b&watchlist=c,d&mode=defensive|aggressive&positions=code:avg:qty,...&refresh=true
    //   - mode 미지정 시 defensive 폴백.
    //   - positions: 보유 종목 포지션(공격 모드 포트폴리오 스탠스에 활용). 미전달 시 빈 맵.
    //   - refresh=true: 캐시 bypass 재생성.
    get("/macro-impact") {
        val holdings = call.request.queryParameters["holdings"].toCodeList()
        val watchlist = call.request.queryParameters["watchlist"].toCodeList()
        val mode = AnalysisMode.from(call.request.queryParameters["mode"])
        val positionMap = call.request.queryParameters["positions"].toPositionMap()
        val force = call.request.queryParameters["refresh"] == "true"
        call.respond(service.analyze(holdings, watchlist, mode, positionMap, force = force))
    }

    // GET /macro-signal/{code}
    //   - 종목 1개의 섹터 + 지표별 방향 신호. Claude 호출 없음. 상세화면에서 빠르게 조회.
    get("/macro-signal/{code}") {
        val code = call.parameters["code"]?.takeIf { it.matches(Regex("""[0-9A-Z]{6}""")) }
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("유효한 종목 코드가 필요합니다"))
        call.respond(service.stockSignals(code))
    }
}

// "a,b,c" → [a,b,c] (빈/공백 제거, 6자리 숫자만).
private fun String?.toCodeList(): List<String> =
    this?.split(",")
        ?.map { it.trim() }
        ?.filter { it.matches(Regex("""[0-9A-Z]{6}""")) }
        ?.distinct()
        ?: emptyList()

// 시장 지표 표시 순서 — 위에서 아래로 한 흐름으로 읽히게:
// 우리 시장·원화 → 미국 증시(어젯밤 종가) → 미국 야간 선물(가장 최신) → 야간 한국물·아시아 → 금리·달러 → 원자재 → 심리.
private val MACRO_DISPLAY_ORDER = listOf(
    "kospi", "kosdaq", "usdkrw",                  // 우리 시장 + 원화
    "nasdaq", "sox", "sp500", "dow", "rut",       // 미국 증시(어젯밤 종가)
    "nqfut", "esfut", "ymfut",                    // 미국 야간 선물(종가 이후 최신 흐름)
    "ewy", "nikkei",                              // 야간 한국물·아시아
    "tnx", "rate3y", "dxy", "usdjpy",             // 금리·달러
    "crude", "copper",                            // 원자재
    "vix", "fear_greed",                          // 심리·변동성
)

// "code1:avg1:qty1,code2:avg2:qty2" → Map<code, HoldingPosition>
private fun String?.toPositionMap(): Map<String, HoldingPosition> =
    this?.split(",")
        ?.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val code = parts[0].trim().takeIf { it.matches(Regex("""[0-9A-Z]{6}""")) } ?: return@mapNotNull null
            val avg = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val qty = parts[2].toLongOrNull() ?: return@mapNotNull null
            code to HoldingPosition(avg, qty)
        }
        ?.toMap()
        ?: emptyMap()
