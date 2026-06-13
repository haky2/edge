package com.haky.edge.routes

import com.haky.edge.kis.KisClient
import com.haky.edge.kis.KisFuturesResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

@Serializable
data class FuturesTestResult(
    val iscd: String,
    val rtCd: String,
    val msg: String,
    val output1IsNull: Boolean,
    val output1: String,   // JsonElement.toString()
    val output2: String,
    val output3: String,
)

// 코스피200 선물 근월물 코드 후보.
// YYMMformat: 101W + 연도2자리 + 월2자리
// letter format: 101W + 월코드(U=Sep) + 연도1자리
private val FUTURES_CODES = listOf(
    "101W2609", // 2026년 09월, YYYYMM 형식
    "101WU6",   // Sep 2026, letter month (U=Sep) + 6
    "101WM6",   // Jun 2026, M=Jun + 6 (만기 지났을 수도)
    "101W2606", // 2026년 06월, YYYYMM
)

fun Routing.futuresTestRoutes(kis: KisClient) {
    // 코스피200 선물 야간 신호 API 탐색용 테스트 라우트.
    // 근월물 코드 후보를 병렬로 호출하고 raw 응답을 반환한다.
    // 야간 선물 세션(18:00~05:00) 중에만 output1에 데이터가 채워진다.
    get("/futures-test") {
        val results = coroutineScope {
            FUTURES_CODES.map { iscd ->
                async {
                    runCatching { kis.getFuturesRaw(iscd) }
                        .fold(
                            onSuccess = { r ->
                                FuturesTestResult(
                                    iscd = iscd,
                                    rtCd = r.rtCd,
                                    msg = r.msg1,
                                    output1IsNull = r.output1 == null,
                                    output1 = r.output1?.toString() ?: "null",
                                    output2 = r.output2?.toString() ?: "null",
                                    output3 = r.output3?.toString() ?: "null",
                                )
                            },
                            onFailure = { e ->
                                FuturesTestResult(
                                    iscd = iscd,
                                    rtCd = "ERR",
                                    msg = e.message ?: "unknown",
                                    output1IsNull = true,
                                    output1 = "null",
                                    output2 = "null",
                                    output3 = "null",
                                )
                            },
                        )
                }
            }.awaitAll()
        }
        call.respond(results)
    }
}
