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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Slack 슬래시 명령 + 인터랙션 라우트(S7·S8).
 *
 * POST /slack/command     — 슬래시 명령 수신 (Slack Slash Command Request URL)
 * POST /slack/analyze-task — Cloud Tasks 워커: 명령 처리 (X-Edge-Token 인증)
 * POST /slack/interaction  — Block Kit 버튼 인터랙션 수신 (Slack Interactivity Request URL)
 * POST /slack/interact-task — Cloud Tasks 워커: 인터랙션 처리 (X-Edge-Token 인증)
 *
 * Cloud Run CPU 스로틀링 문제: HTTP 응답 후 CPU를 끊으므로, 인프로세스 백그라운드에서
 * 외부 API(KIS·Claude)를 호출하면 잘린다. 따라서 배포 환경에서는 처리를 Cloud Tasks
 * 워커로 위임한다(인바운드 요청이라 처리 내내 CPU가 할당됨). 로컬(큐 미설정)은 인프로세스 폴백.
 */
fun Route.slackCommandRoutes(
    verifier: SlackSignatureVerifier,
    commandService: SlackCommandService,
    tasks: CloudTasksClient,
) {
    // ── 슬래시 명령 ──────────────────────────────────────────────────────────

    post("/slack/command") {
        val rawBody = call.receiveText()
        val ts = call.request.headers["X-Slack-Request-Timestamp"]
        val sig = call.request.headers["X-Slack-Signature"]
        if (!verifier.verify(ts, sig, rawBody)) {
            call.respondText("", status = HttpStatusCode.Unauthorized)
            return@post
        }

        val params = parseQueryString(rawBody)
        val text = params["text"].orEmpty()
        val responseUrl = params["response_url"].orEmpty()
        val userId = params["user_id"].orEmpty()
        val userName = params["user_name"].orEmpty()

        val enqueued = enqueueCommandTask(tasks, call, text, responseUrl, userId, userName)
        if (!enqueued) {
            call.application.launch { commandService.process(text, responseUrl, userId, userName) }
        }

        val label = when (text.trim().split(" ").firstOrNull()) {
            "시황" -> "시황"; "이벤트" -> "이벤트"; "비교" -> "비교"; "신호" -> "신호"
            else -> text.trim().ifBlank { "조회" }
        }
        call.respondText("🔍 *$label* 가져오는 중… (몇 초 걸려요)")
    }

    // Cloud Tasks 워커: 슬래시 명령 처리. 인증=X-Edge-Token(Security.kt 토큰 게이트).
    post("/slack/analyze-task") {
        val obj = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val text = obj?.get("text")?.jsonPrimitive?.content.orEmpty()
        val responseUrl = obj?.get("response_url")?.jsonPrimitive?.content.orEmpty()
        val userId = obj?.get("user_id")?.jsonPrimitive?.content.orEmpty()
        val userName = obj?.get("user_name")?.jsonPrimitive?.content.orEmpty()
        commandService.process(text, responseUrl, userId, userName)
        call.respondText("OK")
    }

    // ── 인터랙션 (Block Kit 버튼 클릭) ──────────────────────────────────────

    post("/slack/interaction") {
        val rawBody = call.receiveText()
        val ts = call.request.headers["X-Slack-Request-Timestamp"]
        val sig = call.request.headers["X-Slack-Signature"]
        if (!verifier.verify(ts, sig, rawBody)) {
            call.respondText("", status = HttpStatusCode.Unauthorized)
            return@post
        }

        // 인터랙션 페이로드는 application/x-www-form-urlencoded, payload 필드에 JSON.
        val payloadJson = parseQueryString(rawBody)["payload"].orEmpty()
        if (payloadJson.isBlank()) {
            call.respondText("")
            return@post
        }

        val payload = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: run {
            call.respondText("")
            return@post
        }

        if (payload["type"]?.jsonPrimitive?.content != "block_actions") {
            call.respondText("")
            return@post
        }

        val action = payload["actions"]?.jsonArray?.getOrNull(0)?.jsonObject
        val actionId = action?.get("action_id")?.jsonPrimitive?.content.orEmpty()
        val value = action?.get("value")?.jsonPrimitive?.content.orEmpty()
        val responseUrl = payload["response_url"]?.jsonPrimitive?.content.orEmpty()
        val user = payload["user"]?.jsonObject
        val userId = user?.get("id")?.jsonPrimitive?.content.orEmpty()
        val userName = user?.get("name")?.jsonPrimitive?.content.orEmpty()

        val enqueued = enqueueInteractTask(tasks, call, actionId, value, responseUrl, userId, userName)
        if (!enqueued) {
            call.application.launch { commandService.handleInteraction(userId, userName, actionId, value, responseUrl) }
        }

        // 빈 200 = 기존 ephemeral 메시지 그대로 유지(Slack 기본 동작).
        call.respondText("")
    }

    // Cloud Tasks 워커: 인터랙션 처리. 인증=X-Edge-Token.
    post("/slack/interact-task") {
        val obj = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
        val actionId = obj?.get("action_id")?.jsonPrimitive?.content.orEmpty()
        val value = obj?.get("value")?.jsonPrimitive?.content.orEmpty()
        val responseUrl = obj?.get("response_url")?.jsonPrimitive?.content.orEmpty()
        val userId = obj?.get("user_id")?.jsonPrimitive?.content.orEmpty()
        val userName = obj?.get("user_name")?.jsonPrimitive?.content.orEmpty()
        commandService.handleInteraction(userId, userName, actionId, value, responseUrl)
        call.respondText("OK")
    }
}

