package com.haky.edge

import com.haky.edge.ai.DiscoveryValidationService
import com.haky.edge.ai.DiscoveryValidationService.Companion.Firing
import com.haky.edge.ai.DiscoveryValidationService.Companion.KospiSeries
import com.haky.edge.kis.DailyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ②-2b Discovery 신호 실증 — 발화 수집·클러스터 dedupe·버킷 채점 순수 함수. */
class DiscoveryValidationTest {

    /** 종목과 같은 날짜 축의 코스피 시리즈(모든 날 존재, 값은 상수 → 초과수익 = 원수익률). */
    private fun flatKospi(dates: List<String>) = KospiSeries(dates, dates.map { 1000.0 })

    private fun bars(n: Int, close: (Int) -> Long): List<DailyBar> = (0 until n).map { i ->
        val c = close(i)
        DailyBar("%08d".format(20230101 + i), c, c, c, c, 100_000)
    }

    @Test
    fun `collectFirings - 코스피 상수면 excess == raw, t 범위는 252~n-21`() {
        val asc = bars(300) { 100_000L + it * 100 }
        val collected = DiscoveryValidationService.collectFirings(asc, flatKospi(asc.map { it.date }))
        assertEquals(0, collected.joinFailures)
        val ts = collected.firings.map { it.t }
        assertEquals(252, ts.min())
        assertEquals(300 - 1 - 20, ts.max())
        for (f in collected.firings) {
            assertEquals(f.raw.getValue(5), f.excess.getValue(5), 1e-9)
            assertEquals(f.raw.getValue(20), f.excess.getValue(20), 1e-9)
        }
    }

    @Test
    fun `collectFirings - 꾸준한 상승 종목은 신고가근접+상대모멘텀 발화, 교집합은 미발화(신호 제거 후 라이브 컷)`() {
        // 일 +0.5% 복리 상승 → 52주 위치 최상단, 20일 수익률 ~10.5% vs 코스피 0%
        val asc = bars(300) { (100_000 * Math.pow(1.005, it.toDouble())).toLong() }
        val collected = DiscoveryValidationService.collectFirings(asc, flatKospi(asc.map { it.date }))
        val f = collected.firings.first()
        assertTrue("신고가근접" in f.labels)        // 재실측용 단독 버킷은 계속 측정(제거된 신호의 추적)
        assertTrue("상대모멘텀(+5p)" in f.labels)
        assertTrue("상대모멘텀(+3p)" in f.labels)
        // ②-2b 교정 후 라이브 경로(evaluateSignals)엔 신고가근접이 없어 상대모멘텀 1개뿐 → 교집합 미달
        assertTrue("교집합(2신호)" !in f.labels)
    }

    @Test
    fun `collectFirings - 코스피에 없는 날짜는 joinFailures로 제외`() {
        val asc = bars(300) { 100_000L }
        val dates = asc.map { it.date }.filterIndexed { i, _ -> i != 260 } // 260일차 결측
        val collected = DiscoveryValidationService.collectFirings(asc, flatKospi(dates))
        assertEquals(1, collected.joinFailures)
        assertTrue(collected.firings.none { it.t == 260 })
    }

    @Test
    fun `dedupeByLabel - 5거래일 이내 재발화는 첫 발화만, 간격 초과는 채택`() {
        fun firing(t: Int, labels: Set<String>) = Firing(
            t = t, date = "2024%04d".format(t), labels = labels,
            raw = mapOf(5 to 1.0, 20 to 1.0), excess = mapOf(5 to 1.0, 20 to 1.0),
        )
        val firings = listOf(
            firing(252, setOf("신고가근접")),
            firing(255, setOf("신고가근접")),   // +3 — 클러스터 내 스킵
            firing(258, setOf("신고가근접")),   // 채택된 252 대비 +6 — 채택
            firing(259, setOf("저점반등")),     // 다른 라벨 — 독립 채택
        )
        val deduped = DiscoveryValidationService.dedupeByLabel(firings)
        assertEquals(listOf(252, 258), deduped.getValue("신고가근접").map { it.t })
        assertEquals(listOf(259), deduped.getValue("저점반등").map { it.t })
    }

    @Test
    fun `bucketsOf - 초과수익 평균·중앙값·승률, n15 미만 침묵`() {
        fun firing(t: Int, ex5: Double) = Firing(
            t = t, date = "d$t", labels = setOf("x"),
            raw = mapOf(5 to ex5, 20 to ex5), excess = mapOf(5 to ex5, 20 to ex5),
        )
        val firings = (1..20).map { firing(it * 10, if (it <= 15) 2.0 else -1.0) }
        val b5 = DiscoveryValidationService.bucketsOf("x", firings).first { it.days == 5 }
        assertEquals(20, b5.n)
        assertEquals((15 * 2.0 - 5) / 20, b5.avgExcessPct, 0.01)
        assertEquals(2.0, b5.medianExcessPct)
        assertEquals(75.0, b5.winExcessPct)
        assertTrue(!b5.silenced)

        val tiny = DiscoveryValidationService.bucketsOf("y", firings.take(3)).first()
        assertTrue(tiny.silenced)
    }
}
