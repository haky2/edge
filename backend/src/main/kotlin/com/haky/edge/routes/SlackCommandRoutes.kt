package com.haky.edge.routes

import com.haky.edge.slack.SlackCommandService
import com.haky.edge.slack.SlackSignatureVerifier
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.launch

/**
 * Slack 슬래시 명령 수신(S7). POST /slack/command — Slack 앱의 Slash Command Request URL.
 *
 * 인증: Slack 서명검증(SlackSignatureVerifier). Security.kt 토큰 게이트에서 이 경로는 제외됨
 *       (Slack은 EDGE_API_TOKEN을 못 보내므로 서명으로 대신 인증).
 * 3초 제약: Slack은 3초 내 200을 요구 → 즉시 ack 응답 후 분석은 비동기로 돌려 response_url에 결과 발송.
 */
fun Route.slackCommandRoutes(
    verifier: SlackSignatureVerifier,
    commandService: SlackCommandService,
) {
    post("/slack/command") {
        // 서명검증은 파싱 전 **원문 본문**으로 해야 한다(HMAC은 바이트 단위).
        val rawBody = call.receiveText()
        val ts = call.request.headers["X-Slack-Request-Timestamp"]
        val sig = call.request.headers["X-Slack-Signature"]
        if (!verifier.verify(ts, sig, rawBody)) {
            call.respond(HttpStatusCode.Unauthorized, "invalid signature")
            return@post
        }

        val params = parseQueryString(rawBody)
        val text = params["text"].orEmpty()
        val responseUrl = params["response_url"].orEmpty()

        // 분석은 수 초 걸리므로(Claude) 비동기로 돌리고 즉시 ack한다.
        call.application.launch { commandService.process(text, responseUrl) }

        val label = text.trim().ifBlank { "종목" }
        call.respondText("🔍 ‘$label’ 분석 가져오는 중… (몇 초 걸려요)")
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respond(status: HttpStatusCode, text: String) {
    respondText(text, status = status)
}
