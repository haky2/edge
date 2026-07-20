package com.haky.edge.ai

import com.haky.edge.kis.KisClient
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.HoldingPosition
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.MacroImpactService.MacroGroup
import com.haky.edge.master.StockMaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

// ── 앱에 내려주는 DTO ────────────────────────────────────────────────────

/** 섹터 1개의 평가금액 비중. */
@Serializable
data class SectorWeight(val label: String, val weightPct: Double, val stockNames: List<String>)

/** 매크로 지표 1개에 대한 포트폴리오 구조 노출(비중 가중). 오늘 등락이 아니라 "그 지표가 움직이면 얼마나 같이 흔들리나". */
@Serializable
data class MacroExposure(val label: String, val favorablePct: Double, val adversePct: Double)

/** 밸류 밴드 위치 1구간의 비중. */
@Serializable
data class ValuationBucket(val label: String, val weightPct: Double, val count: Int)

/** 포트폴리오 종합 진단 응답. 수치 필드는 전부 계산(사실), comment/summary만 Claude 해석. */
@Serializable
data class PortfolioReview(
    val date: String,
    val comment: String,
    val summary: String? = null,       // "### 핵심 요약" 파싱(분석 코멘트와 동일 계약)
    val generatedAt: String = "",
    val stockCount: Int,
    val totalValue: Long,              // 총 평가금액(원)
    val totalCost: Long,               // 총 매입금액(원)
    val totalPnl: Long,                // 평가손익(원)
    val totalPnlPct: Double,           // 평가손익률(%)
    val topStockName: String? = null,
    val topStockWeightPct: Double? = null,
    val topSectorLabel: String? = null,
    val topSectorWeightPct: Double? = null,
    val sectors: List<SectorWeight> = emptyList(),
    val exposures: List<MacroExposure> = emptyList(),
    val valuationDist: List<ValuationBucket> = emptyList(),
)

/**
 * 포트폴리오 종합 진단(B 슬라이스) — 종목별 분석은 있는데 "내 계좌 전체가 어떤 구조인가"를 보는
 * 뷰가 없던 공백을 채운다.
 *
 * 원칙(CLAUDE.md): 집중도·매크로 노출·밸류 분포는 전부 **계산**(SENSITIVITY×비중 가중) →
 * Claude는 그 구조의 의미 해석만. 오늘 시장 방향은 macro-impact 담당이라 여기선 다루지 않는다
 * (프롬프트가 금지) — 이 진단은 지표가 움직였을 때 "포트폴리오가 한 방에 같이 흔들리는 정도"라는
 * 구조 사실을 다룬다.
 *
 * 캐시: 포지션이 입력이라 개인별 — (날짜 + 정렬된 code:avg:qty + 모드). 당일 1회, force 재생성엔
 * 5분 쿨다운(AnalysisService와 동일한 연타 비용 가드).
 */
