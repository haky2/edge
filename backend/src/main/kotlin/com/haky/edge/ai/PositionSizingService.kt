package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.master.StockMaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.sqrt

// ── 입력 ──────────────────────────────────────────────────────────────────

/** 기존 보유 1건 — 리스크 역산은 수량만 필요(비중 = 최근 확정 종가 × 수량). */
data class SizingHolding(val code: String, val qty: Long)

// ── 출력 DTO ──────────────────────────────────────────────────────────────

/**
 * POST /position-sizing — 리스크 기여 상한 역산. LLM 0, 전부 계산.
 *
 * "이 종목을 얼마나 담으면 포트폴리오 리스크의 X%까지 차지하나"를 역산한다.
 * 켈리·기대수익 같은 공격적 사이징이 아니라 **리스크 상한만** — 판단은 사용자, 계산은 앱.
 */
@Serializable
data class PositionSizing(
    val date: String,
    val candidateCode: String,
    val candidateName: String,
    val riskCapPct: Double,             // 요청한 리스크 기여 상한(%)
    val price: Double,                  // 환산에 쓴 현재가(최근 확정 종가)
    val maxShares: Long,                // 상한을 넘지 않는 최대 주수
    val maxAmount: Long,                // maxShares × price(원)
    val targetWeightPct: Double,        // 그때 후보 비중(신규 총자산 대비, %)
    val atRiskContributionPct: Double,  // 그 비중에서의 리스크 기여(≈ 상한)
    val sigmaPct: Double,               // 후보 연환산 변동성(%)
    val approxByPeer: Boolean = false,  // σ를 섹터 peer 평균으로 근사했는지
    val excluded: List<String> = emptyList(), // 관측 부족으로 계산에서 뺀 기존 종목
    val caveat: String = "",
)

/**
 * 포지션 사이징 보조 — PortfolioRiskService 수식을 그대로 역산에 재사용(신규 데이터 소스 0).
 *
 * 방법:
 * - 기존 보유 상대비중 p_i 고정. 후보 비중 w_c를 0→1로 올리며(기존은 (1-w_c)로 축소) 후보의
 *   리스크 기여 RC_c = w_c·(Σw)_c / σ²_p 가 상한에 닿는 w* 를 이분 탐색. RC_c는 w_c=0에서 0,
 *   w_c=1에서 100 으로 단조 증가라 상한<100 이면 (0,1)에 항상 해가 존재.
 * - 상관·변동성은 최근 60거래일 로그수익률 실측(PortfolioRiskService와 동일 창·유틸).
 * - 후보 관측<40(신규 상장 등)이면 같은 섹터 peer 평균 σ로 근사(approxByPeer=true).
 *   섹터 미상이면 기존 보유 비중가중 σ로 최후 근사. 근사 시 상관은 기존 평균 상관을 사용.
 * - 현재가는 최근 확정 종가(수익률 계열과 같은 데이터, 시세 콜 0). w* → 신규 매수액 → 주수.
 */
