package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.dart.DartDisclosure
import com.haky.edge.kis.KisClient
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NewsItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * 재료 1건의 구조화 판정. AnalysisService의 산문 코멘트와 달리, 뉴스·공시를
 * "카드 단위"로 호재/악재·강도·선반영까지 떨어뜨린다.
 */
@Serializable
data class CatalystItem(
    val source: String,            // "공시" | "뉴스"
    val category: String,          // 수주·공급계약/실적/유상증자·CB/자사주/배당/정책·규제/소송·제재/지분변동/정정/기타
    val title: String,
    val sentiment: String,         // "호재" | "악재" | "중립"
    val strength: String,          // "상" | "중" | "하"
    val reason: String,            // 한 줄 이유
    val preReflected: Boolean,     // 이미 주가에 반영됐을 가능성
    val preReflectedNote: String? = null, // 선반영 근거(있을 때만)
    val url: String,
    val date: String,              // YYYYMMDD 또는 뉴스 발행 표기
)

/** 종목별 재료 종합 리포트. */
@Serializable
data class CatalystReport(
    val code: String,
    val name: String,
    val date: String,              // 생성 기준일(YYYY-MM-DD)
    val generatedAt: String = "",  // 생성 시각 HH:mm(KST)
    val netBias: String,           // "호재우위" | "악재우위" | "혼조" | "중립"
    val summary: String,           // 1~2문장 종합
    val items: List<CatalystItem> = emptyList(),
)

/**
 * 재료(뉴스·DART 공시) 구조화 판정 엔진 — 슬라이스 1(관심종목 코어).
 *
 * 원칙(AnalysisService와 동일): **사실은 우리가 수집 → Claude는 판정만.** url·날짜·제목은
 * 우리 데이터에서 그대로 쓰고(환각 url 방지), Claude는 인덱스별 판정값만 돌려준다.
 * 선반영 점검을 위해 밸류밴드 위치·섹터 상대강도·52주 위치·최근 가격 흐름을 함께 넣는다.
 * 비용: (code,date,30분버킷) 인메모리+파일 캐시로 전 유저 공유.
 */
