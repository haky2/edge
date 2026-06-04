package com.haky.edge.news

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 네이버 검색 API — 뉴스 헤드라인 수집.
 * 공식(무료, 일 25,000건). Client ID/Secret 을 헤더로 전달하는 단순 REST.
 * 앱이 종목명을 넘기면 그대로 검색어로 쓴다 — 백엔드가 종목명을 알 필요 없음.
 */
class NaverNewsClient(
    private val clientId: String,
    private val clientSecret: String,
) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * 종목명 기준 최신 뉴스 헤드라인. 네이버 검색은 sort=date 로 최신순.
     * HTML 태그(<b>, </b>) 가 섞여 오므로 제거해서 내려준다.
     */
    suspend fun search(query: String, display: Int = 5): List<NewsItem> {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw NewsException("NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 가 설정되지 않았습니다 (.env 확인)")
        }
        val resp: NaverNewsResponse = http.get("https://openapi.naver.com/v1/search/news.json") {
            header("X-Naver-Client-Id", clientId)
            header("X-Naver-Client-Secret", clientSecret)
            parameter("query", query)
            parameter("display", display.coerceIn(1, 20))
            parameter("sort", "date")
        }.body()
        return resp.items.map { it.toNewsItem() }
    }
}

/** 앱에 내려주는 뉴스 1건. 제목·요약·출처·URL·날짜. */
@Serializable
data class NewsItem(
    val title: String,
    val description: String,
    val source: String,
    val url: String,
    val publishedAt: String,
)

@Serializable
private data class NaverNewsResponse(
    val items: List<NaverNewsItem> = emptyList(),
)

@Serializable
private data class NaverNewsItem(
    val title: String = "",
    val description: String = "",
    @SerialName("originallink") val originalLink: String = "",
    val link: String = "",
    @SerialName("pubDate") val pubDate: String = "",
)

private fun NaverNewsItem.toNewsItem() = NewsItem(
    title = title.stripHtml(),
    description = description.stripHtml(),
    source = extractSource(originalLink),
    url = originalLink.ifBlank { link },
    publishedAt = pubDate,
)

private fun String.stripHtml() = replace(Regex("<[^>]+>"), "")
    .replace("&quot;", "\"")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&apos;", "'")
    .replace("&#[0-9]+;".toRegex()) { m ->
        m.value.removePrefix("&#").removeSuffix(";").toIntOrNull()
            ?.let { String(charArrayOf(it.toChar())) } ?: m.value
    }
    .trim()

private fun extractSource(url: String): String = try {
    val host = java.net.URI(url).host ?: return ""
    if (host.startsWith("www.")) host.removePrefix("www.") else host
} catch (_: Exception) { "" }

class NewsException(message: String) : RuntimeException(message)
