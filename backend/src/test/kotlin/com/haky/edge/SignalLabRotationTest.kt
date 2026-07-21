package com.haky.edge

import com.haky.edge.kis.IndexPoint
import com.haky.edge.lab.BacktestEngine
import com.haky.edge.lab.SignalLabService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** X5: 섹터 순환 수트 — 크로스섹션 리플레이·판정·Spearman 순수 함수. */
class SignalLabRotationTest {

    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    private val start = LocalDate.parse("2026-01-01")

    /** n일 연속 날짜열(달력일 — 문자열 비교만 쓰므로 무방). */
    private fun dates(n: Int): List<String> = (0 until n).map { start.plusDays(it.toLong()).format(ymd) }

    private fun series(n: Int, closeAt: (Int) -> Double): List<IndexPoint> =
        dates(n).mapIndexed { i, d -> IndexPoint(d, closeAt(i)) }

    private fun benchFlat(n: Int) = BacktestEngine.BenchSeries(dates(n), List(n) { 1000.0 })

    // ── replayRotation ──────────────────────────────────────────────────

    @Test
    fun `baseline은 전 업종-날짜 셀, 워밍업 21봉과 forward 20봉을 제외한 창에서만 평가`() {
        val n = 60
        val flat = mapOf(
            "A" to series(n) { 100.0 }, "B" to series(n) { 200.0 },
            "C" to series(n) { 300.0 },
        )
        val r = SignalLabService.replayRotation(flat, benchFlat(n))
        // 평가일 = t ∈ [20, n-1-20] = [20, 39] → 20일 × 3업종 = 60셀
        assertEquals(20, r.evalDays)
        assertEquals(60, r.firings[BacktestEngine.BASELINE]!!.size)
        // 전부 보합 → 순위 동률(안정 정렬) → rankDelta 0 → 신호 없음
        assertTrue(SignalLabService.ROT_INFLOW !in r.firings)
        assertTrue(SignalLabService.ROT_OUTFLOW !in r.firings)
    }

    /** A: i<40 일 −0.5% 하락(20일 창 최하위) → i≥40 일 +2% 급등(5일 창 1위) = 순위 급상승. */
    private fun accelSeries(n: Int): List<IndexPoint> = series(n) { i ->
        if (i < 40) 100.0 * Math.pow(0.995, i.toDouble())
        else 100.0 * Math.pow(0.995, 39.0) * Math.pow(1.02, (i - 39).toDouble())
    }

    @Test
    fun `단기 가속 업종은 유입 발화하고 forward 초과수익이 기록된다`() {
        val n = 70
        // A: 하락하다 t=40부터 급등 — 5일 창 1위로 뛰지만 20일 창은 하락분이 지배해 최하위
        // → rankDelta ≥ 2 & ret5 > ret20 = 유입. B·C·D: 완만 하락(고정). 벤치 보합 → excess = raw.
        val hist = mapOf(
            "A" to accelSeries(n),
            "B" to series(n) { i -> 200.0 - i * 0.1 },
            "C" to series(n) { i -> 300.0 - i * 0.2 },
            "D" to series(n) { i -> 400.0 - i * 0.3 },
        )
        val r = SignalLabService.replayRotation(hist, benchFlat(n))
        val inflow = r.firings[SignalLabService.ROT_INFLOW]
        assertTrue(inflow != null && inflow.isNotEmpty(), "가속 구간에서 유입 발화 필요")
        assertTrue(inflow!!.all { it.code == "A" }, "유입 판정은 가속 업종 A여야 함: ${inflow.map { it.code }.distinct()}")
        assertTrue(inflow.all { it.t >= 40 }, "급등 시작 전 발화는 lookahead 의심: ${inflow.map { it.t }}")
        // A는 이후에도 계속 +2%/일 → forward 20일 초과수익 양수
        assertTrue(inflow.all { it.excess.getValue(20) > 0 })
    }