class CatalystService(
    private val kis: KisClient,
    private val naver: NaverNewsClient,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val dart: DartClient,
    private val valuationBandSvc: ValuationBandService,
    private val macroImpact: MacroImpactService,
    private val modelRouter: ModelRouter,
) {
    private val cache = ConcurrentHashMap<String, CatalystReport>()
    private val fileCache = FileCache("catalysts", CatalystReport.serializer())
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun catalysts(code: String, days: Int = 7, force: Boolean = false): CatalystReport {
        val today = effectiveMarketDate()
        // 재료는 장중 언제든 추가될 수 있어 30분 버킷으로 캐시(공시 캐시와 동일 정책).
        val key = "$today|${System.currentTimeMillis() / 1_800_000}|$days|$code"
        if (!force) {
            cache[key]?.let { return it }
            fileCache.get(key)?.let { cache[key] = it; return it }
        }

        return coroutineScope {
            val nameD          = async { master.search(code).firstOrNull { it.code == code }?.name ?: code }
            val quoteD         = async { kis.getPrice(code) }
            val disclosuresD   = async { runCatching { dart.getDisclosures(code, days) }.getOrElse { emptyList() } }
            val barsD          = async { runCatching { kis.getDailyChart(code, bars = 10) }.getOrElse { emptyList() } }
            val valuationBandD = async { runCatching { valuationBandSvc.getValuationBand(code) }.getOrNull() }

            val name = nameD.await()
            val newsD = async { runCatching { naver.search(name, display = 30) }.getOrElse { emptyList() } }

            val quote = quoteD.await()
            val sectorRsD = async { runCatching { macroImpact.sectorIndexChangeRate(code, name, quote.sectorName) }.getOrNull() }

            val disclosures   = disclosuresD.await()
            val bars          = barsD.await()
            val valuationBand = valuationBandD.await()
            val news          = dedupeNews(newsD.await(), limit = 8)
            val sectorRs      = sectorRsD.await()

            // 재료 인덱싱: 공시 먼저(객관·우선), 그다음 뉴스. url/제목/날짜는 우리 데이터 정본.
            val materials = buildList {
                disclosures.forEach { add(Material("공시", it.reportName, it.url, it.date, ruleCategory(it), null)) }
                news.forEach { add(Material("뉴스", it.title, it.url, it.publishedAt, null, it.description.ifBlank { null })) }
            }

            val now = nowKstHm()
            if (materials.isEmpty()) {
                val empty = CatalystReport(code, name, today, now, "중립", "최근 ${days}일 새 재료(공시·뉴스)가 없습니다.")
                cache[key] = empty; fileCache.put(key, empty)
                return@coroutineScope empty
            }

            val context = buildContext(quote, valuationBand, sectorRs, bars)
            val userMsg = buildUserMessage(name, code, context, materials)
            val model = modelRouter.modelFor(ModelRouter.CATALYST)
            val raw = claude.complete(SYSTEM_PROMPT, userMsg, maxTokens = 3000, modelOverride = model)

            val report = parseReport(raw, code, name, today, now, materials)
            cache[key] = report; fileCache.put(key, report)
            report
        }
    }

    /** 인덱싱용 내부 재료(우리 데이터 정본). ruleCat=공시 룰 분류 힌트, extra=뉴스 요약. */
    private data class Material(
        val source: String,
        val title: String,
        val url: String,
        val date: String,
        val ruleCat: String?,
        val extra: String?,
    )

    /** 선반영 판정용 컨텍스트 — 지금 주가가 어느 수준/흐름인지 사실만. */
    private fun buildContext(
        quote: com.haky.edge.kis.Quote,
        band: ValuationBand?,
        sectorRs: Double?,
        bars: List<com.haky.edge.kis.DailyBar>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("현재가: ${quote.price}원 (전일대비 ${quote.change}, ${quote.changeRate}%)")
        if (quote.high52w > quote.low52w && quote.high52w > 0) {
            val pos = (quote.price - quote.low52w).toDouble() / (quote.high52w - quote.low52w) * 100
            sb.appendLine("52주 위치: ${"%.0f".format(pos)}% (최고 ${quote.high52w} / 최저 ${quote.low52w})")
        }
        if (sectorRs != null) {
            val rs = quote.changeRate - sectorRs
            val label = if (rs > 0.5) "섹터 대비 강세" else if (rs < -0.5) "섹터 대비 약세" else "섹터 수준"
            sb.appendLine("섹터 상대강도: ${if (rs >= 0) "+" else ""}${"%.1f".format(rs)}%p ($label)")
        }
        if (band != null && band.yearsUsed > 0 && band.perCurrent > 0) {
            sb.appendLine("밸류 위치: PER ${"%.1f".format(band.perCurrent)}배 (${band.perLabel}), PBR ${"%.2f".format(band.pbrCurrent)}배 (${band.pbrLabel})")
        }
        // 최근 흐름: 이미 급등했으면 선반영 가능성 ↑
        if (bars.size >= 2) {
            val closes = bars.map { it.close } // 최신일이 앞
            val recent = closes.take(6)
            if (recent.size >= 2 && recent.last() > 0) {
                val cum = (recent.first() - recent.last()).toDouble() / recent.last() * 100
                sb.appendLine("최근 ${recent.size}거래일 누적 등락: ${if (cum >= 0) "+" else ""}${"%.1f".format(cum)}%")
            }
        }
        return sb.toString().trim()
    }

    private fun buildUserMessage(name: String, code: String, context: String, materials: List<Material>): String {
        val sb = StringBuilder()
        sb.appendLine("종목: $name ($code)")
        sb.appendLine()
        sb.appendLine("[현재 주가 맥락 — 선반영 판정에 사용]")
        sb.appendLine(context)
        sb.appendLine()
        sb.appendLine("[재료 목록 — 각 항목을 판정하라]")
        materials.forEachIndexed { i, m ->
            val hint = m.ruleCat?.let { " (공시분류 힌트: $it)" } ?: ""
            sb.appendLine("$i. [${m.source}]$hint ${m.title}")
            if (m.extra != null) sb.appendLine("   요약: ${m.extra}")
        }
        return sb.toString().trim()
    }

    /** DART 보고서명 룰 기반 1차 분류(토큰 절약·일관성). 모델이 최종 결정하되 강한 힌트로 쓴다. */
    private fun ruleCategory(d: DartDisclosure): String {
        val n = d.reportName.replace(" ", "")
        return when {
            n.contains("정정") -> "정정"
            n.contains("단일판매") || n.contains("공급계약") || n.contains("수주") -> "수주·공급계약"
            n.contains("유상증자") || n.contains("전환사채") || n.contains("신주인수권") || n.contains("교환사채") || n.contains("CB") -> "유상증자·CB"
            n.contains("자기주식") || n.contains("자사주") -> "자사주"
            n.contains("현금·현물배당") || n.contains("배당") -> "배당"
            n.contains("분기보고서") || n.contains("반기보고서") || n.contains("사업보고서") || n.contains("영업실적") || n.contains("잠정") -> "실적"
            n.contains("최대주주") || n.contains("주식등의대량보유") || n.contains("임원·주요주주") || n.contains("소유상황") -> "지분변동"
            n.contains("소송") || n.contains("제재") || n.contains("벌금") -> "소송·제재"
            else -> "기타"
        }
    }

    /** Claude JSON 응답 → CatalystReport. url/제목/날짜/source는 우리 materials에서 병합(환각 방지). */
    private fun parseReport(
        raw: String,
        code: String,
        name: String,
        today: String,
        generatedAt: String,
        materials: List<Material>,
    ): CatalystReport {
        val json = extractJsonObject(raw)
            ?: return CatalystReport(code, name, today, generatedAt, "중립", "판정 결과를 해석하지 못했습니다.")
        val netBias = json["netBias"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "중립"
        val summary = json["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: ""
        val items = (json["items"] as? JsonArray).orEmptyArray().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val i = o["i"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val m = materials.getOrNull(i) ?: return@mapNotNull null
            val preReflected = o["preReflected"]?.jsonPrimitive?.let {
                it.contentOrNull == "true" || it.contentOrNull == "1"
            } ?: false
            CatalystItem(
                source = m.source,
                category = o.str("category") ?: m.ruleCat ?: "기타",
                title = m.title,
                sentiment = o.str("sentiment") ?: "중립",
                strength = o.str("strength") ?: "하",
                reason = o.str("reason") ?: "",
                preReflected = preReflected,
                preReflectedNote = o.str("preReflectedNote"),
                url = m.url,
                date = m.date,
            )
        }
        return CatalystReport(code, name, today, generatedAt, netBias, summary, items)
    }

    private fun JsonObject.str(k: String): String? =
        this[k]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

    /** 응답에서 첫 JSON 객체를 추출(```json 펜스·서두 텍스트 방어). */
    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { parser.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    // ── 뉴스 유사기사 클러스터링(AnalysisService와 동일 정책: 제목 0.5 + 요약 0.6) ──
    private fun dedupeNews(items: List<NewsItem>, limit: Int): List<NewsItem> {
        data class Rep(val item: NewsItem, val t: Set<String>, val d: Set<String>)
        val reps = mutableListOf<Rep>()
        for (n in items) {
            val tt = tokens(n.title)
            val dt = tokens(n.description)
            val dup = reps.firstOrNull { jaccard(tt, it.t) >= 0.5 && jaccard(dt, it.d) >= 0.6 }
            if (dup == null) reps.add(Rep(n, tt, dt))
        }
        return reps.take(limit).map { it.item }
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    private fun nowKstHm(): String =
        java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    companion object {
        // 재료 판정 시스템 프롬프트(캐시 대상). JSON만 출력 — UI 카드가 직접 파싱.
        private val SYSTEM_PROMPT = """
            너는 한국 주식 재료(뉴스·DART 공시) 판정 엔진이다.
            입력으로 한 종목의 "현재 주가 맥락"과 "재료 목록"(인덱스 번호 포함)을 받는다.
            각 재료가 그 종목 주가에 호재/악재/중립 중 무엇이고 강도가 어느 정도인지, 그리고
            이미 주가에 반영됐을(선반영) 가능성이 있는지 판정한다.

            반드시 아래 JSON "객체 하나만" 출력하라. 코드펜스(```)·설명·서두 텍스트 금지.
            {
              "netBias": "호재우위" | "악재우위" | "혼조" | "중립",
              "summary": "이 종목의 재료를 1~2문장으로 종합(가장 중요한 재료 중심). 한국어.",
              "items": [
                {
                  "i": 재료 인덱스(정수),
                  "category": "수주·공급계약" | "실적" | "유상증자·CB" | "자사주" | "배당" | "정책·규제" | "소송·제재" | "지분변동" | "정정" | "기타",
                  "sentiment": "호재" | "악재" | "중립",
                  "strength": "상" | "중" | "하",
                  "reason": "왜 그렇게 봤는지 한 줄(한국어, 30자 내외). 수치는 재료에 있는 것만.",
                  "preReflected": true | false,
                  "preReflectedNote": "선반영으로 본 근거(주가가 이미 급등/52주 고점권/섹터 대비 강세 등). preReflected=false면 빈 문자열."
                }
              ]
            }

            판정 규칙:
            1. 재료 목록에 있는 사실만 근거로 삼아라. 없는 수치·내용을 지어내지 마라.
            2. 강도(상/중/하): 매출·실적·주가에 미치는 영향 크기로. 대규모 수주(기존 매출 대비 큼)·흑자전환·대형 계약=상,
               통상적 계약·소폭 변동=중, 관계 약하거나 단순 보도·일정성=하.
            3. 종목과 무관해 보이는 뉴스(동명이인·다른 회사·시황 일반)는 sentiment="중립", strength="하", reason에 "종목 관련성 낮음".
            4. 선반영(preReflected): "현재 주가 맥락"을 보고 판단하라. 호재인데 이미 최근 급등했거나 52주 고점권이거나
               섹터 대비 강세면 preReflected=true로 두고 근거를 적어라. 새로 나온 정보로 보이면 false.
            5. 유상증자·CB는 보통 주식가치 희석이라 악재 쪽이나, 시설투자·대형 수주 대응 목적이면 강도를 낮춰 신중히.
               정정 공시는 원 공시 방향에 따라가되 불확실하면 중립.
            6. "사라/팔라" 같은 매매 지시는 절대 하지 마라. 판정과 근거만.
            7. items는 입력된 모든 재료 인덱스를 빠짐없이 포함하라.
        """.trimIndent()
    }
}
