package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.kis.KisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// 국내 종목코드는 6자리 숫자. 잘못된 입력을 한투까지 보내지 않고 여기서 먼저 걸러낸다.
private val CODE_REGEX = Regex("""\d{6}""")

fun Route.quoteRoutes(kis: KisClient) {
    // GET /quote/{code} — 6자리 종목코드 현재가 조회
    get("/quote/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            // 형식 오류는 호출자(앱) 잘못이므로 400. (한투 호출 비용도 아낌)
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 숫자여야 합니다: '$code'"))
            return@get
        }
        // 성공 시 Quote 가 JSON으로 직렬화돼 나간다. 실패(KisException 등)는 StatusPages가 처리.
        call.respond(kis.getPrice(code))
    }
}
