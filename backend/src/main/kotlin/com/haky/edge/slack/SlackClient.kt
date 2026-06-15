package com.haky.edge.slack

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
                setBody(PostMessageRequest(channel = channel, text = SlackFormat.sanitize(text)))
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
                    text = SlackFormat.sanitize(text),
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

    /**
     * ephemeral Block Kit 메시지 + [채널에 공유] 버튼.
     * 본문이 길면 2900자 단위로 section block을 나눈다(Slack block section 최대 3000자).
     * @param shareValue 버튼 value — 인터랙션 핸들러가 받아 내용을 재실행할 때 쓰는 원본 커맨드 텍스트.
     */
    suspend fun postWithShareButton(
        responseUrl: String,
        content: String,
        shareValue: String,
    ): Boolean {
        if (responseUrl.isBlank()) return false
        val sanitized = SlackFormat.sanitize(content)
        val chunks = sanitized.splitToBlockChunks(maxLen = 2900)
        val payloadJson = buildBlocksPayload(chunks, shareValue, inChannel = false)
        return runCatching {
            http.post(responseUrl) {
                contentType(ContentType.Application.Json)
                setBody(payloadJson)
            }
            true
        }.getOrElse { e ->
            System.err.println("[Slack] postWithShareButton 예외: ${e.message}")
            false
        }
    }

    /**
     * Slack users.info API로 사용자 표시 이름(display_name)을 가져온다.
     * display_name 비면 real_name, 그것도 비면 fallback 반환. 실패 시 fallback.
     */
    suspend fun getUserDisplayName(userId: String, fallback: String = userId): String {
        if (botToken.isBlank() || userId.isBlank()) return fallback
        return runCatching {
            val resp = http.get("https://slack.com/api/users.info?user=$userId") {
                header(HttpHeaders.Authorization, "Bearer $botToken")
            }.body<String>()
            val json = Json.parseToJsonElement(resp).jsonObject
            if (json["ok"]?.jsonPrimitive?.content != "true") return@runCatching fallback
            val profile = json["user"]?.jsonObject?.get("profile")?.jsonObject
            profile?.get("display_name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: profile?.get("real_name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: fallback
        }.getOrElse { fallback }
    }

    /**
     * in_channel Block Kit 메시지 — [채널에 공유] 버튼 클릭 시 동일 내용을 채널에 공개 게시.
     * "OO님이 공유했어요" 헤더 section을 맨 앞에 붙인다.
     */
    suspend fun postInChannelShared(
        responseUrl: String,
        content: String,
        sharedByName: String,
    ): Boolean {
        if (responseUrl.isBlank()) return false
        val header = "_*${sharedByName}*님이 공유했어요_"
        val sanitized = SlackFormat.sanitize(content)
        val chunks = sanitized.splitToBlockChunks(maxLen = 2900)
        val payloadJson = buildBlocksPayload(chunks, shareValue = "", inChannel = true, headerText = header)
        return runCatching {
            http.post(responseUrl) {
                contentType(ContentType.Application.Json)
                setBody(payloadJson)
            }
            true
        }.getOrElse { e ->
            System.err.println("[Slack] postInChannelShared 예외: ${e.message}")
            false
        }
    }

    private fun String.splitToBlockChunks(maxLen: Int): List<String> {
        if (length <= maxLen) return listOf(this)
        val result = mutableListOf<String>()
        var remaining = this
        while (remaining.length > maxLen) {
            val cutAt = remaining.lastIndexOf("\n\n", maxLen).takeIf { it > maxLen / 2 }
                ?: remaining.lastIndexOf("\n", maxLen).takeIf { it > maxLen / 2 }
                ?: maxLen
            result.add(remaining.substring(0, cutAt).trim())
            remaining = remaining.substring(cutAt).trim()
        }
        if (remaining.isNotEmpty()) result.add(remaining)
        return result
    }

    private fun buildBlocksPayload(
        chunks: List<String>,
        shareValue: String,
        inChannel: Boolean,
        headerText: String? = null,
    ): String {
        val blocks = buildJsonArray {
            if (headerText != null) {
                add(buildJsonObject {
                    put("type", "section")
                    put("text", buildJsonObject {
                        put("type", "mrkdwn")
                        put("text", headerText)
                    })
                })
            }
            chunks.forEach { chunk ->
                add(buildJsonObject {
                    put("type", "section")
                    put("text", buildJsonObject {
                        put("type", "mrkdwn")
                        put("text", chunk)
                    })
                })
            }
            if (shareValue.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "actions")
                    put("elements", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "button")
                            put("text", buildJsonObject {
                                put("type", "plain_text")
                                put("text", "채널에 공유")
                            })
                            put("action_id", "share_to_channel")
                            put("value", shareValue.take(2000))
                        })
                    })
                })
            }
        }
        val payload = buildJsonObject {
            put("response_type", if (inChannel) "in_channel" else "ephemeral")
            put("replace_original", true)
            put("text", chunks.firstOrNull()?.take(200).orEmpty())
            put("blocks", blocks)
        }
        return payload.toString()
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
