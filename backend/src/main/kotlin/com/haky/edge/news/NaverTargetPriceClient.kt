package com.haky.edge.news

import com.haky.edge.util.KST
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
 *
 * O3 방어 2종:
 *  - 네트워크·HTTP **예외**는 캐시하지 않는다(다음 호출 재시도) — 일시 장애가 당일 내내
 *    목표가를 지우는 것 방지. 파싱 null(페이지에 컨센서스 없음)만 당일 negative 캐시.
 *  - **구조 변경 감지**: 직전까지 값이 있던 종목이 파싱 null로 바뀐 게 당일 3종목이 되는
 *    순간 onStructureAlert 1회(당일 자연 디듀프 — set 크기는 그날 안에서 단조 증가).
 *    "컨센서스 없는 종목" 오탐 방지를 위해 값→null 전환만 센다.
 */
class NaverTargetPriceClient(
    private val onStructureAlert: ((String) -> Unit)? = null,
    internal var clock: () -> String = { LocalDate.now(KST).toString() },  // 테스트용 날짜 주입
    internal var fetchOverride: (suspend (String) -> Long?)? = null,      // 테스트용 fetch 대체
) {
    private val http = HttpClient(CIO)

    private data class Cached(val price: Long?, val date: String)
    private val cache = ConcurrentHashMap<String, Cached>()

    private val nullTransitions = mutableSetOf<String>()  // 오늘 값→null 전환된 종목
    @Volatile private var transitionsDate = ""

    suspend fun getTargetPrice(code: String): Long? {
        val today = clock()
        cache[code]?.takeIf { it.date == today }?.let { return it.price }

        val prevPrice = cache[code]?.price  // 직전 관측(전일 이전, 웜 인스턴스 한정)
        val over = fetchOverride
        val result = runCatching { if (over != null) over(code) else fetch(code) }
        if (result.isFailure) return null   // 예외는 미캐시 — 다음 호출이 재시도(O3)

        val price = result.getOrNull()
        if (price == null && prevPrice != null) registerNullTransition(code, today)
        cache[code] = Cached(price, today)
        return price
    }

    @Synchronized
    private fun registerNullTransition(code: String, today: String) {
        if (transitionsDate != today) { nullTransitions.clear(); transitionsDate = today }
        nullTransitions += code
        if (nullTransitions.size == STRUCTURE_ALERT_THRESHOLD) {
            onStructureAlert?.invoke(
                "네이버 목표주가 파싱: 직전까지 값이 있던 종목 ${nullTransitions.size}개가 오늘 null 전환 — " +
                    "페이지 구조 변경 가능성 (${nullTransitions.joinToString(", ")})"
            )
        }
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
     * HTML에서 컨센서스 목표주가를 추출.
     *
     * "목표주가"는 토론실 글 제목·뉴스 헤드라인에도 등장한다 — 실측(2026-07-11 감사5탄):
     * 게시판 링크의 code=307950(종목코드), 뉴스 링크의 article_id=0000007728이 목표가로
     * 오염돼 RegimeDetector·비교/분석 facts에 그대로 주입됐다. 방어 2겹:
     *  ① 진짜 컨센서스 블록만 사용 — "투자의견 l 목표주가" 테이블이라 "투자의견"이 인접한
     *    발생만 인정(±300/400자). 게시글·뉴스 제목엔 이 조합이 사실상 없다.
     *  ② 숫자는 태그를 제거한 **텍스트에서만** 탐색 — href 속성 등 URL 안의 숫자를 차단.
     * 그 위에 기존 주가 범위 필터(1,000~10,000,000원) 유지.
     */
    internal fun parseTargetPrice(html: String): Long? {
        var idx = html.indexOf("목표주가")
        while (idx >= 0) {
            val ctx = html.substring(maxOf(0, idx - 300), minOf(idx + 400, html.length))
            if (ctx.contains("투자의견")) {
                val window = html.substring(idx, minOf(idx + 500, html.length))
                val text = window.replace(TAG_REGEX, " ")
                val n = NUM_REGEX.findAll(text)
                    .mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }
                    .firstOrNull { it in 1_000..10_000_000 }
                if (n != null) return n
            }
            idx = html.indexOf("목표주가", idx + 1)
        }
        return null
    }

    companion object {
        private val TAG_REGEX = Regex("""<[^>]*>""")
        private val NUM_REGEX = Regex("""([\d]{1,3}(?:,[\d]{3})+|[\d]{4,})""")
        private const val STRUCTURE_ALERT_THRESHOLD = 3  // 값→null 전환 종목 수(당일)
    }
}
