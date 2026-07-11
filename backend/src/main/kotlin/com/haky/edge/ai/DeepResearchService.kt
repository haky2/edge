package com.haky.edge.ai

import com.haky.edge.master.StockMaster
import com.haky.edge.util.DayScopedCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** 딥리서치 리포트. comment는 2계층(우리 facts/웹 주장) 출처 규율이 적용된 본문. */
@Serializable
data class DeepResearch(
    val code: String,
    val name: String,
    val date: String,                   // 기준 거래일 (YYYY-MM-DD)
    val summary: String? = null,        // ### 핵심 요약 파싱(기존 계약 재사용)
    val comment: String,
    val sources: List<ResearchSource> = emptyList(), // 웹 검색 출처(URL 중복 제거)
    val generatedAt: String,            // HH:mm (KST)
)

@Serializable
data class ResearchSource(val title: String, val url: String)

/** 일일 상한 초과 — 라우트에서 429로 변환. */
class DeepResearchLimitException(message: String) : Exception(message)

/**
 * 일일 생성 카운터. 검색 과금이 걸린 기능이라 캐시 미스 생성만 센다(캐시 적중은 무료).
 * 생성 실패 시 release()로 쿼터를 되돌린다 — 검색 실패가 하루치를 갉아먹지 않게.
 */
internal class DailyLimiter(private val limit: Int) {
    private var date = ""
    private var count = 0

    @Synchronized
    fun tick(today: String) {
        if (date != today) { date = today; count = 0 }
        if (count >= limit) throw DeepResearchLimitException(
            "오늘 딥리서치 한도(${limit}건)를 모두 사용했습니다. 내일 다시 요청해 주세요.")
        count++
    }

    @Synchronized
    fun release(today: String) {
        if (date == today && count > 0) count--
    }
}

/**
 * 종목 딥리서치(C) — 온디맨드 웹검색 결합 심층 리포트.
 *
 * 2단계 구조(EventSyncService의 검증된 패턴 확대):
 *   1단계: web_search로 최신 정보를 "출처·날짜 병기 노트"로만 수집(기본 모델 Sonnet —
 *          수집은 기계적 작업, catalyst와 같은 철학). completeWithWebSearch의 텍스트에
 *          검색 사이 중간 서술이 섞이는 문제를 노트 형식 강제로 회피.
 *   2단계: 우리 facts(1층, 검증된 수치) + 리서치 노트(2층, 주장)를 complete()로 합성
 *          (ModelRouter.DEEP_RESEARCH 기본 Opus — 해석·종합이 이 기능의 가치).
 *
 * NumberGuard(요약 가격류 재생성 가드) 미적용 — 웹 수치는 구조적으로 facts 밖이라
 * 전부 오탐이다. 대신 프롬프트 D1(2층 수치는 출처·시점 병기 의무)·D3(두 계층 밖 수치
 * 생성 금지)이 같은 리스크를 출처 규율로 방어한다.
 *
 * 비용: 검색 과금(회당 별도) + Opus 생성이라 가장 비싼 코멘트 →
 *   (code, 날짜) 공유 캐시 + force 불허(당일 1회면 충분) + 일일 상한(기본 5, env) +
 *   종목별 in-flight Mutex(동시 중복 생성 차단). 해외 종목 제외(라우트 6자리 regex).
 */
