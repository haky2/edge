package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.toss.StockWarning
import com.haky.edge.toss.TossClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// 종목코드는 6자리 영숫자(신규 ETF/ETN은 영문 섞임). 잘못된 입력을 토스까지 보내지 않고 먼저 거른다.
private val WARN_CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.warningsRoutes(toss: TossClient) {
    // GET /warnings/{code} — 종목 투자유의(시장경보·단기과열·정리매매·VI). 발동 없으면 빈 배열.
    // 토스에만 있는 데이터(한투 미제공). 키 미설정/오류(404 등) 시에도 빈 배열로 응답해 상세 화면을 막지 않는다.
    get("/warnings/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!WARN_CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val warnings: List<StockWarning> = runCatching { toss.getActiveWarnings(code) }.getOrDefault(emptyList())
        call.respond(warnings)
    }

    // GET /price-limits/{code} — 종목 상·하한가. 제한폭 없거나 오류 시 503(앱은 카드만 숨김).
    get("/price-limits/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!WARN_CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val limits = runCatching { toss.getPriceLimits(code) }.getOrNull()
        if (limits == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("가격 제한폭을 가져오지 못했습니다"))
            return@get
        }
        call.respond(limits)
    }
}
