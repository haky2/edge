package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.AnalogService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

fun Route.analogRoutes(analog: AnalogService) {
    // GET /analog/{code}
    //   유사 국면 통계(F1): 오늘의 상태 벡터와 비슷했던 과거 시점들의 이후 5/20/60거래일
    //   실제 수익률 분포. LLM 호출 0(전부 계산), 당일 캐시.
    get("/analog/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        call.respond(analog.analog(code))
    }
}
