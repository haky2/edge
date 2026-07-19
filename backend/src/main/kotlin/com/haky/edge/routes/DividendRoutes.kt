package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.dart.DartClient
import com.haky.edge.dart.DividendCard
import com.haky.edge.kis.KisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.dividendRoutes(dartClient: DartClient, kisClient: KisClient) {
    get("/dividend/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!Regex("""[0-9A-Z]{6}""").matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val div = dartClient.getDividendInfo(code) ?: run {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("배당 데이터가 없습니다(무배당 또는 미확정)."))
            return@get
        }
        val price = runCatching { kisClient.getPrice(code).price }.getOrNull() ?: 0L
        val expectedYieldPct = if (price > 0L) div.dpsThis.toDouble() / price * 100 else null
        call.respond(DividendCard(
            code = code,
            fiscalYear = div.fiscalYear,
            dpsThis = div.dpsThis,
            dpsPrev = div.dpsPrev,
            dpsPrev2 = div.dpsPrev2,
            dpsYoyPct = div.dpsYoyPct,
            yieldPctAtRecord = div.yieldPctAtRecord,
            payoutPct = div.payoutPct,
            settleMonth = div.settleDate?.substring(5, 7)?.toIntOrNull(),
            expectedYieldPct = expectedYieldPct,
        ))
    }
}
