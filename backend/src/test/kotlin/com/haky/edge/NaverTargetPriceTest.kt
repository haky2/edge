package com.haky.edge

import com.haky.edge.news.NaverTargetPriceClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * O3 + 감사5탄 실측 버그 회귀 방지.
 * 실측(2026-07-11): 토론실 글 제목·뉴스 헤드라인의 "목표주가" 매치 + 링크 URL 숫자
 * (code=307950, article_id=0000007728)가 목표가로 오염돼 RegimeDetector·facts에 주입됐다.
 */
class NaverTargetPriceTest {

    // ── parseTargetPrice — 실측 사고 2건 재현 ──────────────────────────

    @Test
    fun `토론실 글 제목이 먼저 나와도 게시판 링크의 종목코드를 목표가로 잡지 않는다`() {
        // 307950 실측 재현: 첫 "목표주가"는 게시글, 진짜 블록은 뒤의 투자의견 테이블.
        val html = """
            <li><span><a href="/item/board_read.naver?code=307950&nid=425207067">목표주가 넘는다</a></span>
            <em>07/10 19:25</em></li> ... 중략 ...
            <th>투자의견 l 목표주가</th><td><em>3.81</em> 매수 l <em>684,063</em></td><th>52주최고</th>
        """.trimIndent()
        assertEquals(684_063L, NaverTargetPriceClient().parseTargetPrice(html))
    }

    @Test
    fun `뉴스 헤드라인이 먼저 나와도 기사 링크의 article_id를 목표가로 잡지 않는다`() {
        // 267260 실측 재현: article_id=0000007728이 7,728원으로 오염됐던 케이스.
        val html = """
            <a href="/item/news_read.naver?article_id=0000007728&office_id=1">목표주가는 줄상향…'고배당 매력'</a>
            <em>07/11</em> ... 중략 ...
            <th>투자의견 l 목표주가</th><td><em>4.00</em> 매수 l <em>1,487,692</em></td><th>52주최고</th>
        """.trimIndent()
        assertEquals(1_487_692L, NaverTargetPriceClient().parseTargetPrice(html))
    }

    @Test
    fun `투자의견 블록이 없으면 null - 컨센서스 없는 종목`() {
        val html = """<a href="/item/board_read.naver?code=307950">목표주가 간다</a>"""
        assertNull(NaverTargetPriceClient().parseTargetPrice(html))
    }

    @Test
    fun `태그 속성 안의 숫자는 무시하고 텍스트의 목표가만 잡는다`() {
        val html = """투자의견 <img width="8888" src="x"> 목표주가 <a href="?id=999999">l</a> <em>513,958</em>"""
        assertEquals(513_958L, NaverTargetPriceClient().parseTargetPrice(html))
    }

    // ── getTargetPrice — O3 캐시 정책 ──────────────────────────────────

    @Test
    fun `예외는 캐시하지 않는다 - 다음 호출이 재시도`() = runBlocking {
        var calls = 0
        val client = NaverTargetPriceClient(fetchOverride = {
            calls++
            if (calls == 1) throw RuntimeException("HTTP 503") else 500_000L
        })
        assertNull(client.getTargetPrice("005930"))          // 1차: 예외 → null, 미캐시
        assertEquals(500_000L, client.getTargetPrice("005930"))  // 2차: 재시도 성공
        assertEquals(2, calls)
    }

    @Test
    fun `파싱 null은 당일 캐시 - 같은 날 재조회 안 함`() = runBlocking {
        var calls = 0
        val client = NaverTargetPriceClient(fetchOverride = { calls++; null })
        assertNull(client.getTargetPrice("005930"))
        assertNull(client.getTargetPrice("005930"))  // 캐시 적중 — fetch 재호출 없음
        assertEquals(1, calls)
    }

    // ── 구조 변경 감지 — 값→null 전환 임계 ─────────────────────────────

    @Test
    fun `값이 있던 종목 3개가 null로 전환되면 경고 1회`() = runBlocking {
        val alerts = mutableListOf<String>()
        var day = "2026-07-10"
        var broken = false
        val client = NaverTargetPriceClient(
            onStructureAlert = { alerts += it },
            fetchOverride = { if (broken) null else 100_000L },
            clock = { day },
        )
        // 1일차: 4종목 정상 관측
        for (c in listOf("A", "B", "C", "D")) client.getTargetPrice(c)
        // 2일차: 구조 변경 — 전부 파싱 null
        day = "2026-07-11"; broken = true
        client.getTargetPrice("A"); client.getTargetPrice("B")
        assertEquals(0, alerts.size)      // 2종목까지는 침묵
        client.getTargetPrice("C")
        assertEquals(1, alerts.size)      // 3종목째에 1회
        client.getTargetPrice("D")
        assertEquals(1, alerts.size)      // 4종목째엔 중복 경고 없음(당일 1회)
    }

    @Test
    fun `원래 컨센서스 없던 종목의 null은 전환으로 세지 않는다`() = runBlocking {
        val alerts = mutableListOf<String>()
        var day = "2026-07-10"
        val client = NaverTargetPriceClient(
            onStructureAlert = { alerts += it },
            fetchOverride = { null },   // 항상 컨센서스 없음
            clock = { day },
        )
        for (c in listOf("A", "B", "C", "D")) client.getTargetPrice(c)
        day = "2026-07-11"
        for (c in listOf("A", "B", "C", "D")) client.getTargetPrice(c)
        assertEquals(0, alerts.size)  // 값→null 전환이 아니므로 오탐 없음
    }
}
