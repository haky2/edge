package com.haky.edge.routes

import com.haky.edge.ErrorResponse
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NewsItem
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.newsRoutes(naver: NaverNewsClient) {
    // GET /news?q=삼성전자&display=5
    // q: 검색어(종목명). 앱이 WatchItem.name 을 그대로 넘긴다.
    // display: 원하는 결과 수 (기본 5). 내부에서 3배 fetch 후 중복 제거해 반환.
    get("/news") {
        val q = call.request.queryParameters["q"].orEmpty().trim()
        if (q.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("q 파라미터가 필요합니다"))
            return@get
        }
        val display = call.request.queryParameters["display"]?.toIntOrNull() ?: 5
        val raw = naver.search(q, (display * 3).coerceAtMost(20))
        call.respond(deduplicateNews(raw, display))
    }
}

// 제목 자카드 유사도 0.45 이상이면 같은 이슈로 보고 대표 1건만 남긴다.
private fun deduplicateNews(items: List<NewsItem>, max: Int): List<NewsItem> {
    val result = mutableListOf<NewsItem>()
    for (item in items) {
        val tokens = titleTokens(item.title)
        if (result.none { newsJaccard(tokens, titleTokens(it.title)) >= 0.45 }) {
            result.add(item)
            if (result.size >= max) break
        }
    }
    return result
}

private fun titleTokens(title: String): Set<String> =
    title.split(Regex("[\\s\\[\\]()·,!?…\"']+"))
        .filter { it.length >= 2 }
        .toSet()

private fun newsJaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val inter = a.count { it in b }
    return inter.toDouble() / (a.size + b.size - inter)
}
