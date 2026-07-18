package com.haky.edge

import com.haky.edge.kis.DailyBar
import com.haky.edge.lab.BacktestEngine
import com.haky.edge.lab.BacktestEngine.BenchSeries
import com.haky.edge.lab.BacktestEngine.Dedupe
import com.haky.edge.lab.BacktestEngine.SignalDef
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 전략 실험실 엔진 — 순수 함수(replay·aggregate·judge) 검증.
 * 결정론 합성 계열로 forward 수익률·초과수익·dedupe·대조군·판정 규칙을 확인한다.
 */
class BacktestEngineTest {

    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    private val start = LocalDate.parse("2024-01-01")

    private fun date(i: Int) = start.plusDays(i.toLong()).format(ymd)

    private fun bars(closes: List<Double>): List<DailyBar> =
        closes.mapIndexed { i, c ->
            val l = Math.round(c)
            DailyBar(date(i), l, l, l, l, 100)
        }

    private fun bench(closes: List<Double>, skipDates: Set<String> = emptySet()): BenchSeries {
        val pairs = closes.mapIndexed { i, c -> date(i) to c }.filter { it.first !in skipDates }
        return BenchSeries(pairs.map { it.first }, pairs.map { it.second })
    }

    private fun geo(n: Int, dailyPct: Double, base: Double = 100_000.0): List<Double> =
        (0 until n).map { base * (1 + dailyPct / 100).pow(it) }

    private val never = SignalDef("없음", warmupBars = 5) { false }

    @Test
    fun `baseline forward 수익률 - 매일 1% 상승, 벤치 평평이면 excess=raw`() {
        val closes = geo(60, 1.0)
        val r = BacktestEngine.replay("A", bars(closes), bench(List(60) { 2500.0 }),
            listOf(never), benchLookback = 0)
        // t ∈ [5, 39] — warmup 5, forward 20 확보
        assertEquals(35, r.evalDays)
        val base = r.firings.getValue(BacktestEngine.BASELINE)
        assertEquals(35, base.size)
        val expected20 = ((1.01).pow(20) - 1) * 100
        for (f in base) {
            assertEquals(expected20, f.raw.getValue(20), 0.01)
            assertEquals(expected20, f.excess.getValue(20), 0.01)  // 벤치 수익 0
        }
    }

    @Test
    fun `초과수익 - 종목 1% vs 벤치 0_5%`() {
        val r = BacktestEngine.replay("A", bars(geo(60, 1.0)), bench(geo(60, 0.5, base = 2500.0)),
            listOf(never), benchLookback = 0)
        val f = r.firings.getValue(BacktestEngine.BASELINE).first()
        val expected = ((1.01).pow(20) - (1.005).pow(20)) * 100
        assertEquals(expected, f.excess.getValue(20), 0.01)
    }

    @Test
    fun `클러스터 dedupe - 연속 발화는 첫 발화만, 갭 초과는 별건`() {
        val closes = geo(60, 0.1)
        val sig = SignalDef("연속", warmupBars = 5) { it.t in 10..14 || it.t == 21 }
        val r = BacktestEngine.replay("A", bars(closes), bench(List(60) { 2500.0 }),
            listOf(sig), benchLookback = 0)
        // t=10 채택, 11~14는 갭 5 이내 스킵, t=21은 갭 11 > 5 → 채택
        assertEquals(listOf(10, 21), r.firings.getValue("연속").map { it.t })
    }

    @Test
    fun `dedupe NONE - 연속 발화 전부 채택`() {
        val sig = SignalDef("전부", warmupBars = 5, dedupe = Dedupe.NONE) { it.t in 10..12 }
        val r = BacktestEngine.replay("A", bars(geo(60, 0.1)), bench(List(60) { 2500.0 }),
            listOf(sig), benchLookback = 0)
        assertEquals(3, r.firings.getValue("전부").size)
    }

    @Test
    fun `대조군 자동 채점 - 급락일과 급등일`() {
        val closes = geo(60, 0.1).toMutableList()
        closes[20] = closes[19] * 0.97   // -3% 급락일
        for (i in 21 until 60) closes[i] = closes[20] * (1.001).pow(i - 20)
        val r = BacktestEngine.replay("A", bars(closes), bench(List(60) { 2500.0 }),
            listOf(never), benchLookback = 0)
        val down = r.firings.getValue(BacktestEngine.CTL_DOWN)
        assertEquals(listOf(20), down.map { it.t })
    }

    @Test
    fun `벤치 조인 실패 - 결측일은 건너뛰고 카운트`() {
        val closes = geo(60, 1.0)
        val missing = date(15)
        val r = BacktestEngine.replay("A", bars(closes), bench(List(60) { 2500.0 }, skipDates = setOf(missing)),
            listOf(never), benchLookback = 0)
        assertEquals(1, r.joinFailures)
        assertEquals(34, r.evalDays)   // 35 - 결측 1일
    }

