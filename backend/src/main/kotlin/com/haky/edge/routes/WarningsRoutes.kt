package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.toss.StockWarning
import com.haky.edge.toss.TossClient
import com.haky.edge.toss.toStockWarning
import com.haky.edge.util.KST
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate

// 종목코드는 6자리 영숫자(신규 ETF/ETN은 영문 섞임). 잘못된 입력을 토스까지 보내지 않고 먼저 거른다.
private val WARN_CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.warningsRoutes(toss: TossClient) {
    // GET /warnings/{code} — 종목 투자유의(시장경보·단기과열·정리매매·VI). 발동 없으면 빈 배열.
    // 토스에만 있는 데이터(한투 미제공). 키 미설정/오류 시에도 빈 배열로 응답해 상세 화면을 막지 않는다.
    get("/warnings/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!WARN_CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val today = LocalDate.now(KST).toString() // yyyy-MM-dd (KST 기준)
        val warnings: List<StockWarning> = runCatching {
            toss.getWarnings(code)
                // 해제일이 지난 과거 경보는 제외(만료된 칩은 오해를 부른다). endDate 미정(진행 중)은 포함.
                .filter { it.endDate.isNullOrBlank() || it.endDate >= today }
                .map { it.toStockWarning() }
        }.getOrDefault(emptyList())
        call.respond(warnings)
    }
}
