package com.haky.edge.ai

import com.haky.edge.dart.DartClient
import com.haky.edge.dart.DartDisclosure
import com.haky.edge.kis.KisClient
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NewsItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import com.haky.edge.util.DayScopedCache
import com.haky.edge.util.writeTextAtomic
import java.util.concurrent.ConcurrentHashMap

/** 브리핑용: 섹터 단위로 묶은 재료 동향 한 줄. */
@Serializable
data class SectorCatalystLine(
    val sector: String,
    val bias: String,             // "호재우위" | "악재우위" | "혼조"
    val line: String,             // "종목A·종목B — 한 줄 요약"
    val stockNames: List<String>, // 해당 섹터 비중립 종목 이름 목록
)

/** 브리핑용: 관심종목 재료 동향 집계 결과. */
@Serializable
data class CatalystBriefReport(
    val date: String,
    val sectors: List<SectorCatalystLine>,
)

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
 * 재료(뉴스·DART 공시) 구조화 판정 엔진 — 슬라이스 1 + 1b(증분 캐싱).
 *
 * 판정을 세 층으로 분리해 "안 변하는 건 영구 캐시, 변하는 것만 새로 계산"한다:
 *  ① 재료 본질 판정(카테고리·호재/악재·강도·이유) = Claude, `code|url` 영구 캐시.
 *     기사 내용은 변하지 않으므로 한 번만 판정하고, 이후엔 **새 재료만** Claude에 보낸다.
 *     같은 뉴스라도 종목에 따라 호재/악재가 갈릴 수 있어 키는 (종목+url).
 *  ② 종합 summary(산문) = Claude, `code|url집합` 캐시. 재료 집합이 바뀔 때만 재생성.
 *     가격과 무관한 "재료 자체의 종합"이라 가격이 움직여도 재생성 불필요.
 *  ③ 선반영 + netBias = Kotlin 룰. 현재가 기준으로 매번 새로 계산(무료·항상 최신).
 *
 * 결과: 새 뉴스가 없으면 그날 Claude 호출 0, 가격이 움직여도 선반영은 룰로 갱신된다.
 * 캐시는 콜드스타트 대비 파일에 영속(기존 DART/analysis 캐시와 동일 철학).
 * API 응답(CatalystReport)은 슬라이스 1과 동일 → 앱 변경 없음.
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
    private val eventLog: CatalystEventLog = CatalystEventLog(),
) {
    // 30분 버킷 빠른 경로(재료 묶음 + 룰 결과 스냅샷). 같은 30분 내 재호출은 즉시.
    private val cache = DayScopedCache<CatalystReport>()
    private val fileCache = FileCache("catalysts", CatalystReport.serializer())
    // ① 재료 본질 판정 영구 캐시(code|url → 판정). ② summary 캐시(code|url집합 → 산문).
    private val verdictStore = PersistentMap("catalyst/verdicts.json", CatalystVerdict.serializer())
    private val summaryStore = PersistentMap("catalyst/summaries.json", String.serializer())
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun catalysts(code: String, days: Int = 7, force: Boolean = false): CatalystReport {
        val today = effectiveMarketDate()
        // 재료는 장중 언제든 추가될 수 있어 30분 버킷으로 스냅샷 캐시(선반영도 그 시점 가격 기준).
        val key = "$today|${System.currentTimeMillis() / 1_800_000}|$days|$code"
        if (!force) {
            cache.get(today, key)?.let { return it }
            fileCache.get(key)?.let { cache.put(today, key, it); return it }
        }

        return coroutineScope {
            val nameD          = async { master.findByCode(code)?.name ?: code }
            val quoteD         = async { kis.getPrice(code) }
            val disclosuresD   = async { runCatching { dart.getDisclosures(code, days) }.getOrElse { emptyList() } }
            val barsD          = async { runCatching { kis.getDailyChart(code, bars = 10) }.getOrElse { emptyList() } }
            val valuationBandD = async { runCatching { valuationBandSvc.getValuationBand(code) }.getOrNull() }
            // 연매출: 수주·계약 강도(상/중/하) 판정의 규모 기준. DART 캐시 재사용이라 가벼움.
            val financialsD    = async { runCatching { dart.getFinancials(code) }.getOrNull() }

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
                putReportCaches(key, lastKey(today, days, code), empty)
                return@coroutineScope empty
            }

            verdictStore.ensureLoaded()
            summaryStore.ensureLoaded()

            // ① 본질 판정: 캐시에 없는(새) 재료만 추려 Claude로. ② summary: 집합 바뀌면 재생성.
            val verdictKey = { m: Material -> "$code|${m.url}" }
            val newMaterials = materials.filter { verdictStore.get(verdictKey(it)) == null }
            val setKey = "$code|${materials.map { it.url }.sorted().joinToString("|").hashCode()}"
            val cachedSummary = summaryStore.get(setKey)

            var summary = cachedSummary ?: ""
            var newlyJudgedUrls: Set<String> = emptySet()
            if (newMaterials.isNotEmpty() || cachedSummary == null) {
                val model = modelRouter.modelFor(ModelRouter.CATALYST)
                val revenueEok = financialsD.await()?.revenue?.let { it / 100_000_000 }
                val userMsg = buildJudgeMessage(name, code, materials, revenueEok) { verdictStore.get(verdictKey(it)) }
                val raw = claude.complete(SYSTEM_PROMPT, userMsg, maxTokens = 2500, modelOverride = model)
                val (parsedSummary, verdictsByIdx) = parseJudge(raw)
                verdictsByIdx.forEach { (i, v) -> materials.getOrNull(i)?.let { verdictStore.put(verdictKey(it), v) } }
                newlyJudgedUrls = verdictsByIdx.keys.mapNotNull { materials.getOrNull(it)?.url }.toSet()
                if (parsedSummary.isNotBlank()) summary = parsedSummary
                summaryStore.put(setKey, summary)
                verdictStore.persist(); summaryStore.persist()
            }

            // ③ 선반영·netBias 룰(현재가 기준). 캐시된 본질 판정과 합쳐 카드 구성.
            val ctx = priceContext(quote, sectorRs, bars)
            val items = materials.map { m ->
                // 캐시·이번 판정에도 없으면(파싱 실패 등) 중립 폴백 — 카드에서 사라지지 않게(영구 캐시엔 안 넣음).
                val v = verdictStore.get(verdictKey(m)) ?: CatalystVerdict(m.ruleCat ?: "기타", "중립", "하", "")
                val (pre, note) = preReflectedRule(v.sentiment, ctx)
                CatalystItem(
                    source = m.source, category = v.category, title = m.title,
                    sentiment = v.sentiment, strength = v.strength, reason = v.reason,
                    preReflected = pre, preReflectedNote = note, url = m.url, date = m.date,
                )
            }
            // 이벤트 로그: 이번에 "처음" 판정된 재료만 append(중복 없음 — verdictStore 존재 여부가 게이트).
            // 판정 파싱 실패분(중립 폴백)은 verdictStore 미기록이라 다음 성공 판정 때 남는다.
            if (newlyJudgedUrls.isNotEmpty()) {
                val judgedAt = nowKstIso()
                eventLog.append(items.filter { it.url in newlyJudgedUrls }.map {
                    CatalystEvent(
                        code = code, date = it.date, source = it.source, category = it.category,
                        sentiment = it.sentiment, strength = it.strength,
                        preReflected = it.preReflected, url = it.url, judgedAt = judgedAt,
                    )
                })
            }

            val netBias = netBiasRule(items)
            val finalSummary = appendPreReflectedCaveat(summary, items, netBias)

            val report = CatalystReport(code, name, today, now, netBias, finalSummary, items)
            putReportCaches(key, lastKey(today, days, code), report)
            report
        }
    }

    /** 30분 버킷 키와 "당일 마지막 리포트" 키에 함께 저장 — 후자는 peekCached의 버킷 경과 폴백용. */
    private fun putReportCaches(bucketKey: String, lastKey: String, report: CatalystReport) {
        val today = report.date
        cache.put(today, bucketKey, report); fileCache.put(bucketKey, report)
        cache.put(today, lastKey, report); fileCache.put(lastKey, report)
    }

    private fun lastKey(today: String, days: Int, code: String) = "$today|last|$days|$code"

    /** 인덱싱용 내부 재료(우리 데이터 정본). ruleCat=공시 룰 분류 힌트, extra=뉴스 요약. */
    private data class Material(
        val source: String,
        val title: String,
        val url: String,
        val date: String,
        val ruleCat: String?,
        val extra: String?,
    )

    /** Claude가 판정하는 "재료 본질"(가격 무관 → code|url 영구 캐시 대상). */
    @Serializable
    private data class CatalystVerdict(
        val category: String,
        val sentiment: String,
        val strength: String,
        val reason: String,
    )

    /** 선반영 룰 입력값(현재 주가 맥락, 전부 nullable — 데이터 없으면 해당 조건 미적용). */
    private data class PriceCtx(val cum6d: Double?, val pos52w: Double?, val sectorRs: Double?)

    private fun priceContext(
        quote: com.haky.edge.kis.Quote,
        sectorRs: Double?,
        bars: List<com.haky.edge.kis.DailyBar>,
    ): PriceCtx {
        val pos52w = if (quote.high52w > quote.low52w && quote.high52w > 0)
            (quote.price - quote.low52w).toDouble() / (quote.high52w - quote.low52w) * 100 else null
        val rs = sectorRs?.let { quote.changeRate - it }
        val cum6d = run {
            val recent = bars.map { it.close }.take(6) // 최신일이 앞
            if (recent.size >= 2 && recent.last() > 0)
                (recent.first() - recent.last()).toDouble() / recent.last() * 100 else null
        }
        return PriceCtx(cum6d, pos52w, rs)
    }

    /**
     * 선반영 룰(현재가 기준). 호재인데 이미 급등/고점권/섹터강세면 "선반영 가능성", 악재는 대칭(이미 하락 반영).
     * 임계값은 휴리스틱 — 너무 빡빡하면 Opus가 잡던 선반영을 놓치므로 완만하게.
     */
    private fun preReflectedRule(sentiment: String, c: PriceCtx): Pair<Boolean, String?> {
        val notes = mutableListOf<String>()
        when (sentiment) {
            "호재" -> {
                if ((c.cum6d ?: 0.0) >= SURGE_PCT) notes += "최근 흐름 +${"%.1f".format(c.cum6d)}%"
                if ((c.pos52w ?: 0.0) >= HIGH_52W) notes += "52주 ${"%.0f".format(c.pos52w)}% 고점권"
                if ((c.sectorRs ?: 0.0) >= STRONG_RS) notes += "섹터 대비 +${"%.1f".format(c.sectorRs)}%p 강세"
            }
            "악재" -> {
                if ((c.cum6d ?: 0.0) <= -SURGE_PCT) notes += "최근 흐름 ${"%.1f".format(c.cum6d)}%"
                if ((c.pos52w ?: 100.0) <= LOW_52W) notes += "52주 ${"%.0f".format(c.pos52w)}% 저점권"
                if ((c.sectorRs ?: 0.0) <= -STRONG_RS) notes += "섹터 대비 ${"%.1f".format(c.sectorRs)}%p 약세"
            }
        }
        return if (notes.isEmpty()) false to null else true to notes.joinToString(" · ")
    }

    /** netBias 룰: 호재/악재 가중합(강도 상3·중2·하1). 양쪽이 비등하면 혼조. */
    private fun netBiasRule(items: List<CatalystItem>): String {
        fun w(s: String) = when (s) { "상" -> 3; "중" -> 2; else -> 1 }
        val pos = items.filter { it.sentiment == "호재" }.sumOf { w(it.strength) }
        val neg = items.filter { it.sentiment == "악재" }.sumOf { w(it.strength) }
        if (pos == 0 && neg == 0) return "중립"
        val lo = minOf(pos, neg); val hi = maxOf(pos, neg)
        if (lo > 0 && lo.toDouble() / hi >= 0.4) return "혼조"
        val net = pos - neg
        return when {
            net >= 2 -> "호재우위"
            net <= -2 -> "악재우위"
            else -> "중립"
        }
    }

    /** summary에 선반영 한 줄을 룰로 덧붙인다(가격 기준이라 캐시된 summary와 분리해 매번 신선). */
    private fun appendPreReflectedCaveat(summary: String, items: List<CatalystItem>, netBias: String): String {
        if (summary.isBlank()) return summary
        val caveat = when (netBias) {
            "호재우위" -> {
                val pos = items.filter { it.sentiment == "호재" }
                if (pos.isNotEmpty() && pos.count { it.preReflected } * 2 >= pos.size)
                    "다만 최근 주가 흐름상 상당수 호재가 선반영된 것으로 보입니다." else null
            }
            "악재우위" -> {
                val neg = items.filter { it.sentiment == "악재" }
                if (neg.isNotEmpty() && neg.count { it.preReflected } * 2 >= neg.size)
                    "다만 최근 하락으로 악재가 상당 부분 반영된 것으로 보입니다." else null
            }
            else -> null
        }
        return if (caveat == null) summary else "$summary $caveat"
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

    /** Claude 판정 요청 메시지. 캐시된 재료는 기존 판정을 보여주고(summary 맥락용), 새 재료만 "판정 필요"로 표시. */
    private fun buildJudgeMessage(
        name: String,
        code: String,
        materials: List<Material>,
        revenueEok: Long?,
        cached: (Material) -> CatalystVerdict?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("종목: $name ($code)")
        // 강도 판정의 규모 앵커. 없으면 모델이 학습 프라이어(낡았거나 중소형주는 아예 없음)로
        // "매출 대비 큼"을 감으로 정하게 되므로, 있는 경우 반드시 준다.
        if (revenueEok != null && revenueEok > 0) {
            sb.appendLine("참고: 이 회사 최근 연간 매출액 약 ${"%,d".format(revenueEok)}억원 — 수주·계약 규모의 상대 크기 판단 기준으로만 사용하라.")
        }
        sb.appendLine()
        sb.appendLine("[재료 목록]")
        materials.forEachIndexed { i, m ->
            val v = cached(m)
            val tag = if (v == null) "← 판정 필요"
                      else "← 이미 판정됨: ${v.sentiment}·${v.strength}(${v.category})"
            val hint = m.ruleCat?.let { " (공시분류 힌트: $it)" } ?: ""
            sb.appendLine("$i. [${m.source}]$hint ${m.title}  $tag")
            if (m.extra != null) sb.appendLine("   요약: ${m.extra}")
        }
        return sb.toString().trim()
    }

    /** Claude 응답 → (summary, 새로 판정한 재료 인덱스별 본질 판정). 파싱 실패 시 ("", emptyMap). */
    private fun parseJudge(raw: String): Pair<String, Map<Int, CatalystVerdict>> {
        val json = extractJsonObject(raw) ?: return "" to emptyMap()
        val summary = json["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: ""
        val verdicts = mutableMapOf<Int, CatalystVerdict>()
        (json["items"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val i = o["i"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
            verdicts[i] = CatalystVerdict(
                category = o.str("category") ?: "기타",
                sentiment = o.str("sentiment") ?: "중립",
                strength = o.str("strength") ?: "하",
                reason = o.str("reason") ?: "",
            )
        }
        return summary to verdicts
    }

    private fun JsonObject.str(k: String): String? =
        this[k]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() && it != "null" }

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

    private fun nowKstIso(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    /**
     * 작은 영속 맵(code|key → V). 인메모리 + 단일 JSON 파일(CACHE_DIR).
     * 콜드스타트 시 1회 로드, 새 값 추가 후 persist()로 저장. 날짜 무관(영구) — FileCache와 별개.
     */
    private class PersistentMap<V>(fileName: String, valueSer: KSerializer<V>) {
        private val file = File("${System.getenv("CACHE_DIR") ?: ".cache"}/$fileName").also { it.parentFile?.mkdirs() }
        private val ser = MapSerializer(String.serializer(), valueSer)
        private val json = Json { ignoreUnknownKeys = true }
        private val map = ConcurrentHashMap<String, V>()
        private val mutex = Mutex()
        @Volatile private var loaded = false

        suspend fun ensureLoaded() {
            if (loaded) return
            mutex.withLock {
                if (loaded) return
                runCatching { if (file.exists()) json.decodeFromString(ser, file.readText()).forEach { (k, v) -> map[k] = v } }
                loaded = true
            }
        }

        fun get(k: String): V? = map[k]
        fun put(k: String, v: V) { map[k] = v }

        suspend fun persist() {
            mutex.withLock { runCatching { file.writeTextAtomic(json.encodeToString(ser, HashMap(map))) } }
        }
    }

    /** 캐시에 있는 재료 판정만 조회 — Claude 호출 없음. 캐시 미스면 null 반환. */
    fun peekCached(code: String, days: Int = 7): CatalystReport? {
        val today = effectiveMarketDate()
        val key = "$today|${System.currentTimeMillis() / 1_800_000}|$days|$code"
        cache.get(today, key)?.let { return it }
        fileCache.get(key)?.also { cache.put(today, key, it) }?.let { return it }
        // 현재 30분 버킷에 없어도 오늘 만들어진 마지막 리포트로 폴백 — 같은 버킷 내 조회에만
        // 의존하면 브리핑 "테마별 재료 동향"이 사실상 늘 비게 된다(2026-07 감사 M3).
        val lk = lastKey(today, days, code)
        cache.get(today, lk)?.let { return it }
        return fileCache.get(lk)?.also { cache.put(today, lk, it) }
    }

    /**
     * 관심종목 재료 동향을 섹터별로 묶어 브리핑용 한 줄씩 반환.
     * 오직 캐시된 판정만 사용(Claude 미호출). 판정 미캐시 종목은 조용히 제외.
     * 섹터 결정은 MacroImpactService 7일 캐시 + MANUAL_OVERRIDES 재사용(거의 즉시).
     */
    suspend fun brief(codes: List<String>): CatalystBriefReport = coroutineScope {
        val today = effectiveMarketDate()
        val reports = codes.mapNotNull { code -> peekCached(code)?.let { code to it } }.toMap()

        data class SS(val report: CatalystReport, val sector: String)
        val stockSectors = codes.map { code ->
            async {
                val rpt = reports[code] ?: return@async null
                val sectors = runCatching {
                    macroImpact.resolveStockSectors(code, rpt.name, "")
                }.getOrElse { emptyList() }
                val label = if (sectors.isEmpty()) "기타" else sectors.first().label
                SS(rpt, label)
            }
        }.awaitAll().filterNotNull()

        val groups = stockSectors.groupBy { it.sector }
        val lines = groups.mapNotNull { (sector, stocks) ->
            val notable = stocks.filter { it.report.netBias !in listOf("중립", "") }
            if (notable.isEmpty()) return@mapNotNull null

            val pos = notable.count { it.report.netBias == "호재우위" }
            val neg = notable.count { it.report.netBias == "악재우위" }
            val bias = when {
                pos > neg -> "호재우위"
                neg > pos -> "악재우위"
                else -> "혼조"
            }

            val best = notable.maxByOrNull { briefBiasScore(it.report.netBias) }
            val snippet = best?.report?.summary
                ?.substringBefore("다만")?.substringBefore(".")?.trim()?.take(35) ?: ""
            val nameStr = notable.take(2).joinToString("·") { it.report.name }
            val line = if (snippet.isNotBlank()) "$nameStr — $snippet" else nameStr

            SectorCatalystLine(
                sector = sector,
                bias = bias,
                line = line,
                stockNames = notable.map { it.report.name },
            )
        }.sortedWith(compareBy { briefBiasOrder(it.bias) })

        CatalystBriefReport(date = today, sectors = lines)
    }

    private fun briefBiasScore(bias: String) = when (bias) { "호재우위" -> 3; "혼조" -> 2; "악재우위" -> 1; else -> 0 }
    private fun briefBiasOrder(bias: String) = when (bias) { "악재우위" -> 0; "혼조" -> 1; "호재우위" -> 2; else -> 3 }

    companion object {
        // 선반영 룰 임계값(휴리스틱).
        private const val SURGE_PCT = 10.0   // 최근 6거래일 누적 등락(%) — 호재 급등/악재 급락 기준
        private const val HIGH_52W = 80.0    // 52주 위치(%) 이상이면 고점권
        private const val LOW_52W = 20.0     // 52주 위치(%) 이하면 저점권
        private const val STRONG_RS = 1.5    // 섹터 상대강도(%p) — 강/약세 기준

        // 재료 본질 판정 시스템 프롬프트(캐시 대상). JSON만 출력. 선반영·netBias는 앱(백엔드 룰)이 처리하므로 여기선 다루지 않는다.
        private val SYSTEM_PROMPT = """
            너는 한국 주식 재료(뉴스·DART 공시) 판정 엔진이다.
            입력으로 한 종목의 "재료 목록"(인덱스 번호 포함)을 받는다. 각 재료 끝에 "판정 필요" 또는
            "이미 판정됨: ..."이 표시돼 있다.

            할 일:
            (1) "판정 필요" 항목만 판정해 items 배열에 담는다(이미 판정된 항목은 items에 넣지 마라).
            (2) 전체 재료(이미 판정된 것 포함)를 종합해 summary 한두 문장을 쓴다.

            반드시 아래 JSON "객체 하나만" 출력하라. 코드펜스(```)·설명·서두 텍스트 금지.
            {
              "summary": "이 종목의 재료를 1~2문장으로 종합(가장 중요한 재료 중심, 호재/악재 방향 포함). 한국어.",
              "items": [
                {
                  "i": 재료 인덱스(정수, '판정 필요' 항목만),
                  "category": "수주·공급계약" | "실적" | "유상증자·CB" | "자사주" | "배당" | "정책·규제" | "소송·제재" | "지분변동" | "정정" | "기타",
                  "sentiment": "호재" | "악재" | "중립",
                  "strength": "상" | "중" | "하",
                  "reason": "왜 그렇게 봤는지 한 줄(한국어, 30자 내외). 수치는 재료에 있는 것만."
                }
              ]
            }

            판정 규칙:
            1. 재료 목록에 있는 사실만 근거로 삼아라. 없는 수치·내용을 지어내지 마라. 너의 학습 지식 속 이 회사의 매출·실적 기억은 낡았으니 쓰지 마라.
            2. 강도(상/중/하): 매출·실적·주가에 미치는 영향 크기로. 대규모 수주(기존 매출 대비 큼)·흑자전환·대형 계약=상,
               통상적 계약·소폭 변동=중, 관계 약하거나 단순 보도·일정성=하.
               규모 판단은 "참고: 연간 매출액"이 주어졌으면 그 대비 비중으로 하라. 계약·수주 금액이 재료 텍스트에
               없으면 규모를 알 수 없으므로 강도를 "상"으로 주지 마라(최대 "중").
            3. 종목과 무관해 보이는 뉴스(동명이인·다른 회사·시황 일반)는 sentiment="중립", strength="하", reason에 "종목 관련성 낮음".
            4. 유상증자·CB는 보통 주식가치 희석이라 악재 쪽이나, 시설투자·대형 수주 대응 목적이면 강도를 낮춰 신중히.
               정정 공시는 원 공시 방향에 따라가되 불확실하면 중립.
            5. summary는 "선반영/이미 올랐다" 같은 주가 위치 판단은 넣지 마라(그건 별도 처리한다). 재료 자체의 내용·방향만 종합하라.
            6. "사라/팔라" 같은 매매 지시는 절대 하지 마라. 판정과 근거만.
        """.trimIndent()
    }
}
