package com.haky.edge.routes

import com.haky.edge.slack.SlackCommandService
import com.haky.edge.slack.SlackSignatureVerifier
import com.haky.edge.tasks.CloudTasksClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Slack 슬래시 명령 수신(S7). POST /slack/command — Slack 앱의 Slash Command Request URL.
 *
 * 인증: Slack 서명검증(SlackSignatureVerifier). Security.kt 토큰 게이트에서 이 경로는 제외됨
 *       (Slack은 EDGE_API_TOKEN을 못 보내므로 서명으로 대신 인증).
 * 3초 제약: Slack은 3초 내 200을 요구 → 즉시 ack 응답 후 분석은 비동기로 돌려 response_url에 결과 발송.
 *
 * **비동기 실행 경로(Cloud Run CPU 스로틀링 회피)**: ack 응답을 보내고 나면 Cloud Run은 CPU를 끊어
 * 인프로세스 백그라운드 분석이 외부 API 읽기 도중 잘린다("Not enough data available"). 그래서 배포 환경에선
 * 분석을 Cloud Tasks로 **별도 인바운드 요청**(POST /slack/analyze-task)으로 띄운다 — 그 요청이 처리되는
 * 동안엔 CPU가 할당되므로 끝까지 돈다. 로컬(큐 미설정)은 스로틀링이 없으니 기존처럼 인프로세스로 폴백.
 */
fun Route.slackCommandRoutes(
    verifier: SlackSignatureVerifier,
    commandService: SlackCommandService,
    tasks: CloudTasksClient,
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

        // 배포: Cloud Tasks로 워커 요청을 띄운다(enqueue는 빠른 호출이라 3초 ack 안에 끝남).
        // 로컬/예외: 큐 미설정이거나 enqueue 실패 → 인프로세스 폴백.
        val edgeToken = System.getenv("EDGE_API_TOKEN").orEmpty()
        val enqueued = if (tasks.enabled && edgeToken.isNotEmpty()) {
            val base = workerBaseUrl(call)
            val payload = buildJsonObject {
                put("text", text)
                put("response_url", responseUrl)
            }.toString()
            runCatching {
                tasks.enqueue(
                    url = "$base/slack/analyze-task",
                    bodyJson = payload,
                    headers = mapOf("X-Edge-Token" to edgeToken, "Content-Type" to "application/json"),
                )
            }.onFailure { System.err.println("[SlackCommand] enqueue 실패 → 인프로세스 폴백: ${it.message}") }
                .isSuccess
        } else false

        if (!enqueued) {
            call.application.launch { commandService.process(text, responseUrl) }
        }

        val label = text.trim().ifBlank { "종목" }
        call.respondText("🔍 ‘$label’ 분석 가져오는 중… (몇 초 걸려요)")
    }

    // Cloud Tasks 워커: 실제 분석은 여기서 동기로 돈다(인바운드 요청이라 처리 내내 CPU 할당됨).
    // 인증: Security.kt 토큰 게이트가 X-Edge-Token 검사 → Cloud Tasks가 헤더로 보낸다.
    // process()는 내부에서 예외를 모두 잡고 response_url로 결과/오류를 발송하므로 항상 2xx 반환(재시도 방지).
    post("/slack/analyze-task") {
        val obj = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val text = obj?.get("text")?.jsonPrimitive?.content.orEmpty()
        val responseUrl = obj?.get("response_url")?.jsonPrimitive?.content.orEmpty()
        commandService.process(text, responseUrl)
        call.respondText("OK")
    }
}

/**
 * Cloud Tasks가 다시 호출할 워커의 공개 베이스 URL. Cloud Run은 원본 Host 헤더를 보존하므로
 * 요청이 들어온 도메인(run.app 또는 커스텀)을 그대로 쓴다. 환경변수 PUBLIC_BASE_URL이 있으면 우선.
 */
private fun workerBaseUrl(call: io.ktor.server.application.ApplicationCall): String {
    System.getenv("PUBLIC_BASE_URL")?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
    val host = call.request.headers["X-Forwarded-Host"]
        ?: call.request.headers["Host"]
        ?: error("Host 헤더 없음 — PUBLIC_BASE_URL 환경변수로 워커 URL을 지정하세요")
    val proto = call.request.headers["X-Forwarded-Proto"] ?: "https"
    return "$proto://$host"
}

private suspend fun io.ktor.server.application.ApplicationCall.respond(status: HttpStatusCode, text: String) {
    respondText(text, status = status)
}
