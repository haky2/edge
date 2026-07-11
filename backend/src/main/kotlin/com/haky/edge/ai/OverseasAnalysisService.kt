package com.haky.edge.ai

import com.haky.edge.kis.KisClient
import com.haky.edge.kis.OverseasQuote
import com.haky.edge.master.OverseasMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NewsItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.haky.edge.util.DayScopedCache

/**
 * 해외 종목 간단 AI 코멘트(O4).
 *
 * 국내 AnalysisService와 달리 근거가 시세(15분 지연) + 한국어 뉴스 헤드라인뿐이다 —
 * 수급·공시·재무·목표주가·밸류밴드 등 국내 전용 소스는 해외에 없다. 프롬프트가 이 한계를
 * 명시해 모델이 학습 지식으로 없는 데이터를 아는 척하지 않게 막는다.
 *
 * 응답은 기존 `Analysis` DTO를 그대로 재사용한다(### 핵심 요약 파싱 계약 포함) —
 * 앱의 마크다운 렌더·요약 박스 코드를 해외 카드가 재사용할 수 있게.
 * 캐시: (code, date) 당일 전 유저 공유(인메모리+파일). 포지션·모드 구분 없음(간단 코멘트).
 * 모델: 기본 Opus(ModelRouter.OVERSEAS — 당일 캐시로 종목당 1일 1회 자연 상한, 사용자 결정 2026-07-08).
 */
