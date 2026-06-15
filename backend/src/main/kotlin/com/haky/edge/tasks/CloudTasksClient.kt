package com.haky.edge.tasks

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Cloud Tasks 큐에 HTTP 타깃 태스크를 넣는 얇은 REST 클라이언트(공식 SDK 없이 — ClaudeClient/KisClient 패턴).
 *
 * **왜 필요한가**: Cloud Run은 HTTP 응답을 보내고 나면 CPU 할당을 끊는다(기본 스로틀링).
 * Slack 슬래시 명령은 3초 내 ack가 강제라 분석(~13초)을 응답 뒤로 미뤄야 하는데, 응답 뒤
 * 백그라운드 코루틴은 CPU를 못 받아 외부 API(KIS/Claude) 읽기가 중간에 잘린다
 * (Ktor CIO `Not enough data available`). → 분석을 "별도의 인바운드 요청"으로 돌리면 그 요청이
 * 처리되는 동안엔 CPU가 할당된다. Cloud Tasks가 ack 직후 우리 워커 엔드포인트를 호출해 주는 역할.
 *
 * **인증**: Cloud Run 런타임 서비스계정 토큰을 메타데이터 서버에서 받아 Bearer로 Cloud Tasks API 호출
 * (런타임 SA에 roles/cloudtasks.enqueuer 필요). 태스크 자체는 X-Edge-Token 헤더를 달아 워커를 호출 →
 * 워커는 기존 토큰 게이트(Security.kt)로 인증된다(별도 OIDC 불필요).
 */
class CloudTasksClient(
    private val projectId: String,
    private val location: String,
    private val queue: String,
) {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0L

    /** 큐가 설정된 배포 환경에서만 활성. 로컬(미설정)은 호출부가 인프로세스 폴백을 쓴다. */
    val enabled: Boolean get() = projectId.isNotBlank() && queue.isNotBlank()

    /** 메타데이터 서버에서 SA 액세스 토큰을 받아 만료 60초 전까지 캐시(매 enqueue마다 재발급 방지). */
    private suspend fun accessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiryMs) return it }
        val resp = http.get(METADATA_TOKEN_URL) { header("Metadata-Flavor", "Google") }
        if (!resp.status.isSuccess()) error("메타데이터 토큰 조회 실패: ${resp.status}")
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val token = obj["access_token"]?.jsonPrimitive?.content ?: error("access_token 없음")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3000L
        cachedToken = token
        tokenExpiryMs = now + (expiresIn - 60).coerceAtLeast(0) * 1000
        return token
    }

    /**
     * HTTP 타깃 태스크 1건 enqueue. body는 base64로 감싸 전달(Cloud Tasks 규격).
     * @param url 워커 공개 URL  @param bodyJson 워커에 전달할 JSON 문자열  @param headers 태스크가 워커 호출 시 붙일 헤더
     */
    suspend fun enqueue(url: String, bodyJson: String, headers: Map<String, String>) {
        val token = accessToken()
        val b64Body = Base64.getEncoder().encodeToString(bodyJson.toByteArray(Charsets.UTF_8))
        val taskBody = buildJsonObject {
            put("task", buildJsonObject {
                put("httpRequest", buildJsonObject {
                    put("httpMethod", "POST")
                    put("url", url)
                    put("headers", buildJsonObject { headers.forEach { (k, v) -> put(k, v) } })
                    put("body", b64Body)
                })
            })
        }
        val endpoint = "https://cloudtasks.googleapis.com/v2/projects/$projectId/locations/$location/queues/$queue/tasks"
        val resp = http.post(endpoint) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(taskBody.toString())
        }
        if (!resp.status.isSuccess()) {
            error("Cloud Tasks enqueue 실패 ${resp.status}: ${resp.bodyAsText().take(300)}")
        }
    }

    private companion object {
        const val METADATA_TOKEN_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"
    }
}