    @Test
    fun `연속 발화는 업종별 클러스터 dedupe - 5거래일 내 재발화 제외`() {
        val n = 70
        val hist = mapOf(
            "A" to accelSeries(n),
            "B" to series(n) { i -> 200.0 - i * 0.1 },
            "C" to series(n) { i -> 300.0 - i * 0.2 },
        )
        val r = SignalLabService.replayRotation(hist, benchFlat(n))
        val inflowTs = r.firings[SignalLabService.ROT_INFLOW].orEmpty().filter { it.code == "A" }.map { it.t }
        assertTrue(inflowTs.isNotEmpty())
        inflowTs.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a > BacktestEngine.CLUSTER_GAP, "dedupe 위반: t=$a → t=$b")
        }
    }

    @Test
    fun `업종 간 날짜가 어긋나면 교집합 달력으로 정렬된다`() {
        val n = 60
        val a = series(n) { 100.0 }
        val bMissing = series(n) { 200.0 }.filterIndexed { i, _ -> i != 30 } // B는 하루 결측
        val r = SignalLabService.replayRotation(mapOf("A" to a, "B" to bMissing), benchFlat(n))
        // 교집합 59일 → 평가일 = 59 - 20 - 20 = 19일 × 2업종
        assertEquals(19, r.evalDays)
        assertEquals(38, r.firings[BacktestEngine.BASELINE]!!.size)
    }

    @Test
    fun `벤치에 없는 날짜는 조인 실패로 제외`() {
        val n = 60
        val hist = mapOf("A" to series(n) { 100.0 }, "B" to series(n) { 200.0 })
        // 벤치가 평가창 중 하루(t=25) 결측
        val benchDates = dates(n).filterIndexed { i, _ -> i != 25 }
        val bench = BacktestEngine.BenchSeries(benchDates, List(benchDates.size) { 1000.0 })
        val r = SignalLabService.replayRotation(hist, bench)
        assertEquals(1, r.joinFailures)
        assertEquals(19, r.evalDays)
    }

    // ── judgeRotation ──────────────────────────────────────────────────

    private fun bucket(label: String, avg: Double, win: Double, n: Int = 100) =
        BacktestEngine.LabBucket(label, 20, n, n, 0.0, avg, avg, win, silenced = n < BacktestEngine.MIN_BUCKET_N)

    @Test
    fun `유입은 baseline 상회가 지지, 이탈은 하회가 지지`() {
        val buckets = listOf(
            bucket(BacktestEngine.BASELINE, avg = 0.0, win = 50.0),
            bucket(SignalLabService.ROT_INFLOW, avg = 1.5, win = 58.0),
            bucket(SignalLabService.ROT_OUTFLOW, avg = -1.2, win = 42.0),
        )
        val v = SignalLabService.judgeRotation(buckets).associateBy { it.label }
        assertEquals("지지", v[SignalLabService.ROT_INFLOW]!!.verdict)
        assertEquals("지지", v[SignalLabService.ROT_OUTFLOW]!!.verdict)
    }

    @Test
    fun `유입이 baseline 둘 다 하회면 반증, 이탈이 둘 다 상회면 반증`() {
        val buckets = listOf(
            bucket(BacktestEngine.BASELINE, avg = 0.0, win = 50.0),
            bucket(SignalLabService.ROT_INFLOW, avg = -0.8, win = 45.0),
            bucket(SignalLabService.ROT_OUTFLOW, avg = 0.9, win = 55.0),
        )
        val v = SignalLabService.judgeRotation(buckets).associateBy { it.label }
        assertEquals("반증", v[SignalLabService.ROT_INFLOW]!!.verdict)
        assertEquals("반증", v[SignalLabService.ROT_OUTFLOW]!!.verdict)
    }

    @Test
    fun `평균과 승률이 엇갈리면 혼재, n 미달이면 표본부족`() {
        val buckets = listOf(
            bucket(BacktestEngine.BASELINE, avg = 0.0, win = 50.0),
            bucket(SignalLabService.ROT_INFLOW, avg = 1.0, win = 48.0),           // 평균만 상회
            bucket(SignalLabService.ROT_OUTFLOW, avg = -1.0, win = 40.0, n = 10), // n<15
        )
        val v = SignalLabService.judgeRotation(buckets).associateBy { it.label }
        assertEquals("혼재", v[SignalLabService.ROT_INFLOW]!!.verdict)
        assertEquals("표본부족", v[SignalLabService.ROT_OUTFLOW]!!.verdict)
    }

    // ── spearman ───────────────────────────────────────────────────────

    @Test
    fun `완전 단조 증가면 1, 감소면 -1`() {
        val inc = listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0)
        assertEquals(1.0, SignalLabService.spearman(inc), 1e-9)
        val dec = listOf(1.0 to 40.0, 2.0 to 30.0, 3.0 to 20.0, 4.0 to 10.0)
        assertEquals(-1.0, SignalLabService.spearman(dec), 1e-9)
    }

    @Test
    fun `동률은 평균 순위 - 손계산 대조`() {
        // x=[1,2,2,3], y=[10,20,30,40] → rx=[1,2.5,2.5,4], ry=[1,2,3,4]
        // pearson(rx,ry) = 0.9487 (손계산: cov=2.25, sx=√4.5/..., 표준 공식)
        val pairs = listOf(1.0 to 10.0, 2.0 to 20.0, 2.0 to 30.0, 3.0 to 40.0)
        val rho = SignalLabService.spearman(pairs)
        assertTrue(abs(rho - 0.9487) < 0.001, "rho=$rho")
    }

    @Test
    fun `상관 없으면 0 근처`() {
        val pairs = listOf(1.0 to 10.0, 2.0 to 40.0, 3.0 to 10.0, 4.0 to 40.0)
        assertTrue(abs(SignalLabService.spearman(pairs)) < 0.5)
    }
}
