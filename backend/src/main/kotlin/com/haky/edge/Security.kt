package com.haky.edge

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlin.time.Duration.Companion.minutes

/**
 * 배포 보안 게이트(1.0c-a). 공개 Cloud Run URL이 인증 없는 프록시가 되면
 * URL 아는 누구나 /analysis 를 때려 ANTHROPIC 토큰을 실시간 과금시킬 수 있다.
 * 그래서 ① 공유 토큰 헤더 검사 + ② IP별 레이트리밋 두 층을 둔다.
 *
 * 한계(의도된 수준): 토큰은 앱 바이너리에 박혀 추출 가능 → "캐주얼 차단"이다.
 * 토큰 유출 시 청구 폭탄을 막는 진짜 안전판은 레이트리밋 + max-instances=1 + 비용 모니터링.
 * 악용되면 EDGE_API_TOKEN 만 교체하면 된다.
 */

/** 데이터 라우트에 적용할 레이트리밋 이름. /health 는 이 블록 밖이라 제한받지 않는다. */
val ApiRateLimit = RateLimitName("api")

/**
 * 레이트리밋 키. Cloud Run은 GFE 뒤라 remoteHost 는 프록시 IP로 다 같아진다 →
 * 실제 클라이언트는 X-Forwarded-For 의 맨 앞 항목. 없으면(로컬·내부 헬스체크) remoteHost 폴백.
 */
private fun clientKey(call: ApplicationCall): String {
    val forwarded = call.request.headers["X-Forwarded-For"]
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
    return if (!forwarded.isNullOrEmpty()) forwarded else call.request.origin.remoteHost
}

fun Application.configureSecurity() {
    // IP별 분당 호출 상한. 친구 몇 명 규모라 넉넉히 잡되, 토큰 유출 시 폭주는 막는다.
    // 한 종목 상세 진입이 여러 엔드포인트(quote·investor·analysis·news…)를 부르므로 60은 너무 빡빡 → 120.
    install(RateLimit) {
        register(ApiRateLimit) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call -> clientKey(call) }
        }
    }

    // 공유 토큰 인증. EDGE_API_TOKEN 이 비어 있으면(로컬 개발) 검사를 건너뛴다 →
    // 로컬은 토큰 없이 그대로 동작, 배포 환경에서만 토큰을 강제.
    val expectedToken = System.getenv("EDGE_API_TOKEN").orEmpty()
    intercept(ApplicationCallPipeline.Plugins) {
        // 헬스체크는 인증 없이 통과해야 Cloud Run 기동/생존 프로브가 막히지 않는다.
        if (call.request.path() == "/health") return@intercept
        // /slack/command 는 Slack이 EDGE_API_TOKEN을 못 보낸다 → 토큰 게이트 제외, Slack 서명검증으로 대신 인증.
        if (call.request.path() == "/slack/command") return@intercept
        if (expectedToken.isEmpty()) return@intercept // 로컬 개발: 인증 비활성
        val provided = call.request.headers["X-Edge-Token"]
        if (provided != expectedToken) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return@intercept finish() // 파이프라인 중단 — 라우트 핸들러까지 가지 않음
        }
    }
}
