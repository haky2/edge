package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.PremortemService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""[0-9A-Z]{6}""")

@Serializable
data class PremortemRequest(
    val reason: String,
    val avgPrice: Double? = null,
    val qty: Long? = null,
    val stopPrice: Double? = null,
)

fun Route.premortemRoutes(premortem: PremortemService) {
    // POST /premortem/{code} — 매수 기록 시 프리모템 생성(F5). Claude 1회(기록당 자연 상한).
    //   body: {reason, avgPrice?, qty?, stopPrice?}. 종목당 최신 1건(새 매수가 교체).
    post("/premortem/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@post
        }
        val req = runCatching { call.receive<PremortemRequest>() }.getOrNull()
        if (req == null || req.reason.length > 200) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("본문이 없거나 reason이 200자를 넘습니다"))
            return@post
        }
        call.respond(premortem.create(code, req.reason, req.avgPrice, req.qty, req.stopPrice))
    }

    // GET /premortem/{code} — 저장된 프리모템 조회(상세 화면 조건 카드용). 없으면 404.
    get("/premortem/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!CODE_REGEX.matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val pm = premortem.get(code)
        if (pm == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("프리모템이 없습니다"))
        else call.respond(pm)
    }
}
