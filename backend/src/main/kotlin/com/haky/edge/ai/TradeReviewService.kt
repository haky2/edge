package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.master.StockMaster
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 완결된 매매 1건의 복기 결과. 수치 필드는 전부 일봉으로 계산(사실), summary/comment만 Claude 해석.
 * 가격 경로는 전부 **종가 기준** — 장중 고저는 실현 불가능한 가격이라 복기 기준으로 불공정하다.
 */
@Serializable
data class TradeReview(
    val code: String,
    val name: String,
    val buyDate: String,             // YYYY-MM-DD (사용자 기록일 — 비거래일이면 구간은 그 안쪽 거래일만)
    val sellDate: String,
    val holdingTradingDays: Int,     // 구간 내 거래일 수(이력 범위 밖이면 잡힌 만큼만)
    val realizedPct: Double,         // (매도가-매수가)/매수가 — 기록가 기준
    val realizedPnl: Long? = null,   // qty 있을 때만
    val periodHighClose: Long? = null,  // 보유 구간 최고 종가(놓친 폭의 기준)
    val periodHighDate: String? = null,
    val periodLowClose: Long? = null,   // 보유 구간 최저 종가(견딘 낙폭의 기준)
    val periodLowDate: String? = null,
    val sellVsHighPct: Double? = null,  // 매도가 vs 구간 최고 종가(음수 = 고점 대비 낮게 매도)
    val afterSell5dPct: Double? = null,  // 매도 5거래일 후 종가 vs 매도가(사후 정보 — 참고용)
    val afterSell20dPct: Double? = null, // 매도 20거래일 후 종가 vs 매도가
    val partialHistory: Boolean = false, // 매수일이 일봉 이력(~500거래일)보다 과거 — 구간 사실이 부분적
    val summary: String? = null,
    val comment: String,
    val generatedAt: String,
)

/**
 * 매매 복기(트레이드 포스트모템) — 매수 전 프리모템(F5)의 대칭. 완결된 매매(매수→매도)의
 * 기록(가격·기간·사유)과 실제 가격 경로를 대조해 "그 판단은 맞았나"를 복기한다.
 *
 * 역할 분리(포폴 진단과 같은 원칙): 경로·수익률·사후 추이는 전부 **계산**(일봉 재사용, LLM 0),
 * Claude는 과정/결과 분리 해석만. 프롬프트의 축은 ①결과편향 가드(결과가 좋아도 과정이 나빴으면
 * 지적, 나빠도 과정이 옳았으면 인정) ②사후 정보로 단죄 금지(매도 후 추이는 그때 알 수 없던 것)
 * ③아부 금지 ④n=1 일반화 과신 금지.
 *
 * 비용: 요청당 1회 생성이지만 (트레이드+날짜) 캐시 — 같은 매매의 재조회는 당일 무료.
 * 매도 후 20거래일까지는 사후 추이가 자라므로 날짜 포함 키가 자연스럽게 재생성을 허용한다.
 * ModelRouter.TRADE_REVIEW 기본 Opus(해석 코멘트 정책, 저빈도 수동 행위라 자연 상한).
 */