class DeepResearchService(
    private val analysis: AnalysisService,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
    dailyLimit: Int = 5,
) {
    private val cache = DayScopedCache<DeepResearch>()   // 날짜 회전 시 자동 clear(S1)
    private val fileCache = FileCache("deep_research", DeepResearch.serializer())
    private val limiter = DailyLimiter(dailyLimit)
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    suspend fun research(code: String): DeepResearch {
        val today = effectiveMarketDate()
        val key = buildKey(code, today)
        cache.get(today, key)?.let { return it }
        fileCache.get(key)?.let { cache.put(today, key, it); return it }

        // 종목별 직렬화: 생성이 수십 초라 같은 종목 동시 요청이 검색·Opus 비용을 중복 지출하는 걸 막는다.
        return inFlight.computeIfAbsent(code) { Mutex() }.withLock {
            // 락 대기 중 먼저 들어간 요청이 만들었을 수 있음 — 재확인.
            cache.get(today, key)?.let { return@withLock it }
            fileCache.get(key)?.let { cache.put(today, key, it); return@withLock it }

            limiter.tick(kstToday())
            try {
                val result = generate(code, today)
                cache.put(today, key, result)
                fileCache.put(key, result)
                result
            } catch (e: Exception) {
                // 실패한 시도가 하루치 쿼터를 소모하지 않게 반환(과금된 검색은 어쩔 수 없지만 재시도 여지는 남긴다).
                limiter.release(kstToday())
                throw e
            }
        }
    }

    private suspend fun generate(code: String, today: String): DeepResearch {
        val t0 = System.currentTimeMillis()
        val name = master.findByCode(code)?.name ?: code
        val facts = analysis.factsText(code)

        // 1단계 — 웹검색 수집(노트만). 기본 모델(Sonnet): 수집은 기계적 작업.
        val gathered = claude.completeWithWebSearch(
            systemPrompt = SEARCH_PROMPT,
            userFacts = "종목: $name ($code). 오늘: $today. 이 종목 관련 최신 정보를 검색해 노트로 정리:",
            maxTokens = 1200,
            maxSearchUses = 4,
        )
        val notes = gathered.text.trim()
        if (notes.isBlank()) {
            throw ClaudeException("웹 검색 결과를 얻지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
        println("[DeepResearch] $code: search=${System.currentTimeMillis() - t0}ms notes=${notes.length}자 sources=${gathered.sources.size}")

        // 2단계 — 합성 리포트(기본 Opus). complete()는 이어쓰기 루프가 있어 긴 리포트도 안 잘린다.
        val model = modelRouter.modelFor(ModelRouter.DEEP_RESEARCH)
        val raw = claude.complete(
            systemPrompt = DEEP_RESEARCH_PROMPT,
            userFacts = renderStage2Input(facts, notes),
            maxTokens = 3000,
            modelOverride = model,
        )
        val (summary, body) = AnalysisService.parseSummaryFromComment(raw)
        println("[DeepResearch] $code: total=${System.currentTimeMillis() - t0}ms")

        val now = java.time.LocalTime.now(ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return DeepResearch(
            code = code, name = name, date = today,
            summary = summary, comment = body,
            sources = dedupeSources(gathered.sources.map { ResearchSource(it.title, it.url) }),
            generatedAt = now,
        )
    }

    private fun kstToday(): String = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()

    companion object {
        internal fun buildKey(code: String, date: String): String = "$code:$date"

        /** 출처 URL 중복 제거(등장 순 유지) + 상한 — 멀티턴 검색이 같은 페이지를 반복 인용하는 경우 정리. */
        internal fun dedupeSources(sources: List<ResearchSource>, limit: Int = 10): List<ResearchSource> =
            sources.distinctBy { it.url }.take(limit)

        /** 2단계 입력 — 계층 라벨이 프롬프트 D1~D3 규율의 앵커라 형식을 고정한다. */
        internal fun renderStage2Input(facts: String, notes: String): String = buildString {
            appendLine("[1층: 사실 데이터 — 우리 시스템이 검증한 수치]")
            appendLine(facts)
            appendLine()
            appendLine("[2층: 웹 리서치 노트 — 웹 검색으로 수집한 주장, 검증되지 않음]")
            appendLine(notes.take(4000))
        }

        // ── 1단계: 검색 수집 프롬프트 ─────────────────────────────────────────
        // 노트 형식 강제 이유: ① 검색 사이 중간 서술("검색해보겠습니다")이 결과 텍스트에 섞이는 것 차단
        // ② 출처·날짜를 수집 시점에 붙여야 2단계가 D1(출처 병기)을 지킬 재료가 생긴다.
        val SEARCH_PROMPT = """
            너는 한국 주식 종목의 최신 정보를 웹에서 조사하는 리서처다. 웹 검색으로 아래 관점의
            정보를 수집해 "- [출처명, 날짜] 내용" 형식의 노트 목록만 반환하라. 서론·해석·검색 과정
            서술 없이 노트만.

            관점: ① 업계·경쟁사 동향 ② 수주·계약·신제품 파이프라인 ③ 해외 동종업체(peer) 상황
            ④ 정책·규제·소송 리스크 ⑤ 최근 실적 발표에 대한 시장 평가.

            규칙:
            - 각 노트에 출처명과 날짜를 반드시 붙여라. 날짜를 모르면 [출처명, 날짜 미상].
            - 검색 결과에 없는 내용을 너의 지식으로 보태지 마라.
            - 6개월 넘게 지난 정보는 경쟁 구도 같은 구조적 사실만 담고 수치·전망은 제외하라.
            - 같은 내용의 중복 보도는 하나로 합쳐라.
        """.trimIndent()

        // ── 2단계: 합성 리포트 프롬프트(캐시 대상) ────────────────────────────
        // 이 리포트의 생명은 출처 2계층 분리다 — 검증된 수치(1층)와 웹 주장(2층)이 한 문단에
        // 섞이면서도 독자가 어느 쪽인지 항상 알 수 있어야 한다. D1~D4가 전부 그 방어다.
        val DEEP_RESEARCH_PROMPT = """
            너는 개인 투자자를 위한 종목 딥리서치 리포트를 쓴다. 한국어로. 입력은 두 계층이다:
            [1층: 사실 데이터] — 우리 시스템이 검증한 수치(시세·수급·재무·밸류에이션·뉴스 제목). 그대로 근거로 쓴다.
            [2층: 웹 리서치 노트] — 웹 검색으로 수집한 주장. 검증되지 않았다.

            D1. 계층 구분이 이 리포트의 생명이다. 2층 내용을 쓸 때는 반드시 문장 안에 출처와 시점을
                병기하라(예: "~로 알려졌다(전자신문, 7/8)"). 출처 없이 2층의 수치·주장을 사실처럼 서술하지 마라.
                단 '1층/2층'은 내부 용어다 — 본문에서는 쓰지 말고 "우리 데이터 기준"·"보도에 따르면" 같은
                자연어로 구분하라. 출처·시점 병기가 돼 있으면 독자는 웹 주장임을 안다.
            D2. 1층과 2층이 상충하면 양쪽을 병기하고 어느 쪽이 맞는지 단정하지 마라
                ("우리 데이터 기준 X인데, 보도는 Y라고 전한다").
            D3. 두 계층 어디에도 없는 수치·사건을 만들지 마라. 너의 사전 지식으로 빈칸을 채우지 마라 —
                사전 지식은 오래됐고 이 리포트의 가치는 '지금' 정보다. 업의 구조 같은 배경 설명에만
                제한적으로 쓰되 수치는 금지.
            D4. 리서치 노트가 빈약한 주제는 "확인되지 않았다"고 쓰고 넘어가라. 억지로 채우지 마라.
            D5. 매수/매도 지시·목표가 제시 금지. 판단 재료를 깊게 정리하는 리서치이지 매매 권유가 아니다.
                "참고용"·"매매 권유 아님" 류의 면책 문구도 쓰지 마라 — 앱이 별도로 표시한다.
            D6. 형식: 응답 첫 글자부터 "### 핵심 요약" 소제목, 그 아래 2~3문장(가장 중요한 신규 정보 중심,
                핵심 수치 포함), 빈 줄 하나. 이후 본문 4~6개 단락 — 각 단락 첫 줄에 **소제목**만 굵게
                (예: **업계·경쟁 구도**, **수주·파이프라인**, **해외 동종업체**, **리스크 요인**), 다음 줄부터
                본문. 불릿·번호 목록·구분선 금지. 단락 사이 빈 줄 하나.
            D7. 핵심 수치는 **굵게**. 어려운 금융 영어는 한국어로 풀거나 괄호 설명을 붙여라.

            [말미 재확인] 답하기 전에 확인하라 — ① 2층 주장에 전부 출처·시점이 붙었는가
            ② 어느 계층에도 없는 수치를 만들지 않았는가 ③ 매매 지시·목표가를 쓰지 않았는가.
        """.trimIndent()
    }
}
