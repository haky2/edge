package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.ai.OverseasAnalysisService
import com.haky.edge.kis.KisClient
import com.haky.edge.master.OverseasMaster
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// 해외 종목코드 형식: "US:NAS:AAPL" (접두사:거래소코드:심볼).
// 접두사는 현재 "US" 고정(한투 해외 지원 시장). 거래소: NAS·NYS·AMS·TSE·HKS 등.
// 심볼은 BRK.B처럼 점/하이픈 포함 가능, 대문자+숫자+점+하이픈, 1~20자.
private val OVERSEAS_CODE_REGEX = Regex("""US:[A-Z]{2,5}:[A-Z0-9.\-]{1,20}""")

/** "US:NAS:AAPL" → Pair("NAS","AAPL"). 형식 불일치 시 null. */
internal fun parseOverseasCode(code: String): Pair<String, String>? {
    if (!OVERSEAS_CODE_REGEX.matches(code)) return null
    val parts = code.split(":")
    return if (parts.size == 3) Pair(parts[1], parts[2]) else null
}

fun Route.overseasRoutes(kis: KisClient, overseasMaster: OverseasMaster, overseasAnalysis: OverseasAnalysisService) {
    // GET /overseas/search?q=AAPL  또는  /overseas/search?q=애플
    // 대문자·숫자만 → 심볼 prefix. 소문자·한글 포함 → 이름 부분 일치.
    get("/overseas/search") {
        val q = call.request.queryParameters["q"].orEmpty()
        call.respond(overseasMaster.search(q))
    }

    // GET /overseas/quote?code=US:NAS:AAPL
    get("/overseas/quote") {
        val code = call.request.queryParameters["code"].orEmpty()
        val (excd, symb) = parseOverseasCode(code) ?: run {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("해외 종목코드 형식 오류: '$code' (예: US:NAS:AAPL)"),
            )
            return@get
        }
        call.respond(kis.getOverseasPrice(excd, symb))
    }

    // GET /overseas/analysis?code=US:NAS:AAPL — 간단 AI 코멘트(시세+뉴스만 근거). (code,날짜) 당일 공유 캐시.
    get("/overseas/analysis") {
        val code = call.request.queryParameters["code"].orEmpty()
        val (excd, symb) = parseOverseasCode(code) ?: run {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("해외 종목코드 형식 오류: '$code' (예: US:NAS:AAPL)"),
            )
            return@get
        }
        call.respond(overseasAnalysis.analyze(code, excd, symb))
    }

    // GET /overseas/quotes?codes=US:NAS:AAPL,US:NAS:MSFT,...
    // 형식 오류 코드는 조용히 제외(부분 실패 허용, 관심종목 리스트 UX 보호).
    get("/overseas/quotes") {
        val codes = call.request.queryParameters["codes"].orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { OVERSEAS_CODE_REGEX.matches(it) }
            .distinct()

        val quotes = coroutineScope {
            codes.map { code ->
                async {
                    runCatching {
                        val (excd, symb) = parseOverseasCode(code)!!
                        kis.getOverseasPrice(excd, symb)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        call.respond(quotes)
    }
}
