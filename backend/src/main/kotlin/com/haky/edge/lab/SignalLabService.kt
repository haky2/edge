package com.haky.edge.lab

import com.haky.edge.ai.DailyHistoryService
import com.haky.edge.ai.PeerValuationService
import com.haky.edge.lab.BacktestEngine.CTL_DOWN
import com.haky.edge.lab.BacktestEngine.CTL_UP
import com.haky.edge.lab.BacktestEngine.SignalDef
import com.haky.edge.macro.YahooHistoryClient
import com.haky.edge.util.KST
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 전략 실험실 서비스 — 수트(선언적 신호 묶음)를 골라 유니버스 리플레이를 돌린다.
 * GET /signal-lab?suite=&universe= (1회성/관리 라우트 아님 — 재사용 가능한 실험 인프라).
 *
 * 수트 anchor·discovery는 기존 일회성 검증(anchor-validation·discovery-validation)의
 * 신호를 선언형으로 재정의한 것 — 엔진 정합성 크로스체크 겸 예시. 완전 동일 수치는
 * 기대하지 않는다(dedupe·평가창·벤치 처리 미세 차이 — caveat 명시).
 */
class SignalLabService(
    private val history: DailyHistoryService,
    private val yahoo: YahooHistoryClient,
    private val watchCodes: List<String>,
) {
    suspend fun run(suite: String, universe: String): BacktestEngine.SignalLabReport {
        val signals = SUITES[suite]
            ?: throw IllegalArgumentException("없는 수트: $suite (가능: ${SUITES.keys.joinToString(", ")})")
        val codes = when (universe) {
            "peer" -> PeerValuationService.peerUniverse().keys.sorted()
            "watch" -> watchCodes
            else -> throw IllegalArgumentException("없는 유니버스: $universe (가능: peer, watch)")
        }

        val kospiRaw = yahoo.dailyCloses("^KS11", range = "5y", zone = SEOUL)
        val bench = BacktestEngine.BenchSeries(
            dates = kospiRaw.map { it.first.format(YMD) },
            closes = kospiRaw.map { it.second },
        )

        val pooled = mutableMapOf<String, MutableList<BacktestEngine.Firing>>()
        var scored = 0
        var joinFailures = 0
        val minBars = (signals.maxOf { it.warmupBars } + BacktestEngine.HORIZONS.max() + EVAL_MARGIN)
        for (code in codes) {
            val barsAsc = runCatching { history.getHistory(code, minBars = TARGET_BARS) }
                .getOrElse { emptyList() }.asReversed()
            if (barsAsc.size < minBars) continue
            scored++
            val result = BacktestEngine.replay(code, barsAsc, bench, signals)
            joinFailures += result.joinFailures
            result.firings.forEach { (label, list) ->
                pooled.getOrPut(label) { mutableListOf() } += list
            }
        }

        val buckets = BacktestEngine.aggregate(pooled)
        val verdicts = BacktestEngine.judge(buckets, signals)
        val allDates = pooled[BacktestEngine.BASELINE]?.map { it.date } ?: emptyList()
        val report = BacktestEngine.SignalLabReport(
            generatedAt = LocalDate.now(KST).toString(),
            suite = suite,
            universeLabel = universe,
            universeSize = codes.size,
            codesScored = scored,
            benchDays = bench.dates.size,
            dateRange = if (allDates.isEmpty()) "-" else "${allDates.min()} ~ ${allDates.max()}",
            joinFailures = joinFailures,
            buckets = buckets,
            verdicts = verdicts,
            caveat = "같은 날 복수 종목 발화는 독립 표본 아님(시장 공통 충격 — distinctDates 참조). " +
                "유니버스 생존·상승 편향 가능. anchor·discovery 수트는 기존 일회성 검증의 선언형 재정의 — " +
                "dedupe·평가창 미세 차이로 과거 수치와 완전 동일하지 않음. " +
                "판정은 사전 지정 기준(그리드 튜닝 금지).",
            textReport = "",
        )
        val rendered = report.copy(textReport = BacktestEngine.renderText(report))
        println(rendered.textReport)
        return rendered
    }

    companion object {
        const val TARGET_BARS = 750
        private const val EVAL_MARGIN = 30   // 평가일이 최소한 이만큼은 있도록
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")

        private const val TOUCH_TOL = 1.005  // 레벨 ±0.5% 이내 접근을 터치로(anchor-validation 동일)

        /**
         * 수트 레지스트리. 새 신호 실험 = 여기에 SignalDef 추가가 전부.
         * rule 안에서 NaN(데이터 부족)은 비교식이 false가 되어 자동 미발화.
         */
        val SUITES: Map<String, List<SignalDef>> = mapOf(
            // 기술적 앵커(공격 모드 매매 레벨 근거) — 종가 기준, t-1까지 창(anchor-validation 동일 정의)
            "anchor" to listOf(
                SignalDef("저점20 터치", warmupBars = 61, controlLabel = CTL_DOWN) {
                    it.low(0) <= it.minClose(20, 1) * TOUCH_TOL
                },
                SignalDef("고점20 돌파", warmupBars = 61, controlLabel = CTL_UP) {
                    it.close(0) > it.maxClose(20, 1)
                },
                SignalDef("MA20 터치", warmupBars = 61) {
                    it.close(1) > it.maClose(20, 1) && it.low(0) <= it.maClose(20, 1) * TOUCH_TOL
                },
                SignalDef("MA60 터치", warmupBars = 61) {
                    it.close(1) > it.maClose(60, 1) && it.low(0) <= it.maClose(60, 1) * TOUCH_TOL
                },
            ),
            // 후보 발굴 가격 신호(D1 컷) — 52주 창은 t 포함(discovery-validation 동일 정의)
            "discovery" to listOf(
                SignalDef("상대모멘텀(+5p)", warmupBars = 252) {
                    it.ret(20) - it.benchRet(20) >= 5.0
                },
                SignalDef("신고가근접(90%)", warmupBars = 252, controlLabel = CTL_UP) {
                    it.pos52w() >= 90.0
                },
                SignalDef("저점반등(30%/+5%)", warmupBars = 252, controlLabel = CTL_DOWN) {
                    it.pos52w() < 30.0 && it.ret(5) >= 5.0
                },
            ),
            // 거래량 신호(backtest 카드의 "거래량 급증"을 장기·초과수익 기준으로 재검)
            "volume" to listOf(
                SignalDef("거래량 급증(2배)", warmupBars = 21) {
                    val avg = it.avgVolume(20, 1)
                    avg > 0 && it.volume(0) >= avg * 2.0
                },
                SignalDef("거래량 급증(3배)", warmupBars = 21) {
                    val avg = it.avgVolume(20, 1)
                    avg > 0 && it.volume(0) >= avg * 3.0
                },
            ),
        )
    }
}
