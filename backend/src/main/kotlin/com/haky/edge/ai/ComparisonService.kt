package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.Quote
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NaverTargetPriceClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** 두 종목 비교 응답. a/b 핵심 지표 + Claude 비교 코멘트. */
@Serializable
data class ComparisonDetail(
    val code: String,
    val name: String,
    val price: Long,
    val changeRate: Double,
    val per: Double,
    val pbr: Double,
    val week52PosPct: Double,       // 52주 범위 내 현재 위치 (0~100%)
    val upsidePct: Double?,         // 컨센서스 목표가 괴리율 (없으면 null)
    val valuationLabel: String?,    // 역사적 하단권 / 중간권 / 상단권 (없으면 null)
    val foreignNet3d: Long,         // 외인 최근 3일 순매수 합계
    val institutionNet3d: Long,     // 기관 최근 3일 순매수 합계
    val quarterlyYoy: Double?,      // 분기 실적 YoY % (없으면 null)
)

@Serializable
data class Comparison(
    val a: ComparisonDetail,
    val b: ComparisonDetail,
    val comment: String,
    val generatedAt: String = "",
)

/** 두 종목 나란히 비교 코멘트 생성. 캐시 키 = sorted(codeA,codeB):today:mode. */
class ComparisonService(
    private val kis: KisClient,
    private val naver: NaverNewsClient,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val dart: DartClient,
    private val naverTargetPrice: NaverTargetPriceClient,
    private val valuationBandSvc: ValuationBandService,
) {
    private data class Cached(val comparison: Comparison)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val fileCache = FileCache("comparison", Comparison.serializer())

    suspend fun compare(
        codeA: String,
        codeB: String,
        mode: AnalysisMode = AnalysisMode.DEFENSIVE,
        force: Boolean = false,
    ): Comparison {
        val today = effectiveMarketDate() // KST 거래일 — FileCache KST 검증과 통일(오전 캐시 미스 방지)
        val (lo, hi) = if (codeA <= codeB) codeA to codeB else codeB to codeA
        val key = "$lo:$hi:$today:${mode.name}"

        if (!force) {
            val cached = cache[key]?.comparison ?: fileCache.get(key)?.also { cache[key] = Cached(it) }
            if (cached != null) return cached
        }

        return coroutineScope {
            // 두 종목 데이터 병렬 수집
            val quoteAD  = async { kis.getPrice(codeA) }
            val quoteBD  = async { kis.getPrice(codeB) }
            val nameAD   = async { master.search(codeA).firstOrNull { it.code == codeA }?.name ?: codeA }
            val nameBD   = async { master.search(codeB).firstOrNull { it.code == codeB }?.name ?: codeB }
            val flowsAD  = async { runCatching { kis.getInvestorFlow(codeA, days = 3) }.getOrElse { emptyList() } }
            val flowsBD  = async { runCatching { kis.getInvestorFlow(codeB, days = 3) }.getOrElse { emptyList() } }
            val barsAD   = async { runCatching { kis.getDailyChart(codeA, bars = 20) }.getOrElse { emptyList() } }
            val barsBD   = async { runCatching { kis.getDailyChart(codeB, bars = 20) }.getOrElse { emptyList() } }
            val finAD    = async { runCatching { dart.getFinancials(codeA) }.getOrNull() }
            val finBD    = async { runCatching { dart.getFinancials(codeB) }.getOrNull() }
            val tpAD     = async { runCatching { naverTargetPrice.getTargetPrice(codeA) }.getOrNull() }
            val tpBD     = async { runCatching { naverTargetPrice.getTargetPrice(codeB) }.getOrNull() }
            val vbAD     = async { runCatching { valuationBandSvc.getValuationBand(codeA) }.getOrNull() }
            val vbBD     = async { runCatching { valuationBandSvc.getValuationBand(codeB) }.getOrNull() }
            val qiAD     = async { runCatching { dart.getQuarterlyIncome(codeA) }.getOrNull() }
            val qiBD     = async { runCatching { dart.getQuarterlyIncome(codeB) }.getOrNull() }

            val quoteA = quoteAD.await(); val quoteB = quoteBD.await()
            val nameA  = nameAD.await();  val nameB  = nameBD.await()

            val newsAD = async { runCatching { naver.search(nameA, display = 5) }.getOrElse { emptyList() } }
            val newsBD = async { runCatching { naver.search(nameB, display = 5) }.getOrElse { emptyList() } }

            val flowsA = flowsAD.await(); val flowsB = flowsBD.await()
            val barsA  = barsAD.await();  val barsB  = barsBD.await()
            val finA   = finAD.await();   val finB   = finBD.await()
            val tpA    = tpAD.await();    val tpB    = tpBD.await()
            val vbA    = vbAD.await();    val vbB    = vbBD.await()
            val qiA    = qiAD.await();    val qiB    = qiBD.await()
            val newsA  = newsAD.await().take(3)
            val newsB  = newsBD.await().take(3)

            val detailA = buildDetail(codeA, nameA, quoteA, flowsA, tpA, vbA, qiA)
            val detailB = buildDetail(codeB, nameB, quoteB, flowsB, tpB, vbB, qiB)

            val facts = buildCompareFacts(detailA, detailB, barsA, barsB, finA, finB, newsA, newsB)
            val prompt = if (mode == AnalysisMode.AGGRESSIVE) AGGRESSIVE_PROMPT else DEFENSIVE_PROMPT
            val comment = claude.complete(prompt, facts, maxTokens = 2000)

            val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val result = Comparison(a = detailA, b = detailB, comment = comment, generatedAt = now)
            cache[key] = Cached(result)
            fileCache.put(key, result)
            result
        }
    }

    private fun buildDetail(
        code: String,
        name: String,
        q: Quote,
        flows: List<com.haky.edge.kis.InvestorFlow>,
        targetPrice: Long?,
        vb: ValuationBand?,
        qi: com.haky.edge.dart.QuarterlyIncome?,
    ): ComparisonDetail {
        val pos52 = if (q.high52w > q.low52w && q.high52w > 0)
            (q.price - q.low52w).toDouble() / (q.high52w - q.low52w) * 100
        else 0.0

        val upside = if (targetPrice != null && targetPrice > 0)
            (targetPrice - q.price).toDouble() / q.price * 100
        else null

        val foreignNet3d = flows.sumOf { it.foreign }
        val institutionNet3d = flows.sumOf { it.institution }

        return ComparisonDetail(
            code = code,
            name = name,
            price = q.price,
            changeRate = q.changeRate,
            per = q.per,
            pbr = q.pbr,
            week52PosPct = pos52,
            upsidePct = upside,
            valuationLabel = vb?.takeIf { it.yearsUsed > 0 }?.perLabel,
            foreignNet3d = foreignNet3d,
            institutionNet3d = institutionNet3d,
            quarterlyYoy = qi?.yoyPct,
        )
    }

    private fun buildCompareFacts(
        a: ComparisonDetail,
        b: ComparisonDetail,
        barsA: List<com.haky.edge.kis.DailyBar>,
        barsB: List<com.haky.edge.kis.DailyBar>,
        finA: com.haky.edge.dart.FinancialSummary?,
        finB: com.haky.edge.dart.FinancialSummary?,
        newsA: List<com.haky.edge.news.NewsItem>,
        newsB: List<com.haky.edge.news.NewsItem>,
    ): String {
        val sb = StringBuilder()

        fun appendStock(d: ComparisonDetail, bars: List<com.haky.edge.kis.DailyBar>, fin: com.haky.edge.dart.FinancialSummary?, news: List<com.haky.edge.news.NewsItem>) {
            sb.appendLine("=== ${d.name} (${d.code}) ===")
            sb.appendLine("현재가: ${d.price}원 (${if (d.changeRate >= 0) "+" else ""}${"%.2f".format(d.changeRate)}%)")
            sb.appendLine("52주 위치: ${"%.0f".format(d.week52PosPct)}% (0%=52주저점, 100%=52주고점)")
            if (d.per > 0) sb.appendLine("PER ${d.per} / PBR ${d.pbr}")
            if (d.valuationLabel != null) sb.appendLine("밸류에이션 히스토리: ${d.valuationLabel}")
            if (d.upsidePct != null) sb.appendLine("컨센서스 목표가 대비: ${if (d.upsidePct >= 0) "+" else ""}${"%.1f".format(d.upsidePct)}%")
            val fDir = if (d.foreignNet3d > 0) "순매수 ${"%,d".format(d.foreignNet3d)}주" else if (d.foreignNet3d < 0) "순매도 ${"%,d".format(-d.foreignNet3d)}주" else "보합"
            val iDir = if (d.institutionNet3d > 0) "순매수 ${"%,d".format(d.institutionNet3d)}주" else if (d.institutionNet3d < 0) "순매도 ${"%,d".format(-d.institutionNet3d)}주" else "보합"
            sb.appendLine("수급(최근 3일 합계): 외인 $fDir / 기관 $iDir")
            if (d.quarterlyYoy != null) {
                val dir = when {
                    d.quarterlyYoy > 10 -> "(개선)"
                    d.quarterlyYoy < -10 -> "(악화)"
                    else -> "(보합)"
                }
                sb.appendLine("최근 분기 순이익 YoY: ${if (d.quarterlyYoy >= 0) "+" else ""}${"%.1f".format(d.quarterlyYoy)}% $dir")
            }
            if (fin != null) {
                fun eok(v: Long?) = if (v != null) "${"%,d".format(v / 100_000_000)}억" else null
                val rev = eok(fin.revenue); val op = eok(fin.operatingProfit); val ni = eok(fin.netIncome)
                if (rev != null || op != null || ni != null) {
                    sb.append("재무(${fin.fiscalYear}년): ")
                    listOfNotNull(rev?.let { "매출 $it" }, op?.let { "영업익 $it" }, ni?.let { "순익 $it" }).joinTo(sb, " / ")
                    sb.appendLine()
                }
            }
            // 최근 가격 방향 요약 (간략)
            if (bars.size >= 5) {
                val closes = bars.map { it.close }
                val streak5 = closes.take(5)
                val up5 = streak5.zipWithNext().count { (cur, prev) -> cur > prev }
                val dir5 = when {
                    up5 >= 4 -> "최근 5일 강한 상승"
                    up5 <= 1 -> "최근 5일 강한 하락"
                    up5 == 3 -> "최근 5일 소폭 상승세"
                    else -> "최근 5일 소폭 하락세"
                }
                sb.appendLine(dir5)
            }
            if (news.isNotEmpty()) {
                sb.appendLine("관련 뉴스(최신 ${news.size}건):")
                news.forEach { sb.appendLine("  - ${it.title}") }
            }
        }

        appendStock(a, barsA, finA, newsA)
        sb.appendLine()
        appendStock(b, barsB, finB, newsB)
        return sb.toString()
    }

    companion object {
        private val DEFENSIVE_PROMPT = """
            너는 한국 주식 투자 보조 앱의 분석 어시스턴트다.
            독자는 주식에 관심이 있지만 전문 트레이더가 아닌 일반인이다.

            두 종목(A, B)의 핵심 지표가 주어진다. 이 두 종목을 비교·분석해 현재 시점에서 어느 쪽이 더 나아 보이는지 결론을 내라.

            규칙:
            1. 아래 user 메시지의 사실 데이터에 있는 값만 근거로 삼는다. 없는 수치를 절대 지어내지 마라.
            2. 다음 순서로 단락을 써라:
               - **흐름 비교**: 두 종목의 최근 주가 흐름·뉴스 방향을 비교. "A는 ~인 반면 B는 ~"처럼 나란히.
               - **밸류·수급**: PER/PBR·밸류에이션 위치·수급 방향을 비교.
               - **종합 우열**: 지금 이 시점에서 어느 종목이 더 나아 보이는지 결론. 양비론("둘 다 좋다/나쁘다") 금지. 한 쪽을 고르되, 반대 종목의 핵심 리스크를 한 줄만 덧붙여라.
            3. "지금 사라/팔라"처럼 매매를 지시하지 마라. 비교 관점 분석만.
            4. 형식: 불릿·번호 목록, --- 구분선, ~~취소선~~ 금지. 각 단락 첫 줄에 **소제목**만 굵게, 그 다음 줄부터 본문. 단락 사이 빈 줄 하나.
            5. 핵심 수치(등락률·PER·목표가 괴리 등)는 **굵게**.
            6. 전문 용어는 괄호 안에 짧게 설명.
        """.trimIndent()

        private val AGGRESSIVE_PROMPT = """
            너는 한국 주식 투자 보조 앱의 분석 어시스턴트다.
            지금은 "공격적 모드" — 단호한 비교 판단을 직접 요청받은 상태다.

            두 종목(A, B)의 핵심 지표가 주어진다. 이 두 종목을 비교해 지금 어느 쪽이 더 나은지 결론을 내라. 에두르지 마라.

            규칙:
            1. 아래 사실 데이터에 있는 값만 근거로 삼는다. 없는 수치를 절대 지어내지 마라.
            2. 다음 순서로 단락을 써라:
               - **흐름 비교**: 두 종목의 최근 주가·뉴스 방향 나란히 비교.
               - **밸류·수급**: PER/PBR·밸류에이션 위치·수급 방향 비교.
               - **종합 우열**: 지금 시점에서 어느 쪽이 더 나은지 단호하게 결론. 양비론 금지. 반대 종목의 핵심 리스크 한 줄.
            3. 매매를 직접 지시하지 마라("사라/팔라" 금지). 어느 종목이 더 나아 보이는지 판단만.
            4. 형식: 불릿·번호 목록, --- 구분선, ~~취소선~~ 금지. 각 단락 첫 줄에 **소제목**만 굵게. 단락 사이 빈 줄 하나.
            5. 핵심 수치는 **굵게**.
        """.trimIndent()
    }
}
