package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.kis.KisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    // GET /quotes?codes=009150,047810,... — 관심종목 리스트용 다종목 시세.
    // 여러 종목을 병렬로 조회한다(한투 호출당 대기시간이 있어 순차보다 훨씬 빠름).
    get("/quotes") {
        val codes = call.request.queryParameters["codes"].orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { CODE_REGEX.matches(it) } // 형식 맞는 코드만, 중복 제거
            .distinct()

        // 각 종목을 async로 동시에 요청 → awaitAll로 모두 완료 대기.
        // 일부 종목이 실패해도 전체를 막지 않도록 runCatching으로 감싸고, 실패분은 제외(null 필터).
        val quotes = coroutineScope {
            codes.map { code -> async { runCatching { kis.getPrice(code) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
        }
        call.respond(quotes)
    }
}