// ── 내부 헬퍼 ──────────────────────────────────────────────────────────────────

private suspend fun enqueueCommandTask(
    tasks: CloudTasksClient,
    call: io.ktor.server.application.ApplicationCall,
    text: String,
    responseUrl: String,
    userId: String,
    userName: String,
): Boolean {
    val edgeToken = System.getenv("EDGE_API_TOKEN").orEmpty()
    if (!tasks.enabled || edgeToken.isEmpty()) return false
    val base = workerBaseUrl(call)
    val payload = buildJsonObject {
        put("text", text)
        put("response_url", responseUrl)
        put("user_id", userId)
        put("user_name", userName)
    }.toString()
    return runCatching {
        tasks.enqueue(
            url = "$base/slack/analyze-task",
            bodyJson = payload,
            headers = mapOf("X-Edge-Token" to edgeToken, "Content-Type" to "application/json"),
        )
    }.onFailure { System.err.println("[SlackCommand] enqueue 실패 → 인프로세스 폴백: ${it.message}") }
        .isSuccess
}

private suspend fun enqueueInteractTask(
    tasks: CloudTasksClient,
    call: io.ktor.server.application.ApplicationCall,
    actionId: String,
    value: String,
    responseUrl: String,
    userId: String,
    userName: String,
): Boolean {
    val edgeToken = System.getenv("EDGE_API_TOKEN").orEmpty()
    if (!tasks.enabled || edgeToken.isEmpty()) return false
    val base = workerBaseUrl(call)
    val payload = buildJsonObject {
        put("action_id", actionId)
        put("value", value)
        put("response_url", responseUrl)
        put("user_id", userId)
        put("user_name", userName)
    }.toString()
    return runCatching {
        tasks.enqueue(
            url = "$base/slack/interact-task",
            bodyJson = payload,
            headers = mapOf("X-Edge-Token" to edgeToken, "Content-Type" to "application/json"),
        )
    }.onFailure { System.err.println("[SlackInteraction] enqueue 실패 → 인프로세스 폴백: ${it.message}") }
        .isSuccess
}

/**
 * Cloud Tasks가 다시 호출할 워커의 공개 베이스 URL. Cloud Run은 원본 Host 헤더를 보존하므로
 * 요청이 들어온 도메인을 그대로 쓴다. 환경변수 PUBLIC_BASE_URL이 있으면 우선.
 */
private fun workerBaseUrl(call: io.ktor.server.application.ApplicationCall): String {
    System.getenv("PUBLIC_BASE_URL")?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
    val host = call.request.headers["X-Forwarded-Host"]
        ?: call.request.headers["Host"]
        ?: error("Host 헤더 없음 — PUBLIC_BASE_URL 환경변수로 워커 URL을 지정하세요")
    val proto = call.request.headers["X-Forwarded-Proto"] ?: "https"
    return "$proto://$host"
}
