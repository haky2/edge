package com.haky.edge.ai

import com.haky.edge.util.writeTextAtomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// ── DTO ───────────────────────────────────────────────────────────────────

/** 회사가 직접 밝힌 가이던스 1건. sourceUrl은 검색 출처 목록에 실재하는 URL만(아니면 빈 문자열). */
@Serializable
data class GuidanceItem(
    val topic: String,        // 항목(예: "하반기 수주 목표")
    val statement: String,    // 수치/문구(노트 표현 유지)
    val sourceUrl: String = "",
    val saidAt: String = "",  // 발화 시점(예: "7/15 컨콜", 모르면 노트 날짜)
)

/** 한 종목의 최신 가이던스 수집 결과. items가 비면 "찾지 못함"(수집은 했음 — rceptNo 캐시 유지). */
@Serializable
data class Guidance(
    val code: String,
    val name: String,
    val periodLabel: String,  // 어느 실적에 대한 수집인가("2026년 반기")
    val rceptNo: String,      // 트리거 정기보고서 접수번호 — (code, rceptNo) 캐시 키
    val items: List<GuidanceItem> = emptyList(),
    val collectedAt: String,  // YYYY-MM-DD (KST)
)

/**
 * 실적 가이던스 추출(N2) — "회사가 말한 것"을 실적 리뷰에 병기한다.
 *
 * 실적 리뷰(F3 3c)는 실제 vs run-rate 예상을 채점하지만 회사가 직접 밝힌 전망(가이던스)이 없다.
 * DeepResearch의 검증된 2단계 구조를 재사용해 환각 리스크를 통제한다:
 *   1단계: completeWithWebSearch(기본 Sonnet, 수집 전용) — "출처·날짜 병기 노트"만.
 *          중간 서술 오염을 노트 형식 강제로 회피(EventSync·DeepResearch 패턴).
 *   2단계: complete()로 노트→JSON 구조화만(합성 서술 없음). ModelRouter.GUIDANCE 기본 Opus
 *          (분기당 종목 1회 저빈도·발언/추정 구분이 고판단 지점).
 *
 * 환각 가드: ① 2단계는 "노트에 없는 수치 생성 금지·없으면 빈 배열" ② sourceUrl은 검색이
 * 실제 반환한 URL 목록과 대조해 불일치는 빈 문자열로(코드 레벨 후검증) ③ 목표주가·주가
 * 전망은 수집 제외(목표가 파이프라인과 혼선 방지).
 *
 * 비용 캡: (code, rceptNo) 캐시 = 분기당 종목 1회 자연 캡(빈 결과도 캐시 — 검색 재과금 방지)
 *          + GUIDANCE_DAILY_LIMIT(기본 5, 초과 시 조용히 skip — 다음 스캔 재시도).
 * 저장: {DATA_DIR}/guidance.json — code→최신 1건(PremortemService 패턴). earnings-preview가
 *       조회 시점에 붙인다(LLM 0 읽기 경로).
 */
