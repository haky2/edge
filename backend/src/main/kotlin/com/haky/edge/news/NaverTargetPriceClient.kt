package com.haky.edge.news

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 네이버 금융 HTML 파싱 → 컨센서스 목표주가.
 * 공식 무료 API 없음 → 스크래핑(개인/소규모 용도).
 * 당일 인메모리 캐시(코드별로 날짜가 바뀔 때만 재조회).
 */
class NaverTargetPriceClient {
    private val http = HttpClient(CIO)

    private data class Cached(val price: Long?, val date: String)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun getTargetPrice(code: String): Long? {
        val today = LocalDate.now().toString()
        cache[code]?.takeIf { it.date == today }?.let { return it.price }

        val price = runCatching { fetch(code) }.getOrNull()
        cache[code] = Cached(price, today)
        return price
    }

    private suspend fun fetch(code: String): Long? {
        val html: String = http.get(
            "https://finance.naver.com/item/main.naver?code=$code"
        ) {
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            header("Accept-Language", "ko-KR,ko;q=0.9")
        }.body()

        return parseTargetPrice(html)
    }

    /**
     * HTML에서 "목표주가" 이후 첫 번째 합리적 주가 범위(1,000~10,000,000원)의 숫자를 추출.
     * 네이버 금융 HTML 구조가 변경되어도 키워드+숫자 탐색이라 어느 정도 유연하다.
     */
    private fun parseTargetPrice(html: String): Long? {
        val idx = html.indexOf("목표주가")
        if (idx < 0) return null

        val window = html.substring(idx, minOf(idx + 500, html.length))
        val numRegex = Regex("""([\d]{1,3}(?:,[\d]{3})+|[\d]{4,})""")

        return numRegex.findAll(window)
            .mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }
            .firstOrNull { it in 1_000..10_000_000 }
    }
}
