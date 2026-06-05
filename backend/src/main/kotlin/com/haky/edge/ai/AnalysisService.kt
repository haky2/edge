package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.dart.FinancialSummary
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.Quote
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NewsItem
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** 앱에 내려주는 분석 결과. comment 는 참고용 종합 코멘트. */
@Serializable
data class Analysis(
    val code: String,
    val name: String,
    val date: String,    // 생성 기준일 (YYYY-MM-DD)
    val comment: String,
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
) {
    private data class Cached(val analysis: Analysis)
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun analyze(code: String, position: Position? = null): Analysis {
        val today = LocalDate.now().toString()
        val key = if (position != null)
            "$code:$today:avg=${position.avgPrice}:qty=${position.qty}"
        else
            "$code:$today"
        cache[key]?.let { return it.analysis }

        // 사실 수집. 뉴스·일봉은 실패해도 분석은 진행(없으면 그만큼만).
        val quote = kis.getPrice(code)
        val flows = kis.getInvestorFlow(code, days = 5)
        val name = master.search(code).firstOrNull { it.code == code }?.name ?: code
        val bars = runCatching { kis.getDailyChart(code, bars = 20) }.getOrElse { emptyList() }
        val financials = runCatching { dart.getFinancials(code) }.getOrNull()
        // 비슷한 뉴스가 도배되는 날(예: 특정 이슈)이 많아, 넉넉히 받아 유사 건을 묶고 대표 N건만 쓴다.
        val rawNews = runCatching { naver.search(name, display = 30) }.getOrElse { emptyList() }
        val news = dedupeNews(rawNews, limit = 8)

        val facts = buildFacts(code, name, quote, bars, financials, flows, news, position)
        val comment = claude.complete(SYSTEM_PROMPT, facts, maxTokens = 1280)

        val analysis = Analysis(code = code, name = name, date = today, comment = comment)
        cache[key] = Cached(analysis)
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
        position: Position? = null,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("종목: $name ($code)")
        sb.appendLine("현재가: ${q.price}원 (전일대비 ${q.change}, ${q.changeRate}%)")
        if (q.high52w > q.low52w && q.high52w > 0) {
            val pos = (q.price - q.low52w).toDouble() / (q.high52w - q.low52w) * 100
            val fromHigh = (q.price - q.high52w).toDouble() / q.high52w * 100
            sb.appendLine(
                "52주: 최고 ${q.high52w} / 최저 ${q.low52w} " +
                    "(현재 위치 ${"%.0f".format(pos)}%, 고점 대비 ${"%.1f".format(fromHigh)}%)"
            )
        }
        if (q.per > 0) sb.appendLine("PER ${q.per} / PBR ${q.pbr}")
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
            너는 한국 주식 투자 보조 도구의 분석 어시스턴트다. 사용자가 종목을 더 잘 이해하도록 돕는다.

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치(목표가, 컨센서스, 실적 전망 등)를 절대 지어내지 마라. 모르면 모른다고 하거나 언급하지 않는다.
            2. 시세·밸류에이션(PER/PBR)·최근 가격 흐름·수급(외국인/기관/개인)·뉴스를 종합해 "지금 이 종목을 어떻게 봐야 하나"를 5~8문장으로 설명한다.
            3. 가능하면 다음 흐름으로 엮어라: ① 최근 주가가 왜 이렇게 움직였나(뉴스 요약에서 촉매를 찾아 가격 흐름과 연결) → ② 그 촉매가 일시적 기대인지 펀더멘털 변화인지(회사 재무의 매출·이익 추세와 대조) → ③ 그래서 지금 위치를 어떻게 볼지. 뉴스 요약에 회사의 사업·성장동력 단서가 있으면 적극 활용하라. "회사 재무" 섹션이 있으면 밸류에이션(PER/PBR)·주가 기대감이 실적 성장으로 뒷받침되는지 함께 짚어라.
            4. 사실과 해석을 자연스럽게 잇되, 데이터로 뒷받침되지 않는 단정은 피한다. "~로 보인다", "~일 수 있다" 같은 신중한 표현을 쓴다.
            5. "지금 사라/팔라"처럼 매매를 단정하지 마라.
            6. 한국어로, 군더더기 없이. 과장·홍보성 표현 금지. 불릿이 아니라 자연스러운 문단으로.
            7. 뉴스에는 종목과 무관한 게 섞일 수 있다. 관련 있어 보이는 것만 참고하고 억지로 엮지 마라. '외 N건'은 같은 이슈가 그만큼 쏟아졌다는 시장 관심도 신호로 해석할 수 있다.
            8. "내 포지션" 섹션이 있으면 평단가 기준 손익·남은 거리를 코멘트에 자연스럽게 녹여 개인화한다. 목표가가 있으면 "도달 가능성"뿐 아니라 "시간이 걸릴 경우의 기회비용"도 신중히 언급할 수 있다.
        """.trimIndent()
    }
}