class GuidanceService(
    private val master: com.haky.edge.master.StockMaster,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
    dailyLimit: Int = 5,
    dataDir: String = System.getenv("DATA_DIR") ?: ".data",
) {
    private val file = File(dataDir, "guidance.json").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), Guidance.serializer())
    private val store = ConcurrentHashMap<String, Guidance>()
    private val mutex = Mutex()
    @Volatile private var loaded = false
    private val budget = DailyBudget(dailyLimit)

    /** 최신 가이던스(있으면). earnings-preview 응답에 붙이는 읽기 전용 경로. */
    suspend fun latest(code: String): Guidance? {
        ensureLoaded()
        return store[code]
    }

    /**
     * 실적 리뷰 발화 시 호출 — 이 보고서(rceptNo)에 대한 가이던스를 수집(멱등).
     * 같은 rceptNo는 저장본 반환(재검색 없음). 일일 한도 초과·검색 실패는 null(다음 스캔 재시도).
     */
    suspend fun collectForReview(code: String, reportName: String, rceptNo: String, periodLabel: String): Guidance? {
        ensureLoaded()
        store[code]?.takeIf { it.rceptNo == rceptNo }?.let { return it }
        if (!budget.tryTick(kstToday())) {
            println("[Guidance] $code: 일일 한도 초과 — skip(다음 스캔 재시도)")
            return null
        }

        val name = master.findByCode(code)?.name ?: code
        val t0 = System.currentTimeMillis()

        // 1단계 — 웹검색 수집(노트만, 기본 Sonnet).
        val gathered = claude.completeWithWebSearch(
            systemPrompt = SEARCH_PROMPT,
            userFacts = "종목: $name ($code). 발표된 실적: $periodLabel ($reportName). " +
                "이 회사가 실적 발표·컨퍼런스콜·IR에서 직접 밝힌 가이던스를 검색해 노트로 정리:",
            maxTokens = 1000,
            maxSearchUses = 3,
        )
        val notes = gathered.text.trim()

        // 2단계 — 노트→JSON 구조화(GUIDANCE 트리거). 노트가 없으면 LLM 생략.
        val items = if (notes.isBlank() || notes == "없음") emptyList() else {
            val model = modelRouter.modelFor(ModelRouter.GUIDANCE)
            val raw = claude.complete(
                systemPrompt = STRUCTURE_PROMPT,
                userFacts = renderStage2Input(notes, gathered.sources.map { it.title to it.url }),
                maxTokens = 1200,
                modelOverride = model,
            )
            val parsed = parseItems(raw)
            if (parsed == null) println("[Guidance] $code: JSON 파싱 실패 — 빈 배열로 저장")
            guardSourceUrls(parsed ?: emptyList(), gathered.sources.map { it.url })
        }
        println("[Guidance] $code: ${System.currentTimeMillis() - t0}ms items=${items.size} sources=${gathered.sources.size}")

        val result = Guidance(
            code = code, name = name, periodLabel = periodLabel, rceptNo = rceptNo,
            items = items, collectedAt = kstToday(),
        )
        // 빈 결과도 저장 — rceptNo 캐시가 없으면 매 스캔 재검색(검색 과금 반복).
        mutex.withLock { store[code] = result; persistLocked() }
        return result
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            runCatching {
                if (file.exists()) json.decodeFromString(mapSer, file.readText()).forEach { (k, v) -> store[k] = v }
            }
            loaded = true
        }
    }

    private fun persistLocked() {
        runCatching { file.writeTextAtomic(json.encodeToString(mapSer, HashMap(store))) }
    }

    private fun kstToday(): String = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()

    /** 일일 수집 예산 — 초과 시 예외 없이 false(스캔 문맥에서 조용히 skip해야 해서 DailyLimiter와 다름). */
    internal class DailyBudget(private val limit: Int) {
        private var date = ""
        private var count = 0

        @Synchronized
        fun tryTick(today: String): Boolean {
            if (date != today) { date = today; count = 0 }
            if (count >= limit) return false
            count++
            return true
        }
    }

    companion object {
        private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 2단계 입력 — 노트 + 출처 URL 목록(모델이 sourceUrl을 실재 URL로만 채울 재료). */
        internal fun renderStage2Input(notes: String, sources: List<Pair<String, String>>): String = buildString {
            appendLine("[리서치 노트]")
            appendLine(notes.take(3000))
            if (sources.isNotEmpty()) {
                appendLine()
                appendLine("[출처 URL 목록 — sourceUrl은 반드시 이 중에서만]")
                sources.distinctBy { it.second }.take(10).forEach { (title, url) -> appendLine("- $title — $url") }
            }
        }

        /**
         * 응답에서 JSON 배열 추출·파싱(순수 함수). 구조 실패는 null(빈 배열 []과 구분 —
         * 호출부가 로그 남기고 빈 배열로 저장). topic·statement 없는 항목은 버린다.
         */
        internal fun parseItems(raw: String): List<GuidanceItem>? {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return null
            val arr = runCatching { parser.parseToJsonElement(raw.substring(start, end + 1)).jsonArray }
                .getOrNull() ?: return null
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val topic = o.str("topic") ?: return@mapNotNull null
                val statement = o.str("statement") ?: return@mapNotNull null
                GuidanceItem(
                    topic = topic,
                    statement = statement,
                    sourceUrl = o.str("sourceUrl") ?: "",
                    saidAt = o.str("saidAt") ?: "",
                )
            }
        }

        /** sourceUrl 후검증 — 검색이 실제 반환한 URL이 아니면 빈 문자열(모델이 URL을 지어내는 것 차단). */
        internal fun guardSourceUrls(items: List<GuidanceItem>, validUrls: List<String>): List<GuidanceItem> {
            val valid = validUrls.toSet()
            return items.map { if (it.sourceUrl.isNotBlank() && it.sourceUrl !in valid) it.copy(sourceUrl = "") else it }
        }

        private fun JsonObject.str(k: String): String? =
            (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

        // ── 1단계: 검색 수집 프롬프트 — 회사 발언만, 노트 형식 강제 ─────────────
        val SEARCH_PROMPT = """
            너는 한국 상장사의 실적 가이던스를 웹에서 조사하는 리서처다. 웹 검색으로 회사가
            실적 발표·컨퍼런스콜·IR·공시에서 **직접 밝힌** 향후 전망(가이던스)을 수집해
            "- [출처명, 날짜] 내용" 형식의 노트 목록만 반환하라. 서론·해석·검색 과정 서술 없이 노트만.

            수집 대상(회사가 스스로 말한 것만): 매출·이익 목표, 수주 목표, 증설·투자(CAPEX) 계획,
            신제품·신사업 일정, 배당·주주환원 정책, 수요 전망 코멘트.
            제외: 증권사 추정치·애널리스트 전망·목표주가(회사 발언이 아님), 주가 전망.

            규칙:
            - 각 노트에 출처명과 날짜를 반드시 붙여라. 날짜를 모르면 [출처명, 날짜 미상].
            - 검색 결과에 없는 내용을 너의 지식으로 보태지 마라.
            - 이번 실적 발표 전후의 발언을 우선하되, 여전히 유효한 이전 가이던스도 담아라.
            - 같은 내용의 중복 보도는 하나로 합쳐라.
            - 회사가 밝힌 가이던스를 찾지 못했으면 정확히 "없음"이라고만 답하라.
        """.trimIndent()

        // ── 2단계: 구조화 프롬프트(합성 서술 없음 — JSON 추출만) ─────────────────
        val STRUCTURE_PROMPT = """
            아래 리서치 노트에서 회사가 직접 밝힌 가이던스(전망·목표)만 JSON 배열로 구조화하라.
            배열 하나만 출력(코드펜스·설명·서두 텍스트 금지):

            [{"topic":"항목(예: 하반기 수주 목표)","statement":"수치/문구를 노트 표현 그대로","sourceUrl":"출처 URL","saidAt":"발화 시점(예: 7/15 컨콜)"}]

            규칙:
            - 노트에 없는 수치·발언을 만들지 마라. statement는 노트 내용을 그대로 옮겨라(수치 포함).
            - 회사 발언이 아닌 것(증권사 추정·애널리스트 전망)은 제외하라.
            - 목표주가·주가 전망은 제외하라.
            - sourceUrl은 [출처 URL 목록]에 있는 URL만. 매칭이 불확실하면 빈 문자열 "".
            - saidAt은 노트의 날짜를 쓰되 발언 자리(컨콜·IR 등)를 알면 함께("7/15 컨콜").
            - 가이던스가 없으면 빈 배열 [].
        """.trimIndent()
    }
}
