package com.haky.edge.lab

import com.haky.edge.ai.DailyHistoryService
import com.haky.edge.ai.PeerValuationService
import com.haky.edge.kis.IndexPoint
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.SectorHistory
import com.haky.edge.lab.BacktestEngine.CTL_DOWN
import com.haky.edge.lab.BacktestEngine.CTL_UP
import com.haky.edge.lab.BacktestEngine.SignalDef
import com.haky.edge.macro.SectorRotationService
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
    private val controlUniverse: ControlUniverseService? = null, // R3 대조 유니버스(없으면 control 미지원)
    private val kis: KisClient? = null,                          // rotation 수트(업종지수 이력)용 — 없으면 미지원
) {
    suspend fun run(suite: String, universe: String): BacktestEngine.SignalLabReport {
        // X5: 섹터 순환은 크로스섹션(6개 업종 상대순위) 신호 — 종목 단위 SignalDef로 표현 불가라 전용 리플레이.
        if (suite == ROTATION_SUITE) return runRotation()
        val signals = SUITES[suite]
            ?: throw IllegalArgumentException("없는 수트: $suite (가능: ${SUITES.keys.joinToString(", ")}, $ROTATION_SUITE)")
        val codes = when (universe) {
            "peer" -> PeerValuationService.peerUniverse().keys.sorted()
            "watch" -> watchCodes
            // R3: 시총 상위 근사 무작위 표본 — 관심종목(모멘텀 편향)과 독립된 대조 표본.
            "control" -> controlUniverse?.universe()
                ?: throw IllegalArgumentException("control 유니버스 미설정")
            else -> throw IllegalArgumentException("없는 유니버스: $universe (가능: peer, watch, control)")
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
            universeCodes = codes.sorted(),
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

    /**
     * X5: 섹터 자금 순환 수트 — 운영 판정 로직(SectorRotationService.compute)을 과거 2년
     * 업종지수 이력으로 리플레이해 "유입/이탈 판정 섹터가 이후 5/20일 코스피 대비 초과수익을
     * 내는가"를 실측한다. 신호 정의 재사용(이원화 없음) — compute에 t까지 21봉만 잘라 넣어
     * lookahead 차단(엔진 SignalContext와 같은 원리). 전 신호 중 유일하게 무실측이던 휴리스틱.
     */
    private suspend fun runRotation(): BacktestEngine.SignalLabReport {
        val kisClient = kis ?: throw IllegalArgumentException("rotation 수트는 KIS 미설정 환경에서 불가")
        // 업종지수 2년 이력 — 지수 차트 API는 응답당 ~100건이라 90일 청크 병합(코스피 페치와 동일 패턴).
        val end = LocalDate.now(SEOUL)
        var chunkStart = end.minusYears(2)
        val merged = mutableMapOf<String, MutableMap<String, IndexPoint>>() // label → date → point
        while (chunkStart <= end) {
            val chunkEnd = minOf(chunkStart.plusDays(89), end)
            kisClient.getSectorHistories(chunkStart.format(YMD), chunkEnd.format(YMD)).forEach { h ->
                val m = merged.getOrPut(h.label) { mutableMapOf() }
                h.points.forEach { m[it.date] = it }
            }
            chunkStart = chunkEnd.plusDays(1)
        }
        val historiesAsc = merged.mapValues { (_, m) -> m.values.sortedBy { it.date } }

        val kospiRaw = yahoo.dailyCloses("^KS11", range = "5y", zone = SEOUL)
        val bench = BacktestEngine.BenchSeries(
            dates = kospiRaw.map { it.first.format(YMD) },
            closes = kospiRaw.map { it.second },
        )

        val replay = replayRotation(historiesAsc, bench)
        val buckets = BacktestEngine.aggregate(replay.firings)
        val verdicts = judgeRotation(buckets)
        val allDates = replay.firings[BacktestEngine.BASELINE]?.map { it.date } ?: emptyList()
        val rho = spearman(replay.cells)
        val report = BacktestEngine.SignalLabReport(
            generatedAt = LocalDate.now(KST).toString(),
            suite = ROTATION_SUITE,
            universeLabel = "sector(KOSPI 업종지수)",
            universeSize = historiesAsc.size,
            universeCodes = historiesAsc.keys.sorted(),
            codesScored = historiesAsc.size,
            benchDays = bench.dates.size,
            dateRange = if (allDates.isEmpty()) "-" else "${allDates.min()} ~ ${allDates.max()}",
            joinFailures = replay.joinFailures,
            buckets = buckets,
            verdicts = verdicts,
            extraStats = listOf(
                "Spearman(rankΔ, 20일 초과수익) ρ=${"%.3f".format(rho)} " +
                    "(전 셀 n=${replay.cells.size} — 같은 날 ${historiesAsc.size}업종 종속 포함, 참고용)",
                "기저율 = baseline 승률(위 표) — 유입/이탈 판정의 정보가치는 baseline 대비로만 판단",
            ),
            caveat = "업종 ${historiesAsc.size}개뿐이라 크로스섹션 폭이 좁고 같은 날 복수 업종 발화는 " +
                "독립 표본 아님(distinctDates 참조). 연속 발화는 업종별 ±${BacktestEngine.CLUSTER_GAP}일 " +
                "클러스터 dedupe. 20일 평가창 중첩 잔존 — 사전 지정 기준(baseline 대비 평균·승률)으로만 판정. " +
                "신호 정의는 운영 SectorRotationService.compute 재사용(이원화 없음). 이탈 신호의 '지지'는 " +
                "baseline 하회(부진 예측 적중)를 뜻함.",
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

        const val ROTATION_SUITE = "rotation"
        const val ROT_INFLOW = "유입 조짐(rankΔ≥2·단기가속)"
        const val ROT_OUTFLOW = "이탈 조짐(rankΔ≤-2·단기둔화)"

        internal data class RotationReplay(
            val firings: Map<String, List<BacktestEngine.Firing>>,
            val joinFailures: Int,
            val evalDays: Int,
            val cells: List<Pair<Double, Double>>, // (rankDelta, 20일 초과수익) 전 셀 — Spearman용
        )

        /**
         * 순수 리플레이 — 각 평가일 t에 대해 t까지 21봉 스냅샷으로 SectorRotationService.compute를
         * 호출(운영 신호 정의 재사용, lookahead 차단)하고 판정 업종의 forward 5/20일 초과수익을 기록.
         * 업종 간 날짜를 교집합으로 정렬해 창·forward가 정확히 같은 거래일 집합에서 계산된다.
         * 대조군(baseline) = 전 (업종,날짜) 셀 무조건부. 연속 발화는 업종별 클러스터 dedupe(엔진 규약).
         */
        internal fun replayRotation(
            historiesAsc: Map<String, List<IndexPoint>>,
            bench: BacktestEngine.BenchSeries,
        ): RotationReplay {
            val labels = historiesAsc.keys.sorted()
            if (labels.size < 2) return RotationReplay(emptyMap(), 0, 0, emptyList())
            val maxH = BacktestEngine.HORIZONS.max()
            val warmup = SectorRotationService.MIN_POINTS - 1   // 20 — t까지 21봉 필요

            val common = labels.map { l -> historiesAsc.getValue(l).map { it.date }.toSet() }
                .reduce { a, b -> a intersect b }
            val filtered = labels.associateWith { l -> historiesAsc.getValue(l).filter { it.date in common } }
            val dates = filtered.getValue(labels.first()).map { it.date }  // 교집합이라 전 업종 동일

            val out = mutableMapOf<String, MutableList<BacktestEngine.Firing>>()
            val lastAdopted = mutableMapOf<String, Int>()  // "신호|업종" → t
            val cells = mutableListOf<Pair<Double, Double>>()
            var joinFails = 0
            var evalDays = 0

            for (i in warmup..dates.size - 1 - maxH) {
                val d = dates[i]
                val kIdx = bench.idxByDate[d] ?: run { joinFails++; -1 }
                if (kIdx < 0 || kIdx + maxH > bench.closes.lastIndex) continue
                evalDays++
                val kBase = bench.closes[kIdx]
                val benchFwd = BacktestEngine.HORIZONS.associateWith { h ->
                    (bench.closes[kIdx + h] / kBase - 1) * 100
                }

                // 운영 판정 재사용: t까지 21봉(최신이 앞) 스냅샷 → compute.
                val snapshot = labels.map { l ->
                    SectorHistory(l, filtered.getValue(l).subList(i - warmup, i + 1).asReversed())
                }
                val rotation = SectorRotationService.compute(d, snapshot)
                val strengthByLabel = rotation.sectors.associateBy { it.label }

                for (l in labels) {
                    val series = filtered.getValue(l)
                    val base = series[i].close
                    if (base <= 0) continue
                    val raw = BacktestEngine.HORIZONS.associateWith { h -> (series[i + h].close / base - 1) * 100 }
                    val excess = BacktestEngine.HORIZONS.associateWith { h -> raw.getValue(h) - benchFwd.getValue(h) }
                    val firing = BacktestEngine.Firing(l, d, i, raw, excess)

                    out.getOrPut(BacktestEngine.BASELINE) { mutableListOf() } += firing
                    strengthByLabel[l]?.let { s ->
                        cells += s.rankDelta.toDouble() to excess.getValue(BacktestEngine.PRIMARY_HORIZON)
                    }

                    fun fire(signal: String) {
                        val key = "$signal|$l"
                        val last = lastAdopted[key]
                        if (last != null && i - last <= BacktestEngine.CLUSTER_GAP) return
                        lastAdopted[key] = i
                        out.getOrPut(signal) { mutableListOf() } += firing
                    }
                    if (l in rotation.inflow) fire(ROT_INFLOW)
                    if (l in rotation.outflow) fire(ROT_OUTFLOW)
                }
            }
            return RotationReplay(out, joinFails, evalDays, cells)
        }

        /**
         * rotation 판정 — 사전 지정(엔진 judge와 같은 기준: 20일 평균 초과수익·승률 둘 다).
         * 유입은 baseline **상회**가 지지, 이탈은 **하회**(부진 예측 적중)가 지지 — 방향만 반대.
         */
        internal fun judgeRotation(buckets: List<BacktestEngine.LabBucket>): List<BacktestEngine.LabVerdict> {
            val at20 = buckets.filter { it.days == BacktestEngine.PRIMARY_HORIZON }.associateBy { it.label }
            val base = at20[BacktestEngine.BASELINE] ?: return emptyList()
            fun judgeOne(label: String, expectAbove: Boolean): BacktestEngine.LabVerdict {
                val b = at20[label]
                if (b == null || b.silenced) {
                    return BacktestEngine.LabVerdict(label, "표본부족",
                        "n=${b?.n ?: 0} < ${BacktestEngine.MIN_BUCKET_N} — 판정 침묵")
                }
                val dAvg = b.avgExcessPct - base.avgExcessPct
                val dWin = b.winExcessPct - base.winExcessPct
                val vsBase = "baseline 대비 ${BacktestEngine.fmtSigned(dAvg)}%p·승률 ${BacktestEngine.fmtSigned(dWin)}%p"
                val avgOk = if (expectAbove) dAvg > 0 else dAvg < 0
                val winOk = if (expectAbove) dWin > 0 else dWin < 0
                return when {
                    avgOk && winOk -> BacktestEngine.LabVerdict(label, "지지", vsBase)
                    !avgOk && !winOk -> BacktestEngine.LabVerdict(label, "반증", vsBase)
                    else -> BacktestEngine.LabVerdict(label, "혼재", vsBase)
                }
            }
            return listOf(judgeOne(ROT_INFLOW, expectAbove = true), judgeOne(ROT_OUTFLOW, expectAbove = false))
        }

        /** Spearman 순위 상관(동률 평균 순위). n<3이면 NaN. */
        internal fun spearman(pairs: List<Pair<Double, Double>>): Double {
            if (pairs.size < 3) return Double.NaN
            val rx = fractionalRanks(pairs.map { it.first })
            val ry = fractionalRanks(pairs.map { it.second })
            val mx = rx.average(); val my = ry.average()
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0
            for (k in rx.indices) {
                val dx = rx[k] - mx; val dy = ry[k] - my
                sxy += dx * dy; sxx += dx * dx; syy += dy * dy
            }
            return if (sxx == 0.0 || syy == 0.0) Double.NaN else sxy / kotlin.math.sqrt(sxx * syy)
        }

        private fun fractionalRanks(v: List<Double>): List<Double> {
            val sorted = v.withIndex().sortedBy { it.value }
            val ranks = DoubleArray(v.size)
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].value == sorted[i].value) j++
                val avg = (i + j + 2) / 2.0  // 1-based 평균 순위
                for (k in i..j) ranks[sorted[k].index] = avg
                i = j + 1
            }
            return ranks.toList()
        }

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
