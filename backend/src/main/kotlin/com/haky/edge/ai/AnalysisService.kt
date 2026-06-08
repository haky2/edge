package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.dart.FinancialSummary
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.Quote
import com.haky.edge.macro.KrxShortSellingClient
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.ShortSellingSummary
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NaverTargetPriceClient
import com.haky.edge.news.NewsItem
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** 앱에 내려주는 분석 결과. comment 는 참고용 종합 코멘트. */
@Serializable
data class Analysis(
    val code: String,
    val name: String,
    val date: String,       // 생성 기준일 (YYYY-MM-DD)
    val comment: String,
    val generatedAt: String = "",  // 캐시 최초 생성 시각 HH:mm (KST)
)

/** 개인 포지션 정보. avgPrice·qty 가 있을 때만 생성. targetPrice·stopPrice 는 0.0 = 미입력. */
data class Position(
    val avgPrice: Double,
    val qty: Long,
    val targetPrice: Double = 0.0,
    val stopPrice: Double = 0.0,
)

/**
 * 종목 종합 코멘트 생성(② Claude 층).
 *
 * 원칙: **사실은 우리가 수집(시세·52주·PER·수급·가격흐름·재무·뉴스) → Claude 는 해석만.** 수치 날조 금지, 참고용.
 * 비용: 같은 종목·같은 날은 1회만 생성하고 인메모리 캐시로 공유(CLAUDE.md 비용 정책).
 *   - position 없음: (code,date) 공유 캐시 — 전 유저 동일 코멘트.
 *   - position 있음: (code,date,avgPrice,qty) 별도 캐시 — 포지션별로 개인화.
 */