    @Test
    fun `lookahead 구조 차단 - 미래 접근은 예외`() {
        val sig = SignalDef("치팅", warmupBars = 5) { it.close(-1) > it.close(0) }
        assertFailsWith<IllegalArgumentException> {
            BacktestEngine.replay("A", bars(geo(60, 1.0)), bench(List(60) { 2500.0 }),
                listOf(sig), benchLookback = 0)
        }
    }

    @Test
    fun `데이터 부족 NaN - 발화하지 않는다`() {
        // ret(100)은 60봉 계열에서 항상 NaN → NaN >= 0 비교는 false → 발화 0
        val sig = SignalDef("부족", warmupBars = 5) { it.ret(100) >= 0.0 }
        val r = BacktestEngine.replay("A", bars(geo(60, 1.0)), bench(List(60) { 2500.0 }),
            listOf(sig), benchLookback = 0)
        assertTrue(r.firings["부족"].isNullOrEmpty())
    }

    @Test
    fun `같은 날 복수 종목 발화 - distinctDates로 가시화`() {
        val sig = SignalDef("공통", warmupBars = 5) { it.t == 10 }
        val benchS = bench(List(60) { 2500.0 })
        val rA = BacktestEngine.replay("A", bars(geo(60, 1.0)), benchS, listOf(sig), benchLookback = 0)
        val rB = BacktestEngine.replay("B", bars(geo(60, 0.5)), benchS, listOf(sig), benchLookback = 0)
        val pooled = mapOf("공통" to (rA.firings.getValue("공통") + rB.firings.getValue("공통")))
        val bucket = BacktestEngine.aggregate(pooled).first { it.days == 20 }
        assertEquals(2, bucket.n)
        assertEquals(1, bucket.distinctDates)
    }

    // ── 판정 규칙 ─────────────────────────────────────────────────────────────

    private fun bucket(label: String, avgEx: Double, win: Double, n: Int = 100) =
        BacktestEngine.LabBucket(label, 20, n, n, avgEx, avgEx, avgEx, win, n < 15)

    private val baseBucket = bucket(BacktestEngine.BASELINE, 0.0, 50.0)

    @Test
    fun `판정 - 지지·혼재·반증·표본부족`() {
        val signals = listOf(
            SignalDef("좋음", 5) { true },
            SignalDef("섞임", 5) { true },
            SignalDef("나쁨", 5) { true },
            SignalDef("적음", 5) { true },
        )
        val buckets = listOf(
            baseBucket,
            bucket("좋음", 1.0, 60.0),
            bucket("섞임", 1.0, 45.0),   // 평균은 이기고 승률은 짐
            bucket("나쁨", -1.0, 40.0),
            bucket("적음", 5.0, 90.0, n = 10),
        )
        val v = BacktestEngine.judge(buckets, signals).associate { it.label to it.verdict }
        assertEquals("지지", v["좋음"])
        assertEquals("혼재", v["섞임"])
        assertEquals("반증", v["나쁨"])
        assertEquals("표본부족", v["적음"])
    }

    @Test
    fun `판정 - 대조군미달, baseline은 이겨도 방향 대조군에 지면 고유 신호 없음`() {
        val signals = listOf(
            SignalDef("반등류", 5, controlLabel = BacktestEngine.CTL_DOWN) { true },
        )
        val buckets = listOf(
            baseBucket,
            bucket(BacktestEngine.CTL_DOWN, 2.0, 70.0),  // 단순 하락일 평균회귀가 더 강함
            bucket("반등류", 1.0, 60.0),
        )
        val v = BacktestEngine.judge(buckets, signals).single()
        assertEquals("대조군미달", v.verdict)
    }

    @Test
    fun `판정 - 대조군까지 이기면 지지`() {
        val signals = listOf(
            SignalDef("진짜", 5, controlLabel = BacktestEngine.CTL_DOWN) { true },
        )
        val buckets = listOf(
            baseBucket,
            bucket(BacktestEngine.CTL_DOWN, 0.5, 55.0),
            bucket("진짜", 2.0, 65.0),
        )
        val v = BacktestEngine.judge(buckets, signals).single()
        assertEquals("지지", v.verdict)
        assertTrue(v.reason.contains("대조군도 상회"))
    }

    @Test
    fun `집계 - 중앙값과 승률`() {
        assertEquals(2.0, BacktestEngine.median(listOf(1.0, 2.0, 3.0)), 1e-9)
        assertEquals(1.5, BacktestEngine.median(listOf(1.0, 2.0)), 1e-9)
        assertEquals(0.0, BacktestEngine.median(emptyList()), 1e-9)
    }
}