class OverseasAnalysisService(
    private val kis: KisClient,
    private val naver: NaverNewsClient,
    private val overseasMaster: OverseasMaster,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
) {
    private val cache = DayScopedCache<Analysis>()
    private val fileCache = FileCache("overseas_analysis", Analysis.serializer())

    suspend fun analyze(code: String, excd: String, symb: String): Analysis {
        val today = effectiveMarketDate()
        val key = "$code:$today"
        cache.get(today, key)?.let { return it }
        fileCache.get(key)?.let { cache.put(today, key, it); return it }

        val info = overseasMaster.findByCode(code)
        val name = info?.name ?: symb
        val nameEn = info?.nameEn ?: symb

        val (quote, news) = coroutineScope {
            val q = async { kis.getOverseasPrice(excd, symb) }
            // 뉴스는 한글명 기준 한국어 기사 검색(애플·테슬라 등 국내 언론 커버리지 활용).
            // 키 미설정·검색 실패는 코멘트 자체를 막지 않는다 — 시세만으로도 생성.
            val n = async { runCatching { naver.search(name, display = 6) }.getOrDefault(emptyList()) }
            Pair(q.await(), n.await())
        }

        val facts = buildFacts(name, nameEn, quote, news, usMarketStatus())
        val model = modelRouter.modelFor(ModelRouter.OVERSEAS)
        val t0 = System.currentTimeMillis()
        val raw = claude.complete(OVERSEAS_PROMPT, facts, maxTokens = 1400, modelOverride = model)
        println("[Timing] $code: overseas-analysis claude=${System.currentTimeMillis() - t0}ms")
        val (summary, comment) = AnalysisService.parseSummaryFromComment(raw)

        val now = LocalTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("HH:mm"))
        val analysis = Analysis(
            code = code,
            name = name,
            date = today,
            comment = comment,
            summary = summary,
            generatedAt = now,
            generatedPrice = quote.price,
            factsRichness = FactsRichness(newsCount = news.size),
        )
        cache.put(today, key, analysis)
        fileCache.put(key, analysis)
        return analysis
    }

    /** 미국 정규장 상태(뉴욕 시간 09:30~16:00, 주중). 서머타임은 ZoneId가 알아서 처리. */
    internal fun usMarketStatus(nowNy: ZonedDateTime = ZonedDateTime.now(ZoneId.of("America/New_York"))): String {
        if (nowNy.dayOfWeek == DayOfWeek.SATURDAY || nowNy.dayOfWeek == DayOfWeek.SUNDAY) return "미국 휴장(주말)"
        val t = nowNy.toLocalTime()
        return when {
            t < LocalTime.of(9, 30) -> "미국 장 전"
            t < LocalTime.of(16, 0) -> "미국 장 중"
            else -> "미국 장 마감 후"
        }
    }

    companion object {
        /** 통화 기호 포맷 — USD는 $, 그 외 통화코드 접두. 소수 자릿수는 앱 표시와 동일 규칙. */
        internal fun priceText(price: Double, currency: String): String {
            val sym = if (currency == "USD") "$" else "$currency "
            val digits = if (price < 10) 4 else if (price < 100) 3 else 2
            return "%s%,.${digits}f".format(sym, price)
        }

        /** 52주 범위 내 현재가 위치(%). 밴드 폭이 0 이하(데이터 불량)면 null. */
        internal fun position52w(price: Double, high52w: Double, low52w: Double): Int? {
            val range = high52w - low52w
            if (range <= 0.0) return null
            return (((price - low52w) / range) * 100).toInt().coerceIn(0, 100)
        }

        internal fun buildFacts(
            name: String,
            nameEn: String,
            q: OverseasQuote,
            news: List<NewsItem>,
            marketStatus: String,
        ): String = buildString {
            val cur = q.currency
            appendLine("[종목] $name ($nameEn, ${q.symb}, 거래소 ${q.code.split(":").getOrElse(1) { "" }}, 통화 $cur)")
            appendLine("[현재 시장 상태] $marketStatus. 시세는 15분 지연 스냅샷.")
            val sign = if (q.change >= 0) "+" else ""
            appendLine("[시세] 현재가 ${priceText(q.price, cur)} (전일 대비 $sign${"%.2f".format(q.change)}, $sign${"%.2f".format(q.changeRate)}%)")
            appendLine("시가 ${priceText(q.open, cur)} · 고가 ${priceText(q.high, cur)} · 저가 ${priceText(q.low, cur)} · 거래량 ${"%,d".format(q.volume)}주")
            val pos = position52w(q.price, q.high52w, q.low52w)
            append("[52주] 고점 ${priceText(q.high52w, cur)} · 저점 ${priceText(q.low52w, cur)}")
            if (pos != null) append(" · 현재가는 52주 범위의 약 $pos% 위치(0%=저점, 100%=고점)")
            appendLine()
            if (news.isEmpty()) {
                appendLine("[관련 뉴스] 없음")
            } else {
                appendLine("[관련 뉴스 — 한국어 기사 검색 결과. 종목과 무관한 기사가 섞일 수 있음]")
                for (n in news) {
                    val date = formatNewsDate(n.publishedAt)
                    appendLine("- [${n.source}${if (date.isNotEmpty()) ", $date" else ""}] ${n.title} — ${n.description}")
                }
            }
        }

        /** RFC1123("Tue, 08 Jul 2026 09:30:00 +0900") → "7/8". 파싱 실패 시 빈 문자열. */
        internal fun formatNewsDate(pubDate: String): String = runCatching {
            val d = ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME)
            "${d.monthValue}/${d.dayOfMonth}"
        }.getOrDefault("")

        // 해외 전용 프롬프트 — 국내 프롬프트와 달리 근거가 시세+뉴스뿐임을 스스로 알게 한다.
        // 면책 고지는 넣지 않는다(앱 카드 footer가 항상 표시 — 토큰 절약).
        private val OVERSEAS_PROMPT = """
            너는 주식 투자 보조 앱의 해외 종목 간단 코멘트 어시스턴트다.
            독자는 주식에 관심 있는 일반인이다. 전문 용어에는 괄호로 짧은 설명을 붙인다.

            이 종목은 해외 상장 종목이라, 국내 종목과 달리 수급(외국인·기관 매매)·공시·재무·컨센서스 목표주가 데이터가 없다.
            근거는 아래 user 메시지 "사실 데이터"의 시세(15분 지연)와 한국어 뉴스 헤드라인이 전부다. 그 이상을 아는 척하지 마라.

            규칙(반드시 지킬 것):
            1. 사실 데이터에 있는 값만 근거로 쓴다. 거기 없는 수치(과거 주가·실적·목표주가·시가총액 등)는 절대 지어내지 마라 — 너의 학습 지식 속 이 회사의 수치는 낡아서 틀렸다.
            2. 응답은 첫 글자부터 "### 핵심 요약"으로 시작한다(그 앞에 아무것도 쓰지 마라). 요약은 2문장 산문. 그 다음 빈 줄 하나 후 **소제목** 단락 2~3개를 이어라(소제목 예: **오늘 흐름**, **뉴스 재료**, **종합**). 불릿·번호 목록·구분선 금지, 흐르는 문장으로.
            3. 가격은 사실 데이터의 통화 표기 그대로 쓰고(예: ${'$'}213.55), 등락률·가격 등 핵심 수치는 **굵게** 표시하라.
            4. 뉴스는 종목과 무관한 기사가 섞일 수 있다. 관련 있어 보이는 것만 쓰고 억지로 엮지 마라. 뉴스마다 날짜가 붙어 있다 — 3일 이상 지난 기사를 오늘의 재료처럼 쓰지 말고 "지난 ~일 보도된" 식으로 시점을 구분하라.
            5. "현재 시장 상태"에 맞게 가격 표현을 골라라 — 장 중이면 "거래 중", 장 마감 후면 "마감", 장 전·휴장이면 "전일 마감 기준".
            6. 52주 범위 내 위치는 참고 사실일 뿐이다. 고점권/저점권이라는 위치 자체로 비싸다/싸다를 단정하지 마라.
            7. "지금 사라/팔라"처럼 매매를 지시하지 마라. 근거 없는 단정 대신 "~로 보인다", "~일 수 있다"로 신중하게.
            8. 수급·공시·재무 데이터가 없다는 사실을 사과하거나 반복 언급하지 마라 — 있는 재료만 담백하게 다뤄라.
            9. 전체 분량은 핵심 요약 포함 4~5문단 이내로 짧게.

            마지막 경고(가장 중요): 가격·수치는 위 "사실 데이터"에서 그대로 복사해서만 쓴다. 특히 ### 핵심 요약에 쓰는 모든 수치는 사실 데이터에 존재하는 값이어야 한다.
        """.trimIndent()
    }
}
