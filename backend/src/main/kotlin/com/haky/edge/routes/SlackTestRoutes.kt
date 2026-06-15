package com.haky.edge.routes

import com.haky.edge.slack.SlackClient
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * S1 Slack 연동 검증용 라우트(다른 *TestRoutes 와 동일한 임시 검증 패턴).
 * - GET /slack-ping       : ops 채널에 직접 메시지 발송 → 토큰·채널 ID·봇 초대 여부를 한 번에 확인.
 * - GET /slack-test-error : 일부러 예외를 던져 StatusPages → OpsAlerter → Slack 알림 경로를 확인.
 */
fun Route.slackTestRoutes(slack: SlackClient, opsChannel: String) {
    get("/slack-ping") {
        val ok = slack.postMessage(opsChannel, "✅ Edge 백엔드 → Slack 연동 테스트 (S1 ping)")
        call.respondText("slack ping sent=$ok (configured=${slack.isConfigured}, channel=${opsChannel.ifBlank { "(none)" }})")
    }
    get("/slack-test-error") {
        throw RuntimeException("S1 Slack 오류 알림 테스트 — 의도적 예외")
    }
}
