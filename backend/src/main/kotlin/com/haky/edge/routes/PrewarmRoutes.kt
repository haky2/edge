package com.haky.edge.routes

import com.haky.edge.dart.DartClient
import com.haky.edge.kis.KisClient
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

private val CODE_REGEX = Regex("""\d{6}""")

/**
 * GET /prewarm?codes=a,b,c — 브리핑 진입 전 캐시 예열용.
 *
 * 목적: Cloud Scheduler가 장 시작 전(예: 08:45 KST) 1회 호출 → 관심종목의 시세·수급·공시를
 * 미리 조회해 인메모리 + GCS 파일 캐시(investor/dart-disclosure)를 채워둔다.
 * 그러면 사용자의 아침 첫 진입은 캐시 적중으로 빠르고, 콜드 스타트가 끼더라도 GCS 파일에서
 * 데이터 fan-out 없이 재사용된다(컨테이너 부팅 시간만 남음).
 *
 * 개별 실패는 무시하고 성공 카운트만 반환한다(예열은 best-effort).
 */
fun Route.prewarmRoutes(kis: KisClient, dart: DartClient) {
    get("/prewarm") {
        val codes = call.request.queryParameters["codes"].orEmpty()
            .split(",").map { it.trim() }.filter { CODE_REGEX.matches(it) }.distinct()

        val result = coroutineScope {
            // 시세·수급·공시를 종목별 병렬로 미리 조회 → 캐시 적재. KIS Semaphore(3)가 유량을 제어한다.
            val quotes = codes.map { c -> async { runCatching { kis.getPrice(c) }.isSuccess } }
            val flows = codes.map { c -> async { runCatching { kis.getInvestorFlow(c, 3) }.isSuccess } }
            val darts = codes.map { c -> async { runCatching { dart.getDisclosures(c, 7) }.isSuccess } }
            PrewarmResult(
                codes = codes.size,
                quotesOk = quotes.awaitAll().count { it },
                investorOk = flows.awaitAll().count { it },
                dartOk = darts.awaitAll().count { it },
            )
        }
        call.respond(result)
    }
}

@Serializable
private data class PrewarmResult(
    val codes: Int,
    val quotesOk: Int,
    val investorOk: Int,
    val dartOk: Int,
)
