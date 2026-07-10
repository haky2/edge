package com.haky.edge

import com.haky.edge.ai.DailyLimiter
import com.haky.edge.ai.DeepResearchLimitException
import com.haky.edge.ai.DeepResearchService
import com.haky.edge.ai.ResearchSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DeepResearchService의 순수 계산 부분 — 일일 상한·캐시 키·출처 정리·2단계 입력 형식. */
class DeepResearchTest {

    // ── DailyLimiter ──────────────────────────────────────────────

    @Test
    fun `상한 내에서는 통과, 초과 시 예외`() {
        val limiter = DailyLimiter(2)
        limiter.tick("2026-07-10")
        limiter.tick("2026-07-10")
        assertFailsWith<DeepResearchLimitException> { limiter.tick("2026-07-10") }
    }

    @Test
    fun `날짜가 바뀌면 카운터 리셋`() {
        val limiter = DailyLimiter(1)
        limiter.tick("2026-07-10")
        assertFailsWith<DeepResearchLimitException> { limiter.tick("2026-07-10") }
        limiter.tick("2026-07-11") // 새 날 — 통과
    }

    @Test
    fun `release는 실패한 시도의 쿼터를 되돌린다`() {
        val limiter = DailyLimiter(1)
        limiter.tick("2026-07-10")
        limiter.release("2026-07-10")
        limiter.tick("2026-07-10") // 반환됐으므로 다시 통과
        assertFailsWith<DeepResearchLimitException> { limiter.tick("2026-07-10") }
    }

    @Test
    fun `다른 날짜의 release는 무시된다`() {
        val limiter = DailyLimiter(1)
        limiter.tick("2026-07-10")
        limiter.release("2026-07-09") // 어제 키 — 오늘 카운터에 영향 없음
        assertFailsWith<DeepResearchLimitException> { limiter.tick("2026-07-10") }
    }

    @Test
    fun `빈 카운터에 release해도 음수로 안 내려간다`() {
        val limiter = DailyLimiter(1)
        limiter.release("2026-07-10")
        limiter.release("2026-07-10")
        limiter.tick("2026-07-10")
        assertFailsWith<DeepResearchLimitException> { limiter.tick("2026-07-10") }
    }

    // ── 캐시 키 ───────────────────────────────────────────────────

    @Test
    fun `캐시 키는 코드와 날짜만 - 전 유저 공유`() {
        assertEquals("005930:2026-07-10", DeepResearchService.buildKey("005930", "2026-07-10"))
    }

    // ── 출처 정리 ─────────────────────────────────────────────────

    @Test
    fun `출처 URL 중복 제거 - 등장 순 유지`() {
        val sources = listOf(
            ResearchSource("기사A", "https://a.com/1"),
            ResearchSource("기사B", "https://b.com/2"),
            ResearchSource("기사A 재인용", "https://a.com/1"),
        )
        val deduped = DeepResearchService.dedupeSources(sources)
        assertEquals(2, deduped.size)
        assertEquals("기사A", deduped[0].title)   // 첫 등장 제목 유지
        assertEquals("https://b.com/2", deduped[1].url)
    }

    @Test
    fun `출처 상한 적용`() {
        val many = (1..20).map { ResearchSource("기사$it", "https://x.com/$it") }
        assertEquals(10, DeepResearchService.dedupeSources(many).size)
    }

    // ── 2단계 입력 형식 ───────────────────────────────────────────

    @Test
    fun `2단계 입력은 계층 라벨 순서 고정`() {
        val input = DeepResearchService.renderStage2Input("현재가: 100원", "- [뉴스, 7/9] 수주 보도")
        val idx1 = input.indexOf("[1층: 사실 데이터")
        val idx2 = input.indexOf("[2층: 웹 리서치 노트")
        assertTrue(idx1 in 0 until idx2, "1층 라벨이 2층보다 먼저")
        assertTrue(input.indexOf("현재가: 100원") in idx1..idx2, "facts는 1층 구간에")
        assertTrue(input.indexOf("수주 보도") > idx2, "노트는 2층 구간에")
    }

    @Test
    fun `과대한 노트는 절단된다`() {
        val hugeNotes = "가".repeat(10_000)
        val input = DeepResearchService.renderStage2Input("facts", hugeNotes)
        // 노트 4000자 컷 + 라벨/facts 여유분
        assertTrue(input.length < 4300, "입력 전체가 상한 근처로 제한: ${input.length}")
    }
}