class AnalysisService(
    private val kis: KisClient,
    private val naver: NaverNewsClient,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val dart: DartClient,
    private val naverTargetPrice: NaverTargetPriceClient,
    private val macroImpact: MacroImpactService,
    private val krxShortSelling: KrxShortSellingClient,
    private val valuationBandSvc: ValuationBandService,
) {
    private data class Cached(val analysis: Analysis)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val fileCache = FileCache("analysis", Analysis.serializer())

    suspend fun analyze(code: String, position: Position? = null): Analysis {
        val today = LocalDate.now().toString()
        val key = if (position != null)
            "$code:$today:avg=${position.avgPrice}:qty=${position.qty}"
        else
            "$code:$today"
        cache[key]?.let { return it.analysis }
        fileCache.get(key)?.let { cache[key] = Cached(it); return it }

        // 사실 수집. 뉴스·일봉은 실패해도 분석은 진행(없으면 그만큼만).
        val quote = kis.getPrice(code)
        val flows = kis.getInvestorFlow(code, days = 5)
        val name = master.search(code).firstOrNull { it.code == code }?.name ?: code
        val bars = runCatching { kis.getDailyChart(code, bars = 20) }.getOrElse { emptyList() }
        val financials = runCatching { dart.getFinancials(code) }.getOrNull()
        val consensusTarget = runCatching { naverTargetPrice.getTargetPrice(code) }.getOrNull()
        val sectorChangeRate = runCatching {
            macroImpact.sectorIndexChangeRate(code, name, quote.sectorName)
        }.getOrNull()
        val shortSelling = runCatching { krxShortSelling.getShortSelling(code) }.getOrNull()
        val valuationBand = runCatching { valuationBandSvc.getValuationBand(code) }.getOrNull()
        // 비슷한 뉴스가 도배되는 날(예: 특정 이슈)이 많아, 넉넉히 받아 유사 건을 묶고 대표 N건만 쓴다.
        val rawNews = runCatching { naver.search(name, display = 30) }.getOrElse { emptyList() }
        val news = dedupeNews(rawNews, limit = 8)

        val facts = buildFacts(code, name, quote, bars, financials, flows, news, consensusTarget, sectorChangeRate, shortSelling, valuationBand, position)
        val comment = claude.complete(SYSTEM_PROMPT, facts, maxTokens = 1800)

        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val analysis = Analysis(code = code, name = name, date = today, comment = comment, generatedAt = now)
        cache[key] = Cached(analysis)
        fileCache.put(key, analysis)
        return analysis
    }

    /** 사실 데이터를 Claude 입력용 한국어 텍스트로 정리. 여기 있는 값만 근거로 쓰라고 시스템 프롬프트가 지시. */
    private fun buildFacts(
        code: String,
        name: String,
        q: Quote,
        bars: List<DailyBar>,
        financials: FinancialSummary?,
        flows: List<InvestorFlow>,
        news: List<NewsCluster>,
        consensusTarget: Long?,
        sectorChangeRate: Double?,
        shortSelling: ShortSellingSummary?,
        valuationBand: ValuationBand?,
        position: Position? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("종목: $name ($code)")
        sb.appendLine("현재가: ${q.price}원 (전일대비 ${q.change}, ${q.changeRate}%)")
        if (sectorChangeRate != null) {
            val rs = q.changeRate - sectorChangeRate
            val label = when {
                rs > 0.5  -> "섹터 대비 강세"
                rs < -0.5 -> "섹터 대비 약세"
                else      -> "섹터 수준"
            }
            sb.appendLine(
                "섹터 대비 상대강도(RS): ${if (rs >= 0) "+" else ""}${"%.1f".format(rs)}%p" +
                    " (소속 섹터지수 ${if (sectorChangeRate >= 0) "+" else ""}${"%.2f".format(sectorChangeRate)}%, $label)"
            )
        }
        if (shortSelling != null) {
            sb.appendLine("공매도(KRX 데이터):")
            sb.appendLine("  최근 공매도 거래량: ${"%.0f".format(shortSelling.recentVolume.toDouble())}주 (${shortSelling.recentVolumeDate})")
            if (shortSelling.balance != null && shortSelling.balanceDate != null) {
                val balLine = StringBuilder("  공매도 잔고: ${"%.0f".format(shortSelling.balance.toDouble())}주 (${shortSelling.balanceDate} 확정)")
                if (shortSelling.balanceChangePct != null) {
                    val dir = when {
                        shortSelling.balanceChangePct > 1.0 -> "잔고 증가(하락 베팅 강화)"
                        shortSelling.balanceChangePct < -1.0 -> "잔고 감소(숏커버링·하락 베팅 약화)"
                        else -> "잔고 보합"
                    }
                    balLine.append(", 전일 대비 ${if (shortSelling.balanceChangePct >= 0) "+" else ""}${"%.1f".format(shortSelling.balanceChangePct)}% ($dir)")
                }
                sb.appendLine(balLine)
            } else {
                sb.appendLine("  공매도 잔고: 집계 중(T+2일 지연)")
            }
        }
        if (consensusTarget != null && consensusTarget > 0) {
            val upside = (consensusTarget - q.price).toDouble() / q.price * 100
            sb.appendLine(
                "애널리스트 컨센서스 목표주가: ${"%,d".format(consensusTarget)}원" +
                    " (현재가 대비 ${if (upside >= 0) "+" else ""}${"%.1f".format(upside)}%)"
            )
        }
        if (q.high52w > q.low52w && q.high52w > 0) {
            val pos = (q.price - q.low52w).toDouble() / (q.high52w - q.low52w) * 100
            val fromHigh = (q.price - q.high52w).toDouble() / q.high52w * 100
            sb.appendLine(
                "52주: 최고 ${q.high52w} / 최저 ${q.low52w} " +
                    "(현재 위치 ${"%.0f".format(pos)}%, 고점 대비 ${"%.1f".format(fromHigh)}%)"
            )
        }
        if (q.per > 0) sb.appendLine("PER ${q.per} / PBR ${q.pbr}")
        if (valuationBand != null && valuationBand.yearsUsed > 0) {
            sb.appendLine("밸류에이션 히스토리 밴드(연도말 기준 과거 ${valuationBand.yearsUsed}년, 상장주식수 근사치):")
            if (valuationBand.perCurrent > 0 && valuationBand.perMax > 0) {
                sb.appendLine(
                    "  PER 현재 ${"%.1f".format(valuationBand.perCurrent)}배 " +
                        "→ ${valuationBand.yearsUsed}년 밴드 " +
                        "[${"%.1f".format(valuationBand.perMin)}~${"%.1f".format(valuationBand.perMax)}배], " +
                        "중앙 ${"%.1f".format(valuationBand.perMedian)}배 " +
                        "(${valuationBand.perLabel})"
                )
            }
            if (valuationBand.pbrCurrent > 0 && valuationBand.pbrMax > 0) {
                sb.appendLine(
                    "  PBR 현재 ${"%.2f".format(valuationBand.pbrCurrent)}배 " +
                        "→ ${valuationBand.yearsUsed}년 밴드 " +
                        "[${"%.2f".format(valuationBand.pbrMin)}~${"%.2f".format(valuationBand.pbrMax)}배], " +
                        "중앙 ${"%.2f".format(valuationBand.pbrMedian)}배 " +
                        "(${valuationBand.pbrLabel})"
                )
            }
        }
        sb.appendLine("거래량: ${q.volume}")

        // 최근 가격 흐름 서사(일봉 계산) — "상한가 두 번 치고 며칠째 급락" 같은 흐름을 사실로 제공.
        priceActionSummary(bars)?.let { sb.appendLine().append(it) }

        // 회사 재무(DART 연간) — 급등락이 펀더멘털 성장에 근거하는지 판단할 근거.
        financialSummaryText(financials)?.let { sb.appendLine().append(it) }

        if (flows.isNotEmpty()) {
            sb.appendLine("수급(일별 순매수 수량, +매수/-매도):")
            flows.forEach {
                sb.appendLine("  ${it.date} 외국인 ${it.foreign} / 기관 ${it.institution} / 개인 ${it.individual}")
            }
        }
        if (news.isNotEmpty()) {
            sb.appendLine("최근 뉴스(유사 기사는 묶음, '외 N건'=같은 이슈가 그만큼 쏟아졌다는 관심도 신호):")
            news.forEach { c ->
                val more = if (c.count > 1) " (유사 외 ${c.count - 1}건)" else ""
                sb.appendLine("  - [${c.item.source}] ${c.item.title}$more")
                if (c.item.description.isNotBlank()) {
                    sb.appendLine("    요약: ${c.item.description}")
                }
            }
        }
        if (position != null) {
            val currentPrice = q.price.toDouble()
            val pnlRate = if (position.avgPrice > 0)
                (currentPrice - position.avgPrice) / position.avgPrice * 100 else 0.0
            val pnlAmt = (currentPrice - position.avgPrice) * position.qty
            sb.appendLine()
            sb.appendLine("내 포지션 (실제 보유 데이터):")
            sb.appendLine(
                "  평단가: ${position.avgPrice.toLong()}원, 보유수량: ${position.qty}주"
            )
            sb.appendLine(
                "  평가손익: ${if (pnlAmt >= 0) "+" else ""}${"%.0f".format(pnlAmt)}원" +
                    " (${"%.1f".format(pnlRate)}%)"
            )
            if (position.targetPrice > 0) {
                val toTarget = (position.targetPrice - currentPrice) / currentPrice * 100
                sb.appendLine(
                    "  목표가: ${position.targetPrice.toLong()}원" +
                        " (현재가 대비 ${if (toTarget >= 0) "+" else ""}${"%.1f".format(toTarget)}%)"
                )
            }
            if (position.stopPrice > 0) {
                val toStop = (position.stopPrice - currentPrice) / currentPrice * 100
                sb.appendLine(
                    "  손절가: ${position.stopPrice.toLong()}원" +
                        " (현재가 대비 ${if (toStop >= 0) "+" else ""}${"%.1f".format(toStop)}%)"
                )
            }
        }
        return sb.toString()
    }

    /**
     * 일봉(최신일이 앞)에서 최근 가격 흐름을 사람이 읽는 서사로 요약.
     * 고점 대비 낙폭·급등(상한가 수준 포함)·연속 등락을 사실로만 적는다(해석은 Claude 몫).
     */
    private fun priceActionSummary(bars: List<DailyBar>): String? {
        if (bars.size < 2) return null
        val closes = bars.map { it.close }
        val cur = closes.first()
        // 일별 등락률 = (당일종가 - 전일종가)/전일종가. rates[0] 이 가장 최근일.
        val rates = closes.zipWithNext { day, prev ->
            if (prev > 0) (day - prev).toDouble() / prev * 100 else 0.0
        }

        // 최근 고점 대비 현재 낙폭(되돌림 폭)
        val highIdx = closes.indices.minByOrNull { -closes[it] } ?: 0
        val high = closes[highIdx]
        val drawdown = if (high > 0) (cur - high).toDouble() / high * 100 else 0.0

        // 가장 최근일 부호 기준 연속 등락 일수
        val firstSign = rates.firstOrNull()?.let { if (it > 0) 1 else if (it < 0) -1 else 0 } ?: 0
        var streak = 0
        if (firstSign != 0) {
            for (r in rates) {
                val s = if (r > 0) 1 else if (r < 0) -1 else 0
                if (s == firstSign) streak++ else break
            }
        }
        // 연속 구간 누적 등락률
        val streakSum = rates.take(streak).sum()

        val limitUps = rates.count { it >= 29.0 }  // 상한가 수준(+30% 제한 근처)
        val surges = rates.count { it in 15.0..29.0 } // 상한가는 아니지만 급등

        val sb = StringBuilder()
        sb.appendLine("최근 ${bars.size}거래일 가격 흐름:")
        sb.appendLine(
            "  최근 고점 ${high}원(약 ${highIdx}거래일 전) 대비 현재 ${"%.1f".format(drawdown)}%"
        )
        val moves = buildList {
            if (limitUps > 0) add("상한가 수준(+29% 이상) 급등 ${limitUps}회")
            if (surges > 0) add("+15~29% 급등 ${surges}회")
            if (streak >= 2) add("최근 ${streak}거래일 연속 ${if (firstSign > 0) "상승" else "하락"}(누적 ${"%.1f".format(streakSum)}%)")
        }
        if (moves.isNotEmpty()) sb.appendLine("  " + moves.joinToString(", "))
        return sb.toString()
    }

    /**
     * 연간 재무 요약을 사람이 읽는 텍스트로(단위 억원, 전년比 YoY 포함).
     * 매출·영업이익·순이익 중 있는 것만 적는다. 전부 없으면 null.
     */
    private fun financialSummaryText(f: FinancialSummary?): String? {
        if (f == null) return null
        val lines = buildList {
            financeLine("매출액", f.revenue, f.revenuePrev)?.let { add(it) }
            financeLine("영업이익", f.operatingProfit, f.operatingProfitPrev)?.let { add(it) }
            financeLine("당기순이익", f.netIncome, f.netIncomePrev)?.let { add(it) }
        }
        if (lines.isEmpty()) return null
        val basis = if (f.consolidated) "연결" else "별도"
        val sb = StringBuilder()
        sb.appendLine("회사 재무(DART $basis 사업보고서 ${f.fiscalYear}년, 단위 억원):")
        lines.forEach { sb.appendLine("  $it") }
        return sb.toString()
    }

    /** "매출액 1,234억 (전년 1,000억, YoY +23.4%)" 형태. 당기 없으면 null. */
    private fun financeLine(label: String, cur: Long?, prev: Long?): String? {
        if (cur == null) return null
        val curEok = cur / 100_000_000
        val sb = StringBuilder("$label ${"%,d".format(curEok)}억")
        if (prev != null && prev != 0L) {
            val prevEok = prev / 100_000_000
            val yoy = (cur - prev).toDouble() / kotlin.math.abs(prev) * 100
            sb.append(" (전년 ${"%,d".format(prevEok)}억, YoY ${if (yoy >= 0) "+" else ""}${"%.1f".format(yoy)}%)")
        }
        return sb.toString()
    }

    // ── 유사 뉴스 클러스터링 ───────────────────────────────────────────────
    // 같은 이슈로 도배된 기사를 한 건으로 묶되, 제목이 비슷해도 요약(description)에 유의미한
    // 추가 정보가 있으면 별건으로 둔다(사용자 요구). 최신순 입력 → 대표 limit 건 반환.

    private data class NewsCluster(val item: NewsItem, val count: Int)

    private class MutableCluster(
        val item: NewsItem,
        val titleTokens: Set<String>,
        val descTokens: Set<String>,
        var count: Int = 1,
    )

    private fun dedupeNews(items: List<NewsItem>, limit: Int): List<NewsCluster> {
        val reps = mutableListOf<MutableCluster>()
        for (it in items) {
            val tTok = tokens(it.title)
            val dTok = tokens(it.description)
            // 제목이 비슷하고(>=0.5) 요약도 비슷하면(>=0.6) 같은 기사로 보고 묶는다.
            // 제목만 비슷하고 요약이 다르면 → 추가 정보가 있다고 보고 별건 유지.
            val match = reps.firstOrNull { r ->
                jaccard(tTok, r.titleTokens) >= 0.5 && jaccard(dTok, r.descTokens) >= 0.6
            }
            if (match != null) match.count++
            else reps.add(MutableCluster(it, tTok, dTok))
        }
        return reps.take(limit).map { NewsCluster(it.item, it.count) }
    }

    /** 한국어/영문/숫자 토큰 집합(2자 이상). 대소문자 무시. */
    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()

    /** 자카드 유사도. 둘 다 비면 1.0, 한쪽만 비면 0.0. */
    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    companion object {
        // 시스템 프롬프트(캐시 대상). 사실/해석 분리·환각 가드·참고용 디스클레이머를 명시.
        private val SYSTEM_PROMPT = """
            너는 한국 주식 투자 보조 앱의 분석 어시스턴트다.
            독자는 주식에 관심이 있지만 전문 트레이더가 아닌 일반인이다. 전문 용어를 쓸 때는 괄호 안에 짧게 뜻을 달아준다.
            예) PER(주가가 1년 순이익의 몇 배인지), PBR(주가가 순자산의 몇 배인지), 수급(외국인·기관·개인 중 누가 사고 파는지), 컨센서스 목표주가(여러 증권사 애널리스트가 제시한 평균 목표값)

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치를 절대 지어내지 마라.
            2. 다음 흐름으로 자연스럽게 이어지는 4~5문단으로 써라:
               ① 최근 주가가 왜 이렇게 움직였나 — 뉴스와 가격 흐름을 연결해 "무슨 일이 있었는지" 쉽게 설명.
               ② 그 움직임이 일시적 기대인지 실제 실적 변화인지 — "회사 재무" 섹션이 있으면 매출·이익 추세와 비교.
               ③ 수급 흐름(누가 사고 파는 추세인지), PER/PBR, 컨센서스 목표주가를 연결해 "지금 이 가격이 어느 수준인지" 정리.
               ④ 지금 이 종목을 어떻게 봐야 하는지 한 문단으로 마무리.
            3. 사실과 해석을 구분해서, 근거 없는 단정은 "~로 보인다", "~일 수 있다"처럼 신중하게.
            4. "지금 사라/팔라"처럼 매매를 지시하지 마라.
            5. 어려운 금융 영어(모멘텀, 밸류에이션, 멀티플 등)는 가급적 한국어로 바꾸거나 괄호 설명을 붙여라.
            6. 형식: 불릿·번호 목록 금지. 각 단락 첫 줄에 **소제목**(예: **최근 흐름**, **실적 확인**, **수급·밸류**, **종합**)을 넣어라. 단락 사이는 빈 줄 하나. 소제목 바로 아래는 이야기처럼 흐르는 문장으로.
            7. 뉴스는 종목과 무관한 것이 섞일 수 있다. 관련 있어 보이는 것만 쓰고 억지로 연결하지 마라.
            8. "내 포지션" 섹션이 있으면 평단가 기준 현재 손익과 목표가까지 남은 거리를 마지막 문단에 자연스럽게 녹여준다.
        """.trimIndent()
    }
}
