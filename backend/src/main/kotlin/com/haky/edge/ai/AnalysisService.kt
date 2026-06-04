package com.haky.edge.ai

import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.Quote
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
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
 * 원칙: **사실은 우리가 수집(시세·52주·PER·수급·뉴스) → Claude 는 해석만.** 수치 날조 금지, 참고용.
 * 비용: 같은 종목·같은 날은 1회만 생성하고 인메모리 캐시로 공유(CLAUDE.md 비용 정책).
 *   - position 없음: (code,date) 공유 캐시 — 전 유저 동일 코멘트.
 *   - position 있음: (code,date,avgPrice,qty) 별도 캐시 — 포지션별로 개인화.
 */
class AnalysisService(
    private val kis: KisClient,
    private val naver: NaverNewsClient,
    private val master: StockMaster,
    private val claude: ClaudeClient,
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

        // 사실 수집. 뉴스는 실패해도 분석은 진행(없으면 그만큼만).
        val quote = kis.getPrice(code)
        val flows = kis.getInvestorFlow(code, days = 5)
        val name = master.search(code).firstOrNull { it.code == code }?.name ?: code
        val news = runCatching { naver.search(name, display = 5) }.getOrElse { emptyList() }

        val facts = buildFacts(code, name, quote, flows, news, position)
        val comment = claude.complete(SYSTEM_PROMPT, facts, maxTokens = 1024)

        val analysis = Analysis(code = code, name = name, date = today, comment = comment)
        cache[key] = Cached(analysis)
        return analysis
    }

    /** 사실 데이터를 Claude 입력용 한국어 텍스트로 정리. 여기 있는 값만 근거로 쓰라고 시스템 프롬프트가 지시. */
    private fun buildFacts(
        code: String,
        name: String,
        q: Quote,
        flows: List<InvestorFlow>,
        news: List<com.haky.edge.news.NewsItem>,
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

        if (flows.isNotEmpty()) {
            sb.appendLine("수급(일별 순매수 수량, +매수/-매도):")
            flows.forEach {
                sb.appendLine("  ${it.date} 외국인 ${it.foreign} / 기관 ${it.institution} / 개인 ${it.individual}")
            }
        }
        if (news.isNotEmpty()) {
            sb.appendLine("최근 뉴스 헤드라인:")
            news.forEach { sb.appendLine("  - [${it.source}] ${it.title}") }
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

    companion object {
        // 시스템 프롬프트(캐시 대상). 사실/해석 분리·환각 가드·참고용 디스클레이머를 명시.
        private val SYSTEM_PROMPT = """
            너는 한국 주식 투자 보조 도구의 분석 어시스턴트다. 사용자가 종목을 더 잘 이해하도록 돕는다.

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치(목표가, 컨센서스, 실적 전망 등)를 절대 지어내지 마라. 모르면 모른다고 하거나 언급하지 않는다.
            2. 시세·밸류에이션(PER/PBR)·수급(외국인/기관/개인)·뉴스 헤드라인을 종합해 "지금 이 종목을 어떻게 봐야 하나"를 3~5문장으로 설명한다.
            3. 사실과 해석을 자연스럽게 잇되, 데이터로 뒷받침되지 않는 단정은 피한다. "~로 보인다", "~일 수 있다" 같은 신중한 표현을 쓴다.
            4. "지금 사라/팔라"처럼 매매를 단정하지 마라.
            5. 한국어로, 군더더기 없이 간결하게. 과장·홍보성 표현 금지. 불릿이 아니라 자연스러운 문단으로.
            6. 뉴스 헤드라인은 종목과 무관한 게 섞일 수 있다. 관련 있어 보이는 것만 참고하고, 억지로 엮지 마라.
            7. "내 포지션" 섹션이 있으면 평단가 기준 손익·남은 거리를 코멘트에 자연스럽게 녹여 개인화된 해석을 제공한다. 예: "평단 대비 +X% 수익 구간에서…" 또는 "목표가까지 Y% 남은 상황에서…".
        """.trimIndent()
    }
}
