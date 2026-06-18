package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.PeerValuationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.peerValuationRoutes(service: PeerValuationService) {
    // GET /peer-valuation/{code} — 동종 클러스터 대비 PER/PBR 상대 위치. 당일 캐시.
    //   클러스터 미정의·유효 peer 부족 시 404(앱은 카드 숨김).
    get("/peer-valuation/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!Regex("""[0-9A-Z]{6}""").matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val peer = service.getPeerValuation(code)
        if (peer == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("동종 비교에 필요한 peer 데이터가 부족합니다."))
        } else {
            call.respond(peer)
        }
    }
}