class PositionSizingService(
    private val master: StockMaster,
    private val history: DailyHistoryService,
) {
    private val fileCache = FileCache("position-sizing", PositionSizing.serializer())

    suspend fun size(existing: Map<String, Long>, candidateCode: String, riskCapPct: Double): PositionSizing {
        require(existing.isNotEmpty()) { "기존 보유 포지션이 비어 있습니다" }
        val cap = riskCapPct.takeIf { it in 1.0..99.0 } ?: DEFAULT_CAP
        val today = effectiveMarketDate()
        val key = "$today|$candidateCode|${"%.1f".format(cap)}|" +
            existing.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        fileCache.get(key)?.let { return it }

        // 기존 보유 + 후보 일봉을 병렬 수집.
        val existingBars = coroutineScope {
            existing.map { (code, qty) ->
                async {
                    val bars = runCatching { history.getHistory(code, minBars = WINDOW + 10) }
                        .getOrElse { emptyList() }
                    Triple(code, qty, bars)
                }
            }.awaitAll()
        }
        val candidateBars = runCatching { history.getHistory(candidateCode, minBars = WINDOW + 10) }
            .getOrElse { emptyList() }
        require(candidateBars.isNotEmpty()) { "후보 종목의 일봉 이력을 가져오지 못했습니다" }
        val candidateName = master.findByCode(candidateCode)?.name ?: candidateCode
        val price = candidateBars.maxByOrNull { it.date }!!.close.toDouble()

        // 후보 관측이 부족하면 섹터 peer 평균 σ로 근사(추가 일봉 수집).
        val candidateReturns = logReturns(candidateBars).entries.toList().takeLast(WINDOW)
        val needPeer = candidateReturns.size < MIN_RETURNS
        val peerBars: Map<String, List<DailyBar>> = if (needPeer) {
            val peers = resolvePeers(candidateCode)
            coroutineScope {
                peers.map { c ->
                    async { c to runCatching { history.getHistory(c, minBars = WINDOW + 10) }.getOrElse { emptyList() } }
                }.awaitAll()
            }.toMap()
        } else emptyMap()

        val result = compute(
            candidateCode, candidateName, candidateBars, price, cap, today,
            existingBars.map { Triple(it.first, it.second, it.third) },
            peerBars,
        )
        fileCache.put(key, result)
        return result
    }

    /** 후보 코드의 섹터 peer 바스켓(자기 자신 제외). 섹터 미상이면 빈 목록 → 최후 근사로 폴백. */
    private fun resolvePeers(code: String): List<String> {
        val universe = PeerValuationService.peerUniverse()
        val sector = universe[code] ?: return emptyList()
        return universe.filterValues { it == sector }.keys.filter { it != code }
    }

    companion object {
        const val WINDOW = 60
        const val MIN_RETURNS = 40
        const val DEFAULT_CAP = 15.0
        private const val ANNUALIZE = 252.0

        /** 일봉 → 날짜 오름차순 로그수익률(prev·cur 양수만). */
        internal fun logReturns(bars: List<DailyBar>): LinkedHashMap<String, Double> {
            val closes = bars.sortedBy { it.date }.map { it.date to it.close }
            val rets = LinkedHashMap<String, Double>()
            for (i in 1 until closes.size) {
                val prev = closes[i - 1].second; val cur = closes[i].second
                if (prev > 0 && cur > 0) rets[closes[i].first] = ln(cur.toDouble() / prev)
            }
            return rets
        }

        /**
         * 순수 계산 — IO 없이 테스트 가능(코드베이스 관례).
         * existing: (code, qty, bars). peerBars: 근사용 섹터 peer 일봉(관측 충분할 때만 사용).
         */
        internal fun compute(
            candidateCode: String,
            candidateName: String,
            candidateBars: List<DailyBar>,
            price: Double,
            capPct: Double,
            today: String,
            existing: List<Triple<String, Long, List<DailyBar>>>,
            peerBars: Map<String, List<DailyBar>>,
        ): PositionSizing {
            // 기존 보유: 관측 충분한 종목만 남기고 상대비중 재정규화.
            data class Ex(val code: String, val returns: Map<String, Double>, val sigma: Double, val value: Long)
            val excluded = mutableListOf<String>()
            val exSeries = existing.mapNotNull { (code, qty, bars) ->
                val rets = logReturns(bars).entries.toList().takeLast(WINDOW)
                if (rets.size < MIN_RETURNS) { excluded += code; return@mapNotNull null }
                val map = LinkedHashMap<String, Double>().apply { rets.forEach { put(it.key, it.value) } }
                val lastClose = bars.maxByOrNull { it.date }!!.close
                Ex(code, map, PortfolioRiskService.sampleStd(rets.map { it.value }), lastClose * qty)
            }
            require(exSeries.isNotEmpty()) { "변동성 계산에 필요한 기존 보유 일봉이 없습니다" }

            val existingValue = exSeries.sumOf { it.value }.coerceAtLeast(1)
            val p = exSeries.map { it.value.toDouble() / existingValue }  // 기존 상대비중(합 1)
            val m = exSeries.size

            // 후보 σ + 후보↔기존 상관. 관측 충분하면 실측, 아니면 peer 평균 σ + 기존 평균 상관.
            val candReturns = logReturns(candidateBars).entries.toList().takeLast(WINDOW)
            val approxByPeer = candReturns.size < MIN_RETURNS
            val candReturnMap = LinkedHashMap<String, Double>().apply { candReturns.forEach { put(it.key, it.value) } }

            // 기존 종목 간 상관 행렬(교집합 Pearson, 부족 쌍은 평균 상관 대체).
            val corr = Array(m) { DoubleArray(m) { Double.NaN } }
            val validCorrs = mutableListOf<Double>()
            for (i in 0 until m) {
                corr[i][i] = 1.0
                for (j in i + 1 until m) {
                    val common = (exSeries[i].returns.keys intersect exSeries[j].returns.keys).sorted()
                    if (common.size >= MIN_RETURNS) {
                        val r = PortfolioRiskService.pearson(
                            common.map { exSeries[i].returns.getValue(it) },
                            common.map { exSeries[j].returns.getValue(it) },
                        )
                        corr[i][j] = r; corr[j][i] = r; validCorrs += r
                    }
                }
            }
            val avgCorr = validCorrs.takeIf { it.isNotEmpty() }?.average() ?: DEFAULT_CORR
            for (i in 0 until m) for (j in 0 until m) if (corr[i][j].isNaN()) corr[i][j] = avgCorr

            val sigmaC: Double
            val corrCE = DoubleArray(m)  // 후보↔기존 i 상관
            if (!approxByPeer) {
                sigmaC = PortfolioRiskService.sampleStd(candReturns.map { it.value })
                for (i in 0 until m) {
                    val common = (candReturnMap.keys intersect exSeries[i].returns.keys).sorted()
                    corrCE[i] = if (common.size >= MIN_RETURNS)
                        PortfolioRiskService.pearson(
                            common.map { candReturnMap.getValue(it) },
                            common.map { exSeries[i].returns.getValue(it) },
                        ) else avgCorr
                }
            } else {
                // peer 평균 σ(관측 충분한 peer만). 없으면 기존 비중가중 σ로 최후 근사.
                val peerSigmas = peerBars.mapNotNull { (_, bars) ->
                    val r = logReturns(bars).entries.toList().takeLast(WINDOW)
                    if (r.size < MIN_RETURNS) null else PortfolioRiskService.sampleStd(r.map { it.value })
                }
                sigmaC = if (peerSigmas.isNotEmpty()) peerSigmas.average()
                    else (0 until m).sumOf { p[it] * exSeries[it].sigma }
                for (i in 0 until m) corrCE[i] = avgCorr  // 근사 상관 = 기존 평균 상관
            }

            // 이분 탐색: 후보 비중 w_c 에서 리스크 기여 RC_c(%)를 상한에 맞춤.
            val sigmaEx = exSeries.map { it.sigma }
            fun riskContribAt(wc: Double): Double {
                val we = DoubleArray(m) { (1.0 - wc) * p[it] }
                // σ²_p = 기존-기존 + 2·기존-후보 + 후보-후보
                var varP = 0.0
                for (i in 0 until m) for (j in 0 until m) varP += we[i] * we[j] * corr[i][j] * sigmaEx[i] * sigmaEx[j]
                var crossCE = 0.0
                for (i in 0 until m) crossCE += we[i] * corrCE[i] * sigmaC * sigmaEx[i]
                varP += 2 * wc * crossCE + wc * wc * sigmaC * sigmaC
                varP = varP.coerceAtLeast(1e-18)
                // (Σw)_c = Σ_i we_i·ρ_ci·σ_c·σ_i + wc·σ_c²
                val sigmaRowC = (0 until m).sumOf { we[it] * corrCE[it] * sigmaC * sigmaEx[it] } + wc * sigmaC * sigmaC
                return wc * sigmaRowC / varP * 100
            }

            var lo = 0.0; var hi = 1.0
            repeat(80) {
                val mid = (lo + hi) / 2
                if (riskContribAt(mid) < capPct) lo = mid else hi = mid
            }
            val wStar = (lo + hi) / 2
            val achieved = riskContribAt(wStar)

            // w* → 신규 매수액 → 주수. V_c = w*/(1-w*)·V_existing.
            val candidateValue = if (wStar < 0.999999) wStar / (1 - wStar) * existingValue else Double.MAX_VALUE
            val maxShares = if (price > 0) (candidateValue / price).toLong().coerceAtLeast(0) else 0
            val maxAmount = (maxShares * price).toLong()

            return PositionSizing(
                date = today,
                candidateCode = candidateCode,
                candidateName = candidateName,
                riskCapPct = PortfolioRiskService.round2(capPct),
                price = PortfolioRiskService.round2(price),
                maxShares = maxShares,
                maxAmount = maxAmount,
                targetWeightPct = PortfolioRiskService.round2(wStar * 100),
                atRiskContributionPct = PortfolioRiskService.round2(achieved),
                sigmaPct = PortfolioRiskService.round2(sigmaC * sqrt(ANNUALIZE) * 100),
                approxByPeer = approxByPeer,
                excluded = excluded,
                caveat = "리스크 상한 역산일 뿐 적정 매수량 추천이 아닙니다. " +
                    "최근 ${WINDOW}거래일 실측 상관·변동성 기준(위기 국면에선 상관이 급등). 현재가는 최근 확정 종가." +
                    (if (approxByPeer) " 후보 관측 부족 — 섹터 peer 평균 변동성으로 근사(상관은 기존 평균)." else "") +
                    (if (excluded.isNotEmpty()) " 관측 부족 제외(기존): ${excluded.joinToString("·")}." else ""),
            )
        }

        private const val DEFAULT_CORR = 0.5  // 근거 상관이 전혀 없을 때(단일 보유 등) 중립 가정
    }
}
