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

internal fun String.stripHtml() = replace(Regex("<[^>]+>"), "")
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
    .fixMojibake()

// UTF-8 한글 바이트가 Latin-1(ISO-8859-1)로 잘못 디코딩돼 깨진 제목(예: "4,50대인"→"4,50ë...ì¸")을 복원한다.
// 네이버 일부 기사 제목이 드물게 이 형태로 들어온다. "UTF-8 리드바이트로 보이는 Latin-1 문자 + 연속바이트" 패턴에만 적용하고,
// 진짜 한글/CJK가 섞였거나(>U+00FF) 복원 결과가 유효 한글이 아니면 원문을 그대로 둔다 → 정상·유럽어 제목은 절대 손대지 않음.
private val MOJIBAKE_PATTERN =
    Regex("[Â-ß][-¿]|[à-ï][-¿]{2}|[ð-ô][-¿]{3}")

internal fun String.fixMojibake(): String {
    if (!MOJIBAKE_PATTERN.containsMatchIn(this)) return this
    if (any { it.code > 0xFF }) return this  // 진짜 한글/CJK가 섞이면 byte 재해석이 오히려 깨뜨림 → 손대지 않음
    val bytes = ByteArray(length) { this[it].code.toByte() }
    val repaired = String(bytes, Charsets.UTF_8)
    // 복원 결과에 대체문자(U+FFFD)가 없고 한글(가~힣)이 포함될 때만 채택.
    return if (!repaired.contains('�') && repaired.any { it in '가'..'힣' }) repaired else this
}

private fun extractSource(url: String): String = try {
    val host = java.net.URI(url).host ?: return ""
    if (host.startsWith("www.")) host.removePrefix("www.") else host
} catch (_: Exception) { "" }

class NewsException(message: String) : RuntimeException(message)
