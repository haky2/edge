package com.haky.edge

import com.haky.edge.ai.AnalogService
import com.haky.edge.ai.DailyHistoryService
import com.haky.edge.kis.DailyBar
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F1 슬라이스 1a(이력 병합)+1b(유사 국면 계산) 순수 함수 테스트. */
class AnalogTest {

    // ── 합성 일봉 생성(오래된 순 날짜, 최신이 앞 반환 — KIS 응답 규약) ──────────

    private fun bar(i: Int, close: Long, volume: Long = 1_000) = DailyBar(
        date = "%08d".format(20200101 + i), // 유일·단조증가면 충분(실제 달력 아님)
        open = close, high = (close * 1.02).toLong(), low = (close * 0.98).toLong(),
        close = close, volume = volume,
    )

    /** 시드 랜덤워크 n일(최신이 앞). */
    private fun randomWalk(n: Int, seed: Int = 42): List<DailyBar> {
        val rnd = Random(seed)
        var price = 50_000.0
        return (0 until n).map { i ->
            price *= 1.0 + (rnd.nextDouble() - 0.49) * 0.03
            bar(i, price.toLong(), volume = (800 + rnd.nextInt(500)).toLong())
        }.reversed()
    }

    // ── 1a: mergeHistories ─────────────────────────────────────────────

    @Test
    fun `정상 병합 - 겹침 뒤 과거분만 이어붙임`() {
        val cached = listOf(bar(5, 105), bar(4, 104), bar(3, 103), bar(2, 102), bar(1, 101))
        val fresh = listOf(bar(7, 107), bar(6, 106), bar(5, 105), bar(4, 104))
        val merged = DailyHistoryService.mergeHistories(fresh, cached)
        assertNotNull(merged)
        assertEquals(listOf(7, 6, 5, 4, 3, 2, 1).map { "%08d".format(20200101 + it) }, merged.map { it.date })
    }

    @Test
    fun `겹치는 날짜 없으면 null - 캐시 공백`() {
        val cached = listOf(bar(2, 102), bar(1, 101))
        val fresh = listOf(bar(9, 109), bar(8, 108))
        assertNull(DailyHistoryService.mergeHistories(fresh, cached))
    }

    @Test
    fun `겹치는 날짜 종가 불일치면 null - 수정주가 재계산`() {
        val cached = listOf(bar(5, 105), bar(4, 104))
        val fresh = listOf(bar(6, 106), bar(5, 210)) // 액면분할 등으로 과거가 재계산됨
        assertNull(DailyHistoryService.mergeHistories(fresh, cached))
    }

    @Test
    fun `빈 캐시는 fresh 그대로, 빈 fresh는 캐시 그대로`() {
        val bars = listOf(bar(1, 101))
        assertEquals(bars, DailyHistoryService.mergeHistories(bars, emptyList()))
        assertEquals(bars, DailyHistoryService.mergeHistories(emptyList(), bars))
        assertNull(DailyHistoryService.mergeHistories(emptyList(), emptyList()))
    }

    // ── 1b: RSI(Wilder) — sharedLogic TechnicalIndicators.rsi 와 값 일치 ──

