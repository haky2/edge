package com.haky.edge.routes

import com.haky.edge.ai.ClaudeClient
import com.haky.edge.ai.WebSearchResult
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class WebSearchTestResponse(
    val text: String,
    val sources: List<WebSearchSourceDto>,
)

@Serializable
data class WebSearchSourceDto(val title: String, val url: String)

fun Routing.webSearchTestRoutes(claude: ClaudeClient) {
    // 웹검색 인프라 검증용. 슬라이스 2 이후 EventSyncService 로 옮기면 이 라우트는 제거.
    get("/websearch-test") {
        val q = call.request.queryParameters["q"] ?: "향후 4주 한국 주식시장 주요 거시 이벤트 일정"
        val result: WebSearchResult = claude.completeWithWebSearch(
            systemPrompt = "당신은 한국 주식시장 전문가입니다. 검색 결과를 바탕으로 사실만 한국어로 답하세요.",
            userFacts = q,
            maxTokens = 1024,
        )
        call.respond(WebSearchTestResponse(
            text = result.text,
            sources = result.sources.map { WebSearchSourceDto(it.title, it.url) },
        ))
    }
}
