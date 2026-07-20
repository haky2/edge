package com.haky.edge.ai

import kotlinx.serialization.Serializable
import kotlin.math.round

// ── 출력 DTO ──────────────────────────────────────────────────────────────

/** 시나리오 안 종목 1개의 조건부 손익. beta 있는 종목만 포함. */
@Serializable
data class StressStock(
    val code: String,
    val name: String,
    val beta: Double,
    val pnlPct: Double,     // beta × 코스피 등락(%)
    val pnlAmount: Long,    // 평가금액 × beta × 등락 / 100
)

/** 코스피 등락 시나리오 1개 → 포트폴리오·종목별 조건부 손익. */
@Serializable
data class StressScenario(
    val label: String,             // "코스피 -10%"
    val kospiMovePct: Double,      // -10.0
    val portfolioPnlPct: Double,   // 총 손익 ÷ 총 평가금액(%)
    val portfolioPnlAmount: Long,  // 종목 손익 합(원)
    val stocks: List<StressStock> = emptyList(),  // 손실 큰 순
)

/** 상관 클러스터(r≥0.7)의 동반 하락 — "분산돼 보여도 같이 무너지는 조합". -10% 시나리오 기준. */
@Serializable
data class StressCluster(
    val names: List<String>,
    val avgCorr: Double,
    val combinedDropAmount: Long,  // 구성원 손익 합(원, 음수)
    val combinedDropPct: Double,   // 총 평가금액 대비(%)
)

/**
 * POST /portfolio-stress — 코스피 등락 시나리오의 조건부 손익(LLM 0, 전부 계산).
 * 실측 지지 축(베타)만 쓴다 — SENSITIVITY 실증에서 환율·유가·구리 샥은 무근거(반증 15건)라
 * 시나리오 표에 넣으면 그럴듯한 거짓말로 회귀한다. 코스피 충격 × 종목별 실측 베타만.
 */
@Serializable
data class PortfolioStress(
    val date: String,
    val windowDays: Int,
    val totalValue: Long,
    val portfolioBeta: Double? = null,
    val scenarios: List<StressScenario> = emptyList(),
    val clusters: List<StressCluster> = emptyList(),
    val betaExcluded: List<String> = emptyList(),  // 베타 관측 부족으로 시나리오에서 빠진 종목명
    val caveat: String = "",
)

/**
 * 시나리오 스트레스(축소판) — PortfolioRiskService 재사용.
 * risk.analyze()는 (영업일+포지션) fileCache라 리스크 카드가 이미 부른 뒤면 2회차는 무비용.
 * 순수 변환은 build()로 분리(테스트 가능, 코드베이스 관례).
 */
class PortfolioStressService(private val risk: PortfolioRiskService) {

    suspend fun analyze(positions: Map<String, Long>): PortfolioStress = build(risk.analyze(positions))

    companion object {
        // 코스피 등락 시나리오(%). 실측 지지 축=베타만이라 코스피 충격만 전파.
        val SCENARIOS = listOf(-10.0, -5.0, 5.0)
        private const val WORST = -10.0  // 클러스터 동반 하락 기준 시나리오

        internal fun build(r: PortfolioRisk): PortfolioStress {
            val totalValue = r.stocks.sumOf { it.value }.coerceAtLeast(0)
            val withBeta = r.stocks.filter { it.beta != null }
            val betaExcluded = r.stocks.filter { it.beta == null }.map { it.name }

            fun stockPnl(s: RiskStock, move: Double): Long =
                round(s.value * s.beta!! * move / 100.0).toLong()

            val scenarios = SCENARIOS.map { move ->
                val stocks = withBeta.map { s ->
                    StressStock(
                        code = s.code,
                        name = s.name,
                        beta = s.beta!!,
                        pnlPct = round2(s.beta * move),
                        pnlAmount = stockPnl(s, move),
                    )
                }.sortedBy { it.pnlAmount }  // 손실 큰(가장 음수) 순
                val pnlAmount = stocks.sumOf { it.pnlAmount }
                StressScenario(
                    label = "코스피 ${fmtSignedPct(move)}",
                    kospiMovePct = move,
                    portfolioPnlPct = if (totalValue > 0) round2(pnlAmount.toDouble() / totalValue * 100) else 0.0,
                    portfolioPnlAmount = pnlAmount,
                    stocks = stocks,
                )
            }

            // 클러스터 동반 하락(-10%) — 구성원 중 베타 있는 종목만 합산.
            val byName = r.stocks.associateBy { it.name }
            val clusters = r.clusters.mapNotNull { cl ->
                val members = cl.names.mapNotNull { byName[it] }.filter { it.beta != null }
                if (members.size < 2) return@mapNotNull null
                val drop = members.sumOf { stockPnl(it, WORST) }
                StressCluster(
                    names = cl.names,
                    avgCorr = cl.avgCorr,
                    combinedDropAmount = drop,
                    combinedDropPct = if (totalValue > 0) round2(drop.toDouble() / totalValue * 100) else 0.0,
                )
            }.sortedBy { it.combinedDropAmount }

            return PortfolioStress(
                date = r.date,
                windowDays = r.windowDays,
                totalValue = totalValue,
                portfolioBeta = r.portfolioBeta,
                scenarios = scenarios,
                clusters = clusters,
                betaExcluded = betaExcluded,
                caveat = "코스피 등락에 대한 조건부 산수이며 예측이 아닙니다. 베타는 최근 ${r.windowDays}거래일 실측값으로 " +
                    "국면에 따라 변하고, 위기 국면에선 실제 낙폭이 이보다 커질 수 있습니다. " +
                    "환율·유가 등 다른 충격은 근거가 약해 포함하지 않았습니다." +
                    (if (betaExcluded.isNotEmpty()) " 베타 관측 부족 제외: ${betaExcluded.joinToString("·")}." else ""),
            )
        }

        private fun fmtSignedPct(v: Double): String {
            val n = if (v == round(v)) v.toLong().toString() else v.toString()
            return if (v >= 0) "+$n%" else "-${n.removePrefix("-")}%"
        }

        internal fun round2(v: Double) = round(v * 100) / 100.0
    }
}