    /** sharedLogic 산식 사본(최신이 앞 입력) — 백엔드 구현과의 드리프트 가드. */
    private fun rsiSharedLogicRef(closesDesc: List<Double>, n: Int): Double? {
        if (closesDesc.size < n + 1) return null
        val reversed = closesDesc.reversed()
        val changes = (1 until reversed.size).map { reversed[it] - reversed[it - 1] }
        if (changes.size < n) return null
        var avgGain = changes.take(n).filter { it > 0 }.average().takeIf { changes.take(n).any { c -> c > 0 } } ?: 0.0
        var avgLoss = changes.take(n).filter { it < 0 }.map { -it }.average().takeIf { changes.take(n).any { c -> c < 0 } } ?: 0.0
        for (i in n until changes.size) {
            val gain = if (changes[i] > 0) changes[i] else 0.0
            val loss = if (changes[i] < 0) -changes[i] else 0.0
            avgGain = (avgGain * (n - 1) + gain) / n
            avgLoss = (avgLoss * (n - 1) + loss) / n
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    @Test
    fun `rsiWilder는 sharedLogic 산식과 값 일치`() {
        val rnd = Random(7)
        var p = 100.0
        val asc = (0 until 80).map { p *= 1.0 + (rnd.nextDouble() - 0.5) * 0.04; p }
        val backend = AnalogService.rsiWilder(asc, 14)!!
        val ref = rsiSharedLogicRef(asc.reversed(), 14)!!
        assertTrue(abs(backend - ref) < 1e-9, "backend=$backend ref=$ref")
    }

    @Test
    fun `rsiWilder 전부 상승이면 100, 이력 부족이면 null`() {
        assertEquals(100.0, AnalogService.rsiWilder((1..30).map { it.toDouble() }, 14))
        assertNull(AnalogService.rsiWilder((1..10).map { it.toDouble() }, 14))
    }

    // ── 1b: vectorAt ───────────────────────────────────────────────────

    @Test
    fun `vectorAt 이력 부족이면 null, 충분하면 값 반환`() {
        val bars = randomWalk(600)
        val asc = bars.reversed()
        val closes = asc.map { it.close.toDouble() }
        assertNull(AnalogService.vectorAt(asc, closes, 250))   // MIN_HISTORY-1=251 미만
        val v = AnalogService.vectorAt(asc, closes, 251)
        assertNotNull(v)
        assertTrue(v.pos52w in 0.0..100.0)
        assertTrue(v.rsi14 in 0.0..100.0)
        assertTrue(v.volumeRatio > 0)
    }

    @Test
    fun `vectorAt은 look-ahead 없이 해당 시점까지만 사용`() {
        val bars = randomWalk(600)
        val asc = bars.reversed()
        val closes = asc.map { it.close.toDouble() }
        val v1 = AnalogService.vectorAt(asc, closes, 300)
        // 미래 구간을 잘라내도 같은 값이어야 함
        val truncated = asc.subList(0, 301)
        val v2 = AnalogService.vectorAt(truncated, closes.subList(0, 301), 300)
        assertEquals(v1, v2)
    }

    // ── 1b: compute (end-to-end 순수 계산) ──────────────────────────────

    @Test
    fun `compute 정상 - n·horizons·클러스터 간격`() {
        val report = AnalogService.compute("005930", "삼성전자", "2026-07-04", randomWalk(600))
        assertTrue(report.n > 0, "n=${report.n}")
        assertTrue(report.n <= AnalogService.K_NEAREST)
        assertEquals(listOf(5, 20, 60), report.horizons.map { it.days })
        report.horizons.forEach {
            assertTrue(it.winRate in 0.0..100.0)
            assertTrue(it.min <= it.median && it.median <= it.max)
        }
        // 채택 유사일은 ±CLUSTER_GAP 이내 중복 없음(날짜=인덱스 단조 매핑이라 날짜 차이로 검증 가능)
        val idxs = report.matchedDates.map { it.toInt() - 20200101 }.sorted()
        idxs.zipWithNext().forEach { (a, b) -> assertTrue(b - a > AnalogService.CLUSTER_GAP, "cluster gap 위반: $a,$b") }
        assertTrue(report.caveat.contains("과거 분포"))
    }

    @Test
    fun `compute 이력 부족이면 n=0 + caveat`() {
        val report = AnalogService.compute("005930", "삼성전자", "2026-07-04", randomWalk(200))
        assertEquals(0, report.n)
        assertTrue(report.horizons.isEmpty())
        assertTrue(report.caveat.contains("이력이 부족"))
    }

    @Test
    fun `compute 유사일 forward return은 60일 확정분만 - 최근 60일 제외`() {
        val report = AnalogService.compute("005930", "삼성전자", "2026-07-04", randomWalk(600))
        val todayIdx = 599
        report.matchedDates.forEach { d ->
            val idx = d.toInt() - 20200101
            assertTrue(idx <= todayIdx - 61, "미확정 유사일 포함: idx=$idx")
        }
    }

    @Test
    fun `compute 분산 0 피처가 있어도 죽지 않음 - 거래량 상수`() {
        val rnd = Random(3)
        var p = 50_000.0
        val bars = (0 until 600).map { i ->
            p *= 1.0 + (rnd.nextDouble() - 0.49) * 0.03
            bar(i, p.toLong(), volume = 1_000) // 거래량 고정 → volumeRatio 항상 1.0(분산 0)
        }.reversed()
        val report = AnalogService.compute("005930", "삼성전자", "2026-07-04", bars)
        assertTrue(report.n > 0)
    }

    @Test
    fun `compute n 15 미만이면 참고 수준 문구`() {
        // 이력을 정확히 최소 요건 근처로 줄여 후보 자체를 희소하게 만든다
        val report = AnalogService.compute("005930", "삼성전자", "2026-07-04", randomWalk(320))
        if (report.n in 1..14) assertTrue(report.caveat.contains("참고 수준"))
    }

    @Test
    fun `median 홀짝`() {
        assertEquals(2.0, AnalogService.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, AnalogService.median(listOf(1.0, 2.0, 3.0, 4.0)))
    }
}
