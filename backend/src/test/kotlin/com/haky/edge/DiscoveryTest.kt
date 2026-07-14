package com.haky.edge

import com.haky.edge.ai.DiscoveryCandidate
import com.haky.edge.ai.DiscoveryService
import com.haky.edge.ai.DiscoverySignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 후보 발굴(D1) 순수 함수 검증 — 52주 위치·수익률·신호 3종·소음 컷. */
class DiscoveryTest {

    // ── pos52w / retPct ────────────────────────────────────────────────────

    @Test
    fun `52주 위치 - 저점 0, 고점 100, 중간 비례`() {
        assertEquals(0.0, DiscoveryService.pos52w(100, 200, 100)!!, 1e-9)
        assertEquals(100.0, DiscoveryService.pos52w(200, 200, 100)!!, 1e-9)
        assertEquals(50.0, DiscoveryService.pos52w(150, 200, 100)!!, 1e-9)
    }

    @Test
    fun `52주 위치 - 고저 무의미하거나 가격 0이면 null`() {
        assertNull(DiscoveryService.pos52w(100, 100, 100)) // 고=저
        assertNull(DiscoveryService.pos52w(100, 90, 100))  // 고<저
        assertNull(DiscoveryService.pos52w(0, 200, 100))
    }

    @Test
    fun `수익률 - 최신이 앞 리스트에서 N일 전 대비`() {
        val closes = listOf(110.0, 108.0, 105.0, 100.0) // 오늘=110, 3일 전=100
        assertEquals(10.0, DiscoveryService.retPct(closes, 3)!!, 1e-9)
    }

    @Test
    fun `수익률 - 이력 부족이거나 기준가 0이면 null`() {
        assertNull(DiscoveryService.retPct(listOf(110.0, 100.0), 5))
        assertNull(DiscoveryService.retPct(listOf(110.0, 0.0), 1))
        assertNull(DiscoveryService.retPct(emptyList(), 1))
    }

    // ── evaluateSignals ────────────────────────────────────────────────────

    /** 5일 연속 순매도 후 오늘 순매수 = F4 전환 조건 충족 리스트(최신이 앞). */
    private val buyReversal = listOf(100L, -1L, -2L, -3L, -4L, -5L)
    private val flat = listOf(0L, 0L, 0L, 0L, 0L, 0L)

    @Test
    fun `신호 - 수급전환은 매수 전환만, 외인·기관 각각`() {
        val signals = DiscoveryService.evaluateSignals(
            pos52w = 50.0, ret20 = 0.0, ret5 = 0.0, benchRet20 = 0.0,
            foreignNet = buyReversal, instNet = buyReversal,
        )
        assertEquals(2, signals.count { it.type == "수급전환" })
        // 매도 전환(연속 순매수 후 첫 순매도)은 발굴 신호가 아님
        val sellReversal = listOf(-100L, 1L, 2L, 3L, 4L, 5L)
        val none = DiscoveryService.evaluateSignals(50.0, 0.0, 0.0, 0.0, sellReversal, flat)
        assertEquals(0, none.count { it.type == "수급전환" })
    }

    @Test
    fun `신호 - 상대모멘텀은 코스피 대비 +5%p 이상`() {
        fun momentum(ret20: Double?, bench: Double?) = DiscoveryService
            .evaluateSignals(50.0, ret20, 0.0, bench, flat, flat)
            .any { it.type == "상대모멘텀" }
        assertTrue(momentum(8.0, 3.0))    // +5.0%p 경계 포함
        assertTrue(!momentum(7.9, 3.0))   // +4.9%p
        assertTrue(!momentum(null, 3.0))  // 이력 부족 → 생략
        assertTrue(!momentum(8.0, null))  // 벤치마크 실패 → 생략
    }

    @Test
    fun `신호 - 저점반등(30 미만 + 5일 +5%)`() {
        val rebound = DiscoveryService.evaluateSignals(25.0, null, 6.0, null, flat, flat)
        assertEquals(listOf("저점반등"), rebound.map { it.type })

        // 저점권이어도 반등이 없으면 무신호 / 중간권 반등도 무신호
        assertTrue(DiscoveryService.evaluateSignals(25.0, null, 2.0, null, flat, flat).isEmpty())
        assertTrue(DiscoveryService.evaluateSignals(50.0, null, 9.0, null, flat, flat).isEmpty())
    }

    @Test
    fun `신호 - 신고가근접은 실측 반증으로 제거(②-2b 교정 방향 고정)`() {
        // 52주 위치 ≥90%는 20일 코스피 대비 초과수익 -1.32%p·승률 -4.0%p(n=642, baseline 열위)로
        // 발굴 신호에서 제거 — 재도입하려면 discovery-validation 재실측으로 근거를 갱신할 것.
        val high = DiscoveryService.evaluateSignals(92.0, null, null, null, flat, flat)
        assertTrue(high.none { it.type == "신고가근접" })
    }

    @Test
    fun `신호 - null 입력은 해당 신호만 생략(부분 평가)`() {
        val signals = DiscoveryService.evaluateSignals(
            pos52w = null, ret20 = null, ret5 = null, benchRet20 = null,
            foreignNet = buyReversal, instNet = flat,
        )
        assertEquals(listOf("수급전환"), signals.map { it.type })
    }

    // ── selectCandidates ───────────────────────────────────────────────────

    private fun cand(code: String, nSignals: Int, changeRate: Double = 0.0) = DiscoveryCandidate(
        code = code, name = code, sector = "테스트", price = 1000, changeRate = changeRate,
        signals = List(nSignals) { DiscoverySignal("신호$it", "d") },
    )

    @Test
    fun `소음 컷 - 신호 2개 미만 제외, 신호 수·등락률 정렬, 최대 5종목`() {
        val all = listOf(
            cand("A", 1), cand("B", 2, changeRate = 1.0), cand("C", 3),
            cand("D", 2, changeRate = 5.0), cand("E", 2), cand("F", 2), cand("G", 2, changeRate = -1.0),
        )
        val picked = DiscoveryService.selectCandidates(all)
        assertEquals(5, picked.size)
        assertEquals("C", picked[0].code)                    // 신호 3개 최우선
        assertEquals("D", picked[1].code)                    // 신호 2개 중 등락률 상위
        assertTrue(picked.none { it.code == "A" })           // 1개는 컷
    }

    @Test
    fun `소음 컷 - 전부 1개 이하면 후보 없음(억지로 채우지 않음)`() {
        assertTrue(DiscoveryService.selectCandidates(listOf(cand("A", 1), cand("B", 0))).isEmpty())
    }
}