class PortfolioReviewService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val macroImpact: MacroImpactService,
    private val valuationBandSvc: ValuationBandService,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
) {
    private val cache = ConcurrentHashMap<String, PortfolioReview>()
    private val fileCache = FileCache("portfolio-review", PortfolioReview.serializer())

    suspend fun review(
        positions: Map<String, HoldingPosition>,
        mode: AnalysisMode = AnalysisMode.DEFENSIVE,
        force: Boolean = false,
        theses: Map<String, String> = emptyMap(),
        horizon: String? = null,
    ): PortfolioReview {
        require(positions.isNotEmpty()) { "보유 포지션이 비어 있습니다" }
        val today = effectiveMarketDate()
        // 계좌 성격: "long"(장기 계좌 범위 진단)만 의미 있음 — 그 외는 null 정규화(기존 키·프롬프트 불변).
        val horizonLong = horizon == AnalysisService.HORIZON_LONG
        val key = buildKey(today, positions, mode, theses, horizonLong)

        val cached = cache[key] ?: fileCache.get(key)?.also { cache[key] = it }
        if (cached != null) {
            // force 연타 가드: 마지막 생성 5분 안이면 캐시 반환(회당 풀 LLM 비용 방지).
            if (!force || !isPastMinutes(cached.generatedAt, FORCE_COOLDOWN_MINUTES)) {
                if (!force) return cached
                println("[ForceCooldown] portfolio-review: ${FORCE_COOLDOWN_MINUTES}분 내 재생성 요청 → 캐시 반환")
                return cached
            }
        }

        // 종목별 사실 수집(병렬): 현재가·이름·섹터·밸류밴드. 개별 실패는 그 종목만 제외하지 않고
        // 시세 실패 시 전체 실패(비중 계산이 왜곡되므로), 섹터·밸류는 없어도 진행.
        val stocks = coroutineScope {
            positions.map { (code, pos) ->
                async {
                    val quote = kis.getPrice(code)
                    val name = master.findByCode(code)?.name ?: code
                    val sectors = runCatching {
                        macroImpact.resolveStockSectors(code, name, quote.sectorName)
                    }.getOrElse { emptyList() }
                    val vb = runCatching { valuationBandSvc.getValuationBand(code) }.getOrNull()
                    val value = quote.price * pos.qty
                    val cost = (pos.avgPrice * pos.qty).toLong()
                    StockCalc(
                        code = code,
                        name = name,
                        value = value,
                        cost = cost,
                        pnlPct = if (pos.avgPrice > 0) (quote.price - pos.avgPrice) / pos.avgPrice * 100 else 0.0,
                        sectorLabel = sectors.firstOrNull()?.label ?: "기타",
                        groups = sectors.map { it.group }.toSet(),
                        valuationLabel = vb?.takeIf { it.yearsUsed > 0 }?.perLabel,
                    )
                }
            }.awaitAll()
        }.sortedByDescending { it.value }

        val totalValue = stocks.sumOf { it.value }
        val totalCost = stocks.sumOf { it.cost }
        val totalPnl = totalValue - totalCost
        val totalPnlPct = if (totalCost > 0) totalPnl.toDouble() / totalCost * 100 else 0.0
        val sectors = sectorWeights(stocks, totalValue)
        val exposures = macroExposures(stocks, totalValue)
        val valuation = valuationDist(stocks, totalValue)
        val topStock = stocks.firstOrNull()
        val topSector = sectors.firstOrNull()

        val facts = buildFacts(stocks, totalValue, totalCost, totalPnl, totalPnlPct, sectors, exposures, valuation, theses, horizonLong)
        val prompt = if (mode == AnalysisMode.AGGRESSIVE) AGGRESSIVE_PROMPT else DEFENSIVE_PROMPT
        val model = modelRouter.modelFor(ModelRouter.PORTFOLIO)
        val raw = claude.complete(prompt, facts, maxTokens = 2500, modelOverride = model)
        val (summary, comment) = AnalysisService.parseSummaryFromComment(raw)

        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val result = PortfolioReview(
            date = today,
            comment = comment,
            summary = summary,
            generatedAt = now,
            stockCount = stocks.size,
            totalValue = totalValue,
            totalCost = totalCost,
            totalPnl = totalPnl,
            totalPnlPct = totalPnlPct,
            topStockName = topStock?.name,
            topStockWeightPct = topStock?.let { pct(it.value, totalValue) },
            topSectorLabel = topSector?.label,
            topSectorWeightPct = topSector?.weightPct,
            sectors = sectors,
            exposures = exposures,
            valuationDist = valuation,
        )
        cache[key] = result
        fileCache.put(key, result)
        return result
    }

    /** Claude 입력용 사실 텍스트 — 여기 있는 값만 근거로 쓰라고 프롬프트가 지시. */
    private fun buildFacts(
        stocks: List<StockCalc>,
        totalValue: Long,
        totalCost: Long,
        totalPnl: Long,
        totalPnlPct: Double,
        sectors: List<SectorWeight>,
        exposures: List<MacroExposure>,
        valuation: List<ValuationBucket>,
        theses: Map<String, String> = emptyMap(),
        horizonLong: Boolean = false,
    ): String = buildString {
        // P9가 이 라벨("계좌 성격: 장기")에 걸려 조정 스탠스를 장기 리밸런싱 관점으로 전환한다.
        if (horizonLong) {
            appendLine("계좌 성격: 장기 — 이 포트폴리오는 ISA·IRP·퇴직연금 등 장기 투자 계좌의 보유분이다(사용자가 장기 관점으로 관리).")
            appendLine()
        }
        appendLine("포트폴리오 스냅샷(실제 보유 ${stocks.size}종목, 전부 계산된 사실):")
        appendLine(
            "  총 평가 ${"%,d".format(totalValue)}원 / 총 매입 ${"%,d".format(totalCost)}원 / " +
                "평가손익 ${if (totalPnl >= 0) "+" else ""}${"%,d".format(totalPnl)}원 (${signed(totalPnlPct)}%)"
        )
        appendLine()
        appendLine("종목별(평가 비중 순):")
        stocks.forEach { s ->
            val vb = s.valuationLabel?.let { ", 밸류 $it" } ?: ""
            appendLine(
                "  - ${s.name}(${s.code}): 비중 ${"%.1f".format(pct(s.value, totalValue))}%, " +
                    "손익 ${signed(s.pnlPct)}%, 섹터 ${s.sectorLabel}$vb"
            )
            // 종목 줄 바로 아래에 논지를 붙인다 — P8이 "가설" 라벨에 걸려 논지 인용을 막는다.
            theses[s.code]?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("    투자 논지(사용자 기록, 가설이며 사실 아님): \"$it\"")
            }
        }
        appendLine()
        appendLine("섹터 집중도(평가 비중):")
        sectors.forEach { sw ->
            appendLine("  - ${sw.label} ${"%.1f".format(sw.weightPct)}% (${sw.stockNames.joinToString("·")})")
        }
        if (exposures.isNotEmpty()) {
            appendLine()
            appendLine("매크로 공통 노출(비중 가중 구조 계산 — 오늘 등락이 아니라 \"그 지표가 움직이면 포트폴리오의 몇 %가 같은 방향으로 흔들리나\"):")
            exposures.forEach { e ->
                val parts = buildList {
                    if (e.favorablePct > 0) add("수혜 비중 ${"%.0f".format(e.favorablePct)}%")
                    if (e.adversePct > 0) add("부담 비중 ${"%.0f".format(e.adversePct)}%")
                }
                appendLine("  - ${e.label} 시: ${parts.joinToString(" / ")}")
            }
        }
        if (valuation.isNotEmpty()) {
            appendLine()
            appendLine("밸류 위치 분포(각 종목의 역사 밴드 내 위치, 판단 아님):")
            valuation.forEach { v ->
                appendLine("  - ${v.label}: 비중 ${"%.0f".format(v.weightPct)}% (${v.count}종목)")
            }
        }
    }

    private fun signed(v: Double): String = (if (v >= 0) "+" else "") + "%.1f".format(v)

    /** 종목 1개의 계산 스냅샷(순수 데이터 — 집계 함수 테스트용). */
    internal data class StockCalc(
        val code: String,
        val name: String,
        val value: Long,
        val cost: Long,
        val pnlPct: Double,
        val sectorLabel: String,
        val groups: Set<MacroGroup>,
        val valuationLabel: String?,
    )

    companion object {
        private const val FORCE_COOLDOWN_MINUTES = 5L

        /** generatedAt(HH:mm, KST)에서 minutes 이상 경과했는지. 파싱 실패 시 true(재생성 허용). */
        private fun isPastMinutes(generatedAt: String, minutes: Long): Boolean {
            if (generatedAt.isBlank()) return true
            return try {
                val genTime = java.time.LocalTime.parse(generatedAt, java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
                java.time.Duration.between(genTime, now).toMinutes() >= minutes
            } catch (e: Exception) { true }
        }

        internal fun pct(part: Long, total: Long): Double =
            if (total > 0) part.toDouble() / total * 100 else 0.0

        /** 캐시 키: 날짜 + 정렬된 포지션 집합 + 모드 (+논지 해시 +장기 계좌). 입력이 다르면 새 키. */
        internal fun buildKey(today: String, positions: Map<String, HoldingPosition>, mode: AnalysisMode, theses: Map<String, String> = emptyMap(), horizonLong: Boolean = false): String {
            val base = "$today|" + positions.entries
                .sortedBy { it.key }
                .joinToString(",") { "${it.key}:${it.value.avgPrice.toLong()}:${it.value.qty}" } +
                "|${mode.name}"
            // 논지는 자유 텍스트라 SHA-256 앞 16자로 접는다(32비트 hashCode 충돌 방지 — S11).
            val t = theses.entries.filter { it.value.isNotBlank() }.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value.trim()}" }
            val withThesis = if (t.isEmpty()) base else "$base|t${AnalysisService.shortHash(t)}"
            // 장기 계좌 범위 진단은 코멘트 성격이 달라 캐시 분리(자유는 접미사 없음 = 기존 키 불변).
            return if (horizonLong) "$withThesis|hL" else withThesis
        }

        /** 섹터별 평가 비중(내림차순). 세부 섹터 label(주력 첫 섹터) 기준. */
        internal fun sectorWeights(stocks: List<StockCalc>, totalValue: Long): List<SectorWeight> =
            stocks.groupBy { it.sectorLabel }
                .map { (label, group) ->
                    SectorWeight(
                        label = label,
                        weightPct = pct(group.sumOf { it.value }, totalValue),
                        stockNames = group.sortedByDescending { it.value }.map { it.name },
                    )
                }
                .sortedByDescending { it.weightPct }

        // 노출 계산 대상 지표와 표시 문구. SENSITIVITY(대분류 기준)에 등장하는 방향 계산 지표들.
        private val EXPOSURE_LABELS = listOf(
            "usdkrw" to "원/달러 상승(원화 약세)",
            "nasdaq" to "나스닥 상승",
            "rate3y" to "금리(국고채3년) 상승",
            "copper" to "구리 가격 상승",
            "crude" to "유가 상승",
            "usdjpy" to "엔/달러 상승(엔화 약세)",
        )

        /**
         * 지표별 구조 노출(비중 가중). 종목의 대분류(들)가 해당 지표에 갖는 민감도 부호 합으로
         * 종목 단위 방향을 정하고(+수혜/-부담), 그 방향으로 종목 비중을 누적한다.
         * 수혜·부담 모두 0인 지표는 생략. MacroImpactService.SENSITIVITY 재사용 — 별도 판단 없음.
         */
        internal fun macroExposures(stocks: List<StockCalc>, totalValue: Long): List<MacroExposure> =
            EXPOSURE_LABELS.mapNotNull { (key, label) ->
                var favorable = 0L
                var adverse = 0L
                for (s in stocks) {
                    val dirSum = s.groups.sumOf { g ->
                        MacroImpactService.SENSITIVITY[g].orEmpty()
                            .filter { it.indicatorKey == key }
                            .sumOf { it.direction }
                    }
                    when {
                        dirSum > 0 -> favorable += s.value
                        dirSum < 0 -> adverse += s.value
                    }
                }
                if (favorable == 0L && adverse == 0L) null
                else MacroExposure(label, pct(favorable, totalValue), pct(adverse, totalValue))
            }

        /** 밸류 밴드 위치 분포. 라벨 없는 종목은 "밴드 계산 불가"로 묶는다(숨기면 분포가 부풀어 보임). */
        internal fun valuationDist(stocks: List<StockCalc>, totalValue: Long): List<ValuationBucket> {
            val order = listOf("역사적 상단권", "역사적 중간권", "역사적 하단권", "밴드 계산 불가")
            return stocks.groupBy { it.valuationLabel ?: "밴드 계산 불가" }
                .map { (label, group) ->
                    ValuationBucket(label, pct(group.sumOf { it.value }, totalValue), group.size)
                }
                .sortedBy { order.indexOf(it.label).takeIf { i -> i >= 0 } ?: order.size }
        }

        // ── 프롬프트 ─────────────────────────────────────────────────────
        // 개별 종목 진단(AnalysisService)과 역할을 가른다: 여기는 "구조" — 쏠림·공통 노출·분포.
        // 편향 리뷰 교훈 반영: 분산투자 설교 금지(집중=확신일 수 있음), 상단권 비중=위험 단정 금지.

        private val PORTFOLIO_CORE = """
            너는 한국 주식 투자 보조 앱의 포트폴리오 진단 어시스턴트다.
            사용자의 실제 보유 포트폴리오 스냅샷(전부 계산된 사실)이 주어진다. 개별 종목의 좋고 나쁨이 아니라
            **포트폴리오 전체의 구조**(쏠림, 공통 노출, 분포)를 진단하는 것이 너의 역할이다.
            독자는 주식에 관심이 있지만 전문 트레이더가 아닌 일반인이다. 전문 용어를 쓸 때는 괄호 안에 짧게 뜻을 달아준다.

            응답 형식(반드시):
            맨 앞에 아래 블록을 넣어라:

            ### 핵심 요약
            (2~3문장 산문. 이 포트폴리오 구조의 가장 중요한 특징과 핵심 수치를 포함. 불릿 없이 흐르는 문장으로.)

            그 다음 빈 줄 하나 후에 이어지는 문단들로 써라.

            공통 규칙(반드시 지킬 것):
            P1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치·종목을 절대 지어내지 마라. 사실 데이터에 없는 새 종목을 추천하지 마라.
            P2. 다음 주제들을 자연스러운 3~4문단으로 풀어라:
               - 전체 구조: 규모·손익·가장 큰 특징(무엇에 얼마나 쏠려 있나)을 한눈에.
               - 공통 노출: "매크로 공통 노출"을 근거로, 어떤 한 가지 변수(환율·금리·나스닥 등)가 움직이면 포트폴리오의 몇 %가 같은 방향으로 흔들리는지 — 개별 종목이 아니라 "한 방에 같이 움직이는 묶음"의 관점으로 설명하라.
               - 분포와 손익 구조: 밸류 위치 분포와 종목별 손익에서 눈에 띄는 패턴(이익이 특정 종목에 몰려 있는지, 손실 종목의 공통점 등).
               - 마무리: 이 구조에서 점검해볼 만한 포인트 한두 문장.
            P3. 집중이 곧 나쁘다고 단정하지 마라 — 집중은 확신의 표현일 수 있다. "분산하라"는 교과서 설교 대신, 이 집중이 어떤 조건에서 함께 흔들리는지(동반 변동의 사실)를 보여줘라. 판단은 사용자 몫.
            P4. "밸류 위치 분포"는 각 종목의 역사 밴드 내 위치 사실일 뿐이다. 상단권 비중이 높다고 "고평가 포트폴리오"로 단정하지 마라(이익 급증·리레이팅 국면일 수 있음 — 개별 판단은 종목 분석의 몫). 여기선 "기대가 이미 반영된 종목의 비중이 크다 → 기대가 꺾일 때 되돌림도 함께 올 수 있다"는 구조 특성까지만.
            P5. 오늘 시장이 오를지 내릴지 방향 예측을 하지 마라. 오늘 지표 등락 얘기도 하지 마라(그건 브리핑의 몫) — 이 진단은 날씨가 아니라 "집의 구조"다.
            P6. 어려운 금융 영어는 한국어로 바꾸거나 괄호 설명을 붙여라.
            P7. 형식: 불릿·번호 목록·소제목·구분선 금지(핵심 요약 블록 제외, 이야기처럼 흐르는 연속 문단). 핵심 수치(비중%·손익%·노출%)는 **굵게**. 이 지시문의 규칙·조항 번호는 내부 지시일 뿐이니 본문에 절대 쓰지 마라.
            P8. "투자 논지" 줄이 붙은 종목이 있으면(하나도 없으면 이 규칙 전체를 무시) — 사용자가 직접 기록한 보유 이유(가설)다. 개별 논지의 옳고 그름 판정은 종목 분석의 몫이니 하지 말고, 여기서는 논지들이 모여 만드는 **구조**만 진단하라:
                - 여러 종목의 논지가 사실상 같은 한 가지 베팅(같은 테마·같은 변수)이면 그 중복을 짚어라 — 종목 수는 여럿이어도 논지가 하나면 분산이 아니다.
                - 논지와 그 종목의 실제 섹터·매크로 노출이 어긋나 보이면 짚어라(예: 논지는 A인데 손익은 B 변수에 좌우되는 구조).
                - 비중이 큰데 논지가 기록되지 않은 종목이 있으면 사실로만 언급하라(기록하라는 설교 금지).
                - 논지 속 주장·수치는 사실 데이터가 아니다 — 진단의 근거로 인용하지 마라(사실 한정 규칙은 논지 텍스트에도 그대로 적용된다).
            P9. "계좌 성격: 장기" 항목이 있으면(없으면 이 규칙 전체를 무시) — 이 포트폴리오는 연금·세제혜택 등 장기 투자 계좌의 보유분이다:
                - 마무리·조정 스탠스는 단기 타이밍(오늘/이번 주 줄여라·차익 실현하라) 대신 장기 리밸런싱 관점으로 제시하라 — 비중 구조가 수년 지평에서 적절한가, 추가 적립을 어디로 이어갈 만한가, 쏠림이 장기 목표와 맞는가. 공격 모드의 직설 지시도 이 지평 위에서 하라.
                - 단, 구조적 문제(한 변수 쏠림·논지 중복 베팅)는 장기 계좌라는 이유로 눙치지 마라 — 장기일수록 구조가 성과를 지배한다.
        """.trimIndent()

        private val PORTFOLIO_DEFENSIVE = """
            스탠스(방어 모드): "비중을 줄여라/늘려라" 같은 조정 지시를 하지 마라. 구조의 사실과 그 의미(어떤 변수에 함께 흔들리는지)를 보여주는 데서 멈춰라.
        """.trimIndent()

        private val PORTFOLIO_AGGRESSIVE = """
            스탠스(공격 모드 — 사용자가 단호한 구조 조정 의견을 직접 요청해 켠 상태): 마무리 문단에서 비중 조정 스탠스를 직설적으로 제시하라 — "○○ 비중(**32%**)은 과도하니 일부 차익 실현을 검토하라", "금리 민감 비중(**78%**)을 낮추기 전엔 신규 편입을 미뤄라" 같은 종목·묶음 비중 레벨 명령까지 허용한다. 축소만이 답이 아니다 — 손익·노출 구조가 지지하면 "○○ 비중(**12%**)은 확신 대비 얇으니 확대를 검토하라"처럼 유지·확대 스탠스도 똑같이 직설적으로 제시하라(방향은 사실 데이터가 정한다). 단 모든 지시는 사실 데이터의 수치에 묶고, 사실에 없는 새 종목 추천과 "반드시 오른다/떨어진다" 같은 결과 단정은 금지.
        """.trimIndent()

        private val PORTFOLIO_FINAL_GUARD = """
            마지막 경고(가장 중요): 너의 학습 지식 속 이 종목들의 주가·실적·목표주가 기억은 전부 낡아서 틀렸다. 절대 사용하지 마라. 수치는 위 사실 데이터에서 그대로 복사해서만 쓴다. ### 핵심 요약에 쓰는 모든 수치도 사실 데이터에 있는 값이어야 한다.
        """.trimIndent()

        private val DEFENSIVE_PROMPT = PORTFOLIO_CORE + "\n\n" + PORTFOLIO_DEFENSIVE + "\n\n" + PORTFOLIO_FINAL_GUARD
        private val AGGRESSIVE_PROMPT = PORTFOLIO_CORE + "\n\n" + PORTFOLIO_AGGRESSIVE + "\n\n" + PORTFOLIO_FINAL_GUARD

        /** 합성 검증·테스트용으로 프롬프트를 노출(원문 유지). */
        internal fun promptFor(mode: AnalysisMode): String =
            if (mode == AnalysisMode.AGGRESSIVE) AGGRESSIVE_PROMPT else DEFENSIVE_PROMPT
    }
}
