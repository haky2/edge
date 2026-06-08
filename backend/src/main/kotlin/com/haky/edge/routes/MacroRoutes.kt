package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.kis.KisClient
import com.haky.edge.macro.CopperClient
import com.haky.edge.macro.EcosClient
import com.haky.edge.macro.FearGreedClient
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
    // KIS: 코스피·코스닥·원/달러·다우·나스닥·S&P500·WTI유가·KODEX200시간외. Yahoo: 구리·미10년물·달러인덱스. CNN: 공포탐욕지수. ECOS: 국고채3년.
    // 개별 지표 실패는 무시되고 성공분만 반환(섹션 통째로 죽지 않게).
    get("/macro") {
        val kisItems = kis.getMacroIndicators()
        val kodexOt = runCatching { kis.getKodexOvertimeSignal() }.getOrNull()
        val extras = listOfNotNull(copper.get(), fearGreed.get(), ecos.get(), kodexOt) + yahoo.get()
        call.respond(kisItems + extras)
    }
}

fun Route.macroImpactRoutes(service: MacroImpactService) {
    // GET /macro-impact?holdings=a,b&watchlist=c,d
    //   - 보유/관심 종목 코드를 받아 오늘 매크로가 각 그룹에 미치는 영향(계산) + Claude 종합 해석 반환.
    //   - 코드만 받는다(평단 불필요 — 영향은 종목 속성이라 방향성 해석이지 손익 계산이 아님).
    get("/macro-impact") {
        val holdings = call.request.queryParameters["holdings"].toCodeList()
        val watchlist = call.request.queryParameters["watchlist"].toCodeList()
        call.respond(service.analyze(holdings, watchlist))
    }

    // GET /macro-signal/{code}
    //   - 종목 1개의 섹터 + 지표별 방향 신호. Claude 호출 없음. 상세화면에서 빠르게 조회.
    get("/macro-signal/{code}") {
        val code = call.parameters["code"]?.takeIf { it.matches(Regex("""\d{6}""")) }
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("유효한 종목 코드가 필요합니다"))
        call.respond(service.stockSignals(code))
    }
}

// "a,b,c" → [a,b,c] (빈/공백 제거, 6자리 숫자만).
private fun String?.toCodeList(): List<String> =
    this?.split(",")
        ?.map { it.trim() }
        ?.filter { it.matches(Regex("""\d{6}""")) }
        ?.distinct()
        ?: emptyList()