class TradeReviewService(
    private val dailyHistory: DailyHistoryService,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
) {
    private val cache = ConcurrentHashMap<String, TradeReview>()
    private val fileCache = FileCache("trade_review", TradeReview.serializer())

    suspend fun review(
        code: String,
        buyDate: String,
        buyPrice: Double,
        sellDate: String,
        sellPrice: Double,
        qty: Long? = null,
        buyReason: String? = null,
        sellReason: String? = null,
        thesis: String? = null,
        force: Boolean = false,
    ): TradeReview {
        val today = effectiveMarketDate()
        val key = buildKey(today, code, buyDate, buyPrice, sellDate, sellPrice, qty, buyReason, sellReason, thesis)
        if (!force) {
            val cached = cache[key] ?: fileCache.get(key)?.also { cache[key] = it }
            if (cached != null) return cached
        }

        val name = master.findByCode(code)?.name ?: code
        val bars = dailyHistory.getHistory(code)
        val path = computePath(bars, buyDate, buyPrice, sellDate, sellPrice)

        val facts = buildFacts(code, name, buyDate, buyPrice, sellDate, sellPrice, qty, buyReason, sellReason, thesis, path)
        val model = modelRouter.modelFor(ModelRouter.TRADE_REVIEW)
        val raw = claude.complete(TRADE_REVIEW_PROMPT, facts, maxTokens = 1400, modelOverride = model)
        val (summary, body) = AnalysisService.parseSummaryFromComment(raw)

        val review = TradeReview(
            code = code, name = name, buyDate = buyDate, sellDate = sellDate,
            holdingTradingDays = path.holdingTradingDays,
            realizedPct = path.realizedPct,
            realizedPnl = qty?.let { ((sellPrice - buyPrice) * it).toLong() },
            periodHighClose = path.highClose, periodHighDate = path.highDate,
            periodLowClose = path.lowClose, periodLowDate = path.lowDate,
            sellVsHighPct = path.sellVsHighPct,
            afterSell5dPct = path.after5dPct, afterSell20dPct = path.after20dPct,
            partialHistory = path.partialHistory,
            summary = summary, comment = body,
            generatedAt = LocalDateTime.now(com.haky.edge.util.KST).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        )
        cache[key] = review
        fileCache.put(key, review)
        // Slack 아카이브 없음 — 매매 기록·사유는 포지션과 동일한 개인정보.
        return review
    }

    /** 일봉에서 계산한 가격 경로 사실. 전부 종가 기준. */
    internal data class TradePath(
        val holdingTradingDays: Int,
        val realizedPct: Double,
        val highClose: Long?,
        val highDate: String?,
        val lowClose: Long?,
        val lowDate: String?,
        val sellVsHighPct: Double?,
        val after5dPct: Double?,
        val after20dPct: Double?,
        val afterAvailableDays: Int,   // 매도 후 확보된 거래일 수(20 미만이면 추이 미완)
        val partialHistory: Boolean,
    )

    companion object {
        /** 사유·논지 공통 길이 상한 — facts 주입 텍스트라 토큰 방어(논지 200자와 같은 원리). */
        const val REASON_MAX_CHARS = 200

        private val YMD_DASH = DateTimeFormatter.ISO_LOCAL_DATE          // 2026-07-10 (요청 형식)
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")        // DailyBar.date 형식

        /** 캐시 키. 트레이드 필드 전체를 해시로 접는다(자유 텍스트 포함) + 날짜(사후 추이가 자라므로 당일 단위). */
        internal fun buildKey(
            today: String, code: String, buyDate: String, buyPrice: Double,
            sellDate: String, sellPrice: Double, qty: Long?,
            buyReason: String?, sellReason: String?, thesis: String?,
        ): String {
            val h = listOf(buyDate, buyPrice, sellDate, sellPrice, qty, buyReason?.trim(), sellReason?.trim(), thesis?.trim())
                .joinToString("|").hashCode()
            return "$code:$today:r$h"
        }

        /**
         * 일봉(최신이 앞)에서 보유 구간·사후 추이를 계산한다. buy/sell 기록가는 장중 체결가라
         * 봉 종가와 다를 수 있음 — 수익률은 기록가 기준, 경로(고저·추이)는 종가 기준으로 분리.
         * 비거래일 기록(주말 입력 등)은 구간 [buyDate, sellDate] 안의 거래일만 잡혀 자연 처리된다.
         */
        internal fun computePath(
            bars: List<DailyBar>,                 // 최신이 앞
            buyDate: String, buyPrice: Double,
            sellDate: String, sellPrice: Double,
        ): TradePath {
            val buyYmd = LocalDate.parse(buyDate, YMD_DASH).format(YMD)
            val sellYmd = LocalDate.parse(sellDate, YMD_DASH).format(YMD)
            val realizedPct = if (buyPrice > 0) (sellPrice - buyPrice) / buyPrice * 100 else 0.0

            val period = bars.filter { it.date in buyYmd..sellYmd }        // 최신이 앞 유지
            val oldestBar = bars.lastOrNull()?.date
            val partial = oldestBar != null && buyYmd < oldestBar          // 매수일이 이력 시작보다 과거

            val high = period.maxByOrNull { it.close }
            val low = period.minByOrNull { it.close }
            val sellVsHigh = high?.let { if (it.close > 0) (sellPrice - it.close) / it.close * 100 else null }

            // 매도 후 추이: 매도일 이후 봉(과거→미래 순으로 뒤집어 셈). n거래일 후 = after[n-1].
            val after = bars.filter { it.date > sellYmd }.sortedBy { it.date }
            fun afterPct(n: Int): Double? = after.getOrNull(n - 1)
                ?.let { if (sellPrice > 0) (it.close - sellPrice) / sellPrice * 100 else null }

            return TradePath(
                holdingTradingDays = period.size,
                realizedPct = realizedPct,
                highClose = high?.close, highDate = high?.date?.let { fmtDash(it) },
                lowClose = low?.close, lowDate = low?.date?.let { fmtDash(it) },
                sellVsHighPct = sellVsHigh,
                after5dPct = afterPct(5), after20dPct = afterPct(20),
                afterAvailableDays = after.size.coerceAtMost(20),
                partialHistory = partial,
            )
        }

        private fun fmtDash(ymd: String): String =
            if (ymd.length == 8) "${ymd.take(4)}-${ymd.substring(4, 6)}-${ymd.substring(6)}" else ymd

        internal fun buildFacts(
            code: String, name: String,
            buyDate: String, buyPrice: Double, sellDate: String, sellPrice: Double, qty: Long?,
            buyReason: String?, sellReason: String?, thesis: String?, path: TradePath,
        ): String = buildString {
            appendLine("종목: $name ($code)")
            appendLine()
            appendLine("[매매 기록 — 계산된 사실]")
            appendLine("- 매수: $buyDate, ${fmtWon(buyPrice)}" + (qty?.let { " × ${it}주" } ?: ""))
            appendLine("- 매도: $sellDate, ${fmtWon(sellPrice)}")
            appendLine("- 실현 수익률: ${signed(path.realizedPct)}% (보유 ${path.holdingTradingDays}거래일)")
            if (path.partialHistory) appendLine("- ⚠️ 매수일이 일봉 이력 범위(~500거래일)보다 과거 — 아래 구간 수치는 이력이 잡힌 구간만의 값")
            if (path.highClose != null) {
                appendLine("- 보유 구간 최고 종가: ${fmtWon(path.highClose.toDouble())} (${path.highDate}) — 매도가는 이 대비 ${signed(path.sellVsHighPct ?: 0.0)}%")
            }
            if (path.lowClose != null) {
                appendLine("- 보유 구간 최저 종가: ${fmtWon(path.lowClose.toDouble())} (${path.lowDate})")
            }
            when {
                path.after20dPct != null ->
                    appendLine("- 매도 후 추이(사후 정보): 5거래일 후 ${signed(path.after5dPct ?: 0.0)}%, 20거래일 후 ${signed(path.after20dPct)}% (매도가 대비 종가)")
                path.after5dPct != null ->
                    appendLine("- 매도 후 추이(사후 정보): 5거래일 후 ${signed(path.after5dPct)}% (20거래일은 아직 — 매도 후 ${path.afterAvailableDays}거래일 경과)")
                else ->
                    appendLine("- 매도 후 추이: 아직 없음(매도 후 ${path.afterAvailableDays}거래일 경과) — 타이밍 평가는 보류하고 과정 중심으로 복기")
            }
            appendLine()
            appendLine("[사용자 기록 — 주관이며 사실 아님, 점검 대상]")
            appendLine("- 매수 사유: ${buyReason?.trim()?.ifBlank { null } ?: "(기록 없음)"}")
            appendLine("- 매도 사유: ${sellReason?.trim()?.ifBlank { null } ?: "(기록 없음)"}")
            thesis?.trim()?.takeIf { it.isNotBlank() }?.let { appendLine("- 당시 투자 논지: \"$it\"") }
        }

        private fun fmtWon(v: Double): String = "%,d원".format(v.toLong())
        private fun signed(v: Double): String = if (v >= 0) "+%.1f".format(v) else "%.1f".format(v)

        // ── 시스템 프롬프트(캐시 대상) ────────────────────────────────────────
        // 이 복기의 가치는 "잘했다/못했다"가 아니라 과정과 결과를 분리해 배울 점을 남기는 것.
        // 결과편향·사후확신편향(hindsight bias)·아부가 3대 적 — 규칙이 전부 그 방어다.
        val TRADE_REVIEW_PROMPT = """
            너는 개인 투자자의 매매 복기를 돕는 조력자다. 완결된 매매 1건의 계산된 사실과
            사용자가 남긴 매수/매도 사유(주관 기록)를 받는다. 한국어로, 이미 끝난 매매를 복기한다.

            R1. 제공된 데이터에 없는 수치·가격·사건을 만들어내지 마라. 종목에 대한 너의 사전 지식으로
                당시 뉴스·실적을 추정해 서술하는 것도 금지 — 여기 있는 데이터가 전부다.
            R2. 과정과 결과를 분리해서 평가하라. 이 복기의 핵심이다:
                - 결과가 좋았어도(수익) 기록된 사유가 근거 없거나 데이터와 어긋났다면 그 점을 짚어라 — "맞았지만 운이 좋았을 수 있는" 매매와 "맞을 만해서 맞은" 매매를 구분하라.
                - 결과가 나빴어도(손실) 사유가 합리적이었고 기록대로 실행했다면 그 과정은 인정하라.
                - "결과가 좋으니 잘한 매매"라는 논리를 어떤 형태로도 쓰지 마라.
            R3. 매도 후 추이는 **사후 정보**다 — 매도 시점엔 알 수 없던 것. 타이밍을 되짚는 참고로만 쓰고,
                "팔지 말았어야 했다/더 빨리 팔았어야 했다"는 단정을 금지한다. 추이가 크게 갈렸다면
                "되돌아보면 ~였다, 단 그때 알 수 있던 정보는 아니다"의 형태로만 언급하라.
            R4. 매수/매도 사유와 논지는 사용자의 주관 기록이지 사실이 아니다 — 그 안의 주장·수치를
                복기의 근거로 인용하지 마라. 사유는 점검의 대상이다.
            R5. 아부 금지. 복기의 가치는 정직함에 있다 — 듣기 좋은 총평 대신, 다음에 확인할 수 있는
                구체적 관찰을 남겨라. 반대로 결과만 보고 다그치는 것도 금지다(R2·R3).
            R6. 이 한 건으로 일반화하지 마라(n=1). "반복 확인 포인트"는 단정이 아니라
                "다음 매매에서 확인해볼 것" 1~2개로만, 가설의 톤으로 제시하라.
            R7. 이미 끝난 매매다 — 이 종목의 향후 전망, 재진입 판단, 매매 지시를 하지 마라.
            R8. 형식: 첫 줄에 `### 핵심 요약` 소제목, 그 아래 2~3문장 요약, 빈 줄 하나, 이어서 본문
                3~4개 연속 문단(소제목·불릿·번호 목록 금지). 핵심 수치는 **굵게**. 전체 간결하게.
            R9. 어려운 금융 영어는 한국어로 풀거나 괄호 설명을 붙여라.

            [말미 재확인] 답하기 전에 확인하라 — ① 데이터 밖 수치·사건을 만들지 않았는가
            ② 결과로 과정을 정당화하거나 단죄하지 않았는가 ③ 사후 정보로 단정하지 않았는가
            ④ 사유 속 주장을 근거로 쓰지 않았는가 ⑤ 향후 전망·지시를 하지 않았는가.
        """.trimIndent()
    }
}
