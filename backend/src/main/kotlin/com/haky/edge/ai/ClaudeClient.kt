package com.haky.edge.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Anthropic Messages API 직접 호출 클라이언트(공식 SDK 없이 REST — KisClient/NaverNewsClient 와 동일 패턴).
 *
 * - 엔드포인트: POST https://api.anthropic.com/v1/messages
 * - 헤더: x-api-key, anthropic-version: 2023-06-01
 * - 프롬프트 캐싱: system 을 text 블록 배열로 두고 마지막 블록에 cache_control(ephemeral).
 *   (Sonnet 4.6 최소 캐시 prefix=2048토큰. 시스템 프롬프트가 그보다 짧으면 캐시 미적용 — 커지면 자동 적용.)
 * - 사실 데이터는 매 종목 다르므로 user 메시지(캐시 안 함)에 둔다.
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            // LLM 응답은 수 초~수십 초. 이어쓰기 여러 라운드까지 감안해 넉넉히.
            requestTimeoutMillis = 120_000
        }
    }

    /**
     * 시스템 프롬프트(캐시 대상) + 사실 데이터(user)로 한국어 코멘트 1개 생성.
     *
     * maxTokens 는 *상한*이다(목표치 아님). 모델은 할 말이 끝나면 stop_reason=end_turn 으로 스스로 멈추므로
     * 상한을 넉넉히 둬도 짧은 답은 그대로 짧다. 다만 상한에 걸려(stop_reason=max_tokens) 문장이 잘리면,
     * 직전까지의 응답을 assistant 턴으로 되넣어(prefill) 이어서 쓰게 한다 → 어떤 길이도 중간에 안 잘린다.
     */
    suspend fun complete(systemPrompt: String, userFacts: String, maxTokens: Int = 1024): String {
        if (apiKey.isBlank()) {
            throw ClaudeException("ANTHROPIC_API_KEY 가 설정되지 않았습니다 (.env 확인)")
        }
        val system = listOf(ClaudeSystemBlock(text = systemPrompt, cacheControl = ClaudeCacheControl()))
        val full = StringBuilder()
        repeat(MAX_CONTINUATIONS) {
            // 첫 호출은 user(사실)만. 이어쓰기 라운드는 지금까지 쓴 글을 assistant 턴으로 prefill.
            val messages = if (full.isEmpty()) {
                listOf(ClaudeMessage(role = "user", content = userFacts))
            } else {
                listOf(
                    ClaudeMessage(role = "user", content = userFacts),
                    // assistant prefill 은 끝에 공백이 있으면 API가 거부 → trimEnd.
                    ClaudeMessage(role = "assistant", content = full.toString().trimEnd()),
                )
            }
            val (text, stopReason) = callOnce(system, messages, maxTokens)
            full.append(text)
            if (stopReason != "max_tokens") return full.toString().trim()
        }
        // 이어쓰기 한도까지 갔는데도 안 끝남 — 지금까지 쓴 것이라도 반환(잘림보단 낫다).
        return full.toString().trim()
    }

    /** Messages API 1회 호출 → (텍스트, stop_reason). */
    private suspend fun callOnce(
        system: List<ClaudeSystemBlock>,
        messages: List<ClaudeMessage>,
        maxTokens: Int,
    ): Pair<String, String?> {
        val req = ClaudeRequest(model = model, maxTokens = maxTokens, system = system, messages = messages)
        val resp = http.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        // 한투/네이버처럼 본문 rt_cd 가 아니라, Anthropic 은 HTTP 상태로 성패를 알린다.
        if (!resp.status.isSuccess()) {
            throw ClaudeException("Claude API ${resp.status}: ${resp.bodyAsText().take(300)}")
        }
        val body: ClaudeResponse = resp.body()
        val text = body.content.firstOrNull { it.type == "text" }?.text
            ?: throw ClaudeException("Claude 응답에 text 블록이 없습니다")
        return text to body.stopReason
    }

    private companion object {
        // 첫 호출 + 이어쓰기. 3 라운드면 상한 3500 기준 ~1만 토큰까지 — 코멘트엔 차고 넘침.
        const val MAX_CONTINUATIONS = 3
    }
}

// ── 요청/응답 모델 (Anthropic Messages API wire 포맷) ──────────────────────
@Serializable
private data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: List<ClaudeSystemBlock>,
    val messages: List<ClaudeMessage>,
)

@Serializable
private data class ClaudeSystemBlock(
    val type: String = "text",
    val text: String,
    @SerialName("cache_control") val cacheControl: ClaudeCacheControl? = null,
)

@Serializable
private data class ClaudeCacheControl(val type: String = "ephemeral")

@Serializable
private data class ClaudeMessage(val role: String, val content: String)

@Serializable
private data class ClaudeResponse(
    val content: List<ClaudeContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: ClaudeUsage? = null,
)

@Serializable
private data class ClaudeContentBlock(val type: String = "", val text: String = "")

@Serializable
private data class ClaudeUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
)

class ClaudeException(message: String) : RuntimeException(message)
