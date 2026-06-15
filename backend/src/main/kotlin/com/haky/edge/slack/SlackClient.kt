package com.haky.edge.slack

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Slack 메시지 발송용 얇은 클라이언트(Bot Token 기반 chat.postMessage).
 *
 * 설계 원칙:
 * - **절대 예외를 던지지 않는다.** 이 클라이언트는 에러 알림 경로(StatusPages)에서도 호출되므로,
 *   Slack 호출 실패가 원래 요청 처리를 깨면 안 된다. 실패는 로그만 남기고 false 반환.
 * - 토큰/채널이 비면 no-op(로컬 개발에서 Slack 미설정 시 조용히 통과).
 * - 채널은 호출마다 지정(ops·DM·라운지 등 여러 대상을 한 봇으로 라우팅).
 */
class SlackClient(private val botToken: String) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    val isConfigured: Boolean get() = botToken.isNotBlank()

    /**
     * 채널(또는 사용자 ID)에 텍스트 메시지를 보낸다. text는 Slack mrkdwn으로 렌더된다.
     * @return 발송 성공 여부. 토큰/채널 미설정이거나 실패 시 false(예외 없음).
     */
    suspend fun postMessage(channel: String, text: String): Boolean {
        if (botToken.isBlank() || channel.isBlank()) return false
        return runCatching {
            val resp: PostMessageResponse = http.post("https://slack.com/api/chat.postMessage") {
                header(HttpHeaders.Authorization, "Bearer $botToken")
                contentType(ContentType.Application.Json)
                setBody(PostMessageRequest(channel = channel, text = text))
            }.body()
            if (!resp.ok) {
                // Slack은 HTTP 200 + {ok:false, error:"channel_not_found"|"not_in_channel"|...} 로 실패를 알린다.
                System.err.println("[Slack] postMessage 실패: ${resp.error} (channel=$channel)")
            }
            resp.ok
        }.getOrElse { e ->
            System.err.println("[Slack] postMessage 예외: ${e.message}")
            false
        }
    }

    /**
     * 슬래시 명령의 response_url 로 후속 메시지를 보낸다(봇 토큰 불필요 — URL 자체가 비밀).
     * 즉시 ack(3초 제약) 이후 시간이 걸리는 결과(분석 코멘트 등)를 비동기로 전달할 때 사용.
     * @param inChannel true면 채널 공유(in_channel), false면 본인만(ephemeral, 기본).
     * @param replaceOriginal true면 직전 ack 메시지를 결과로 교체.
     */
    suspend fun postToResponseUrl(
        responseUrl: String,
        text: String,
        inChannel: Boolean = false,
        replaceOriginal: Boolean = true,
    ): Boolean {
        if (responseUrl.isBlank()) return false
        return runCatching {
            http.post(responseUrl) {
                contentType(ContentType.Application.Json)
                setBody(ResponseUrlPayload(
                    text = text,
                    responseType = if (inChannel) "in_channel" else "ephemeral",
                    replaceOriginal = replaceOriginal,
                ))
            }
            true
        }.getOrElse { e ->
            System.err.println("[Slack] postToResponseUrl 예외: ${e.message}")
            false
        }
    }
}

@Serializable
private data class PostMessageRequest(
    val channel: String,
    val text: String,
)

@Serializable
private data class PostMessageResponse(
    val ok: Boolean = false,
    val error: String? = null,
)

@Serializable
private data class ResponseUrlPayload(
    val text: String,
    @kotlinx.serialization.SerialName("response_type") val responseType: String,
    @kotlinx.serialization.SerialName("replace_original") val replaceOriginal: Boolean,
)
