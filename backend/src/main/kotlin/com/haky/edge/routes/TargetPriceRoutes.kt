package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.news.NaverTargetPriceClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class TargetPriceResponse(
    val code: String,
    val price: Long,
    val basis: String = "최근 3개월 증권사 컨센서스 평균 (네이버 금융)",
)

fun Route.targetPriceRoutes(naverTargetPrice: NaverTargetPriceClient) {
    get("/target-price/{code}") {
        val code = call.parameters["code"].orEmpty()
        if (!Regex("""[0-9A-Z]{6}""").matches(code)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("종목코드는 6자리 영숫자여야 합니다: '$code'"))
            return@get
        }
        val price = naverTargetPrice.getTargetPrice(code)
        if (price == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("컨센서스 목표주가를 찾을 수 없습니다 (애널리스트 미커버리지 가능성)"))
            return@get
        }
        call.respond(TargetPriceResponse(code = code, price = price))
    }
}
