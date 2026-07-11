package com.haky.edge.ai

import com.haky.edge.master.StockMaster
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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 무효화 조건 1개. threshold: 가격 타입=원, flow_exit=연속 순매도 일수. */
@Serializable
data class Invalidation(
    val type: String,            // price_below | price_above | flow_exit | target_cut | event_before | 기타(표시용)
    val threshold: Double? = null,
    val anchor: String? = null,  // threshold 출처("20일 저점", "본인 손절가" 등)
    val description: String,
    val active: Boolean = true,
    val firedAt: String? = null, // 발동 시각(1회성 — 발동 후 비활성)
)

/** 매수 프리모템 1건(종목당 최신 1개 — 새 매수 기록이 교체). */
@Serializable
data class Premortem(
    val code: String,
    val name: String,
    val createdAt: String,       // ISO(KST)
    val reason: String,          // 매수 사유(행동 로그 reason)
    val bullCase: String = "",
    val bearCase: String = "",
    val invalidations: List<Invalidation> = emptyList(),
)

/**
 * 매수 전 프리모템 + 무효화 조건(F5) — 예측이 아니라 **가설이 틀렸음을 가장 빨리 아는** 장치.
 * 매수 기록 시 reason을 받아 "이 논리가 깨지는 조건"을 구조화해 저장하고,
 * signals-scan(18:00)이 매일 평가해 발동 시 Slack으로 알린다(발동 조건은 1회성 비활성).
 *
 * LLM 비용: 매수 기록당 1회(수동 행위 기반 자연 상한) — 저빈도·고판단이라 ModelRouter.PREMORTEM 기본 Opus.
 * 환각 가드: 가격 threshold는 facts에 존재하는 수치만 허용(A3 원칙 — 아니면 해당 조건 드롭).
 * 저장: {DATA_DIR}/premortem.json (단일 사용자 전제, code → 최신 1건).
 */
class PremortemService(
    private val analysis: AnalysisService,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
    dataDir: String = System.getenv("DATA_DIR") ?: ".data",
) {
    private val file = File(dataDir, "premortem.json").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), Premortem.serializer())
    private val store = ConcurrentHashMap<String, Premortem>()
    private val mutex = Mutex()
    @Volatile private var loaded = false

    /** 생성+저장. JSON 파싱 실패 시 저장하지 않고 산문 폴백(bullCase에 원문, 조건 없음)만 반환. */
    suspend fun create(code: String, reason: String, avgPrice: Double?, qty: Long?, stopPrice: Double? = null): Premortem {
        val name = master.findByCode(code)?.name ?: code
        val position = if (avgPrice != null && avgPrice > 0 && qty != null && qty > 0)
            Position(avgPrice, qty, stopPrice = stopPrice ?: 0.0) else null
        val facts = analysis.factsText(code, position)
        val model = modelRouter.modelFor(ModelRouter.PREMORTEM)
        val userMsg = buildString {
            appendLine("종목: $name ($code)")
            appendLine("매수 사유: ${reason.ifBlank { "(입력 없음)" }}")
            appendLine()
            appendLine("[사실 데이터]")
            append(facts)
        }
        val raw = claude.complete(PREMORTEM_PROMPT, userMsg, maxTokens = 1500, modelOverride = model)
        val now = nowKstIso()

        val parsed = parsePremortem(raw)
        if (parsed == null) {
            // 폴백: 구조화 실패 — 저장하지 않고 산문만 돌려준다(감시 없는 1회성 응답).
            println("[Premortem] $code: JSON 파싱 실패 — 산문 폴백(저장 안 함)")
            return Premortem(code, name, now, reason, bullCase = raw.trim())
        }
        val guarded = parsed.copy(
            code = code, name = name, createdAt = now, reason = reason,
            invalidations = guardInvalidations(parsed.invalidations, facts),
        )
        ensureLoaded()
        mutex.withLock { store[code] = guarded; persistLocked() }
        return guarded
    }

    suspend fun get(code: String): Premortem? { ensureLoaded(); return store[code] }

    /** 활성 무효화 조건이 하나라도 남은 프리모템 전부(signals-scan 평가 대상). */
    suspend fun allWithActive(): List<Premortem> {
        ensureLoaded()
        return store.values.filter { pm -> pm.invalidations.any { it.active && it.type in EVALUABLE_TYPES } }
    }

    /** 발동 처리: 해당 인덱스 조건을 비활성화(1회성)하고 영속. */
    suspend fun markFired(code: String, firedIndexes: List<Int>) {
        if (firedIndexes.isEmpty()) return
        ensureLoaded()
        mutex.withLock {
            val pm = store[code] ?: return
            val now = nowKstIso()
            store[code] = pm.copy(invalidations = pm.invalidations.mapIndexed { i, inv ->
                if (i in firedIndexes) inv.copy(active = false, firedAt = now) else inv
            })
            persistLocked()
        }
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

    private fun nowKstIso(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    companion object {
        /** signals-scan이 평가 가능한 타입(v1). target_cut·event_before는 표시용 저장만(평가 skip). */
        val EVALUABLE_TYPES = setOf("price_below", "price_above", "flow_exit")
        private val KNOWN_TYPES = EVALUABLE_TYPES + setOf("target_cut", "event_before")
        private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Claude JSON 응답 파싱(순수 함수). 형식 불일치·빈 결과는 null(산문 폴백 신호). */
        internal fun parsePremortem(raw: String): Premortem? {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = runCatching { parser.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
            val bull = obj.str("bullCase") ?: ""
            val bear = obj.str("bearCase") ?: ""
            val invs = (obj["invalidations"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val type = o.str("type") ?: return@mapNotNull null
                val desc = o.str("description") ?: return@mapNotNull null
                Invalidation(
                    type = type,
                    threshold = (o["threshold"] as? JsonPrimitive)?.doubleOrNull,
                    anchor = o.str("anchor"),
                    description = desc,
                )
            } ?: emptyList()
            if (bull.isBlank() && bear.isBlank() && invs.isEmpty()) return null
            return Premortem(code = "", name = "", createdAt = "", reason = "", bullCase = bull, bearCase = bear, invalidations = invs)
        }

        /**
         * 환각 가드(순수 함수): 가격 타입(price_below/above)의 threshold는 facts에 존재하는
         * 수치여야 한다(A3 — 창작 레벨 차단). 없으면 해당 조건 드롭. flow_exit threshold는
         * 1~30일로 클램프, 미지의 type은 표시용으로 두되 평가는 EVALUABLE_TYPES가 거른다.
         */
        internal fun guardInvalidations(invs: List<Invalidation>, facts: String): List<Invalidation> {
            val factsNumbers = AnalysisService.extractNumbers(facts)
            return invs.mapNotNull { inv ->
                when (inv.type) {
                    "price_below", "price_above" -> {
                        val t = inv.threshold
                        if (t == null || t !in factsNumbers) null else inv
                    }
                    "flow_exit" -> {
                        val t = inv.threshold?.toInt()?.coerceIn(1, 30) ?: return@mapNotNull null
                        inv.copy(threshold = t.toDouble())
                    }
                    in KNOWN_TYPES -> inv          // target_cut·event_before — 표시용
                    else -> inv                     // 미지 타입 — 표시용(평가 안 됨)
                }
            }
        }

        /**
         * 일일 평가(순수 함수): 현재가·외국인 연속 순매도 일수로 활성 조건 발동 여부.
         * 반환 = 발동한 조건의 인덱스 목록(원본 리스트 기준).
         */
        internal fun firedInvalidations(pm: Premortem, price: Long?, foreignSellStreak: Int): List<Int> =
            pm.invalidations.mapIndexedNotNull { i, inv ->
                if (!inv.active) return@mapIndexedNotNull null
                val t = inv.threshold
                val fired = when (inv.type) {
                    "price_below" -> price != null && t != null && price < t
                    "price_above" -> price != null && t != null && price > t
                    "flow_exit" -> t != null && foreignSellStreak >= t.toInt()
                    else -> false
                }
                if (fired) i else null
            }

        private fun JsonObject.str(k: String): String? =
            this[k]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() && it != "null" }

        private val PREMORTEM_PROMPT = """
            너는 한국 주식 투자 보조 앱의 프리모템(사전 부검) 엔진이다.
            사용자가 방금 이 종목을 매수하며 남긴 "매수 사유"와 "사실 데이터"를 받는다.
            할 일: 이 매수 논리가 **틀렸음을 가장 빨리 알 수 있는** 무효화 조건을 구조화한다. 예측이 아니다.

            반드시 아래 JSON 객체 하나만 출력하라. 코드펜스(```)·설명·서두 텍스트 금지.
            {
              "bullCase": "이 매수 논리가 맞다면 어떤 경로로 실현되는지 2~3문장(한국어)",
              "bearCase": "이 논리가 틀렸다면 무엇 때문일 가능성이 큰지 2~3문장(한국어)",
              "invalidations": [
                {
                  "type": "price_below" | "price_above" | "flow_exit" | "target_cut" | "event_before",
                  "threshold": 숫자(price_*는 원 단위 가격, flow_exit는 연속 순매도 일수. 해당 없으면 생략),
                  "anchor": "threshold의 출처(예: 20일 저점, 본인 손절가, 52주 저가. 해당 없으면 생략)",
                  "description": "발동 조건과 의미 한 줄(한국어, 40자 내외)"
                }
              ]
            }

            규칙:
            1. price_below/price_above의 threshold는 반드시 사실 데이터에 있는 값(기술적 앵커 20일 저점/고점·MA20/60, 52주 고저, 본인 손절가)에서만 골라라. 새 가격을 만들지 마라. anchor에 출처를 명시하라.
            2. invalidations는 2~4개. price_below(지지 이탈)와 flow_exit(수급 이탈)를 우선 고려하라.
            3. 매수 사유가 구체적이지 않으면("그냥", 한두 단어) 일반 리스크 관리 조건(손절 라인·수급 이탈)만 제시하라.
            4. flow_exit의 threshold는 연속 순매도 일수(정수, 3~7 권장). description에 주체(외국인)를 명시하라.
            5. 사유가 특정 이벤트·실적·수주 기대라면 그 가설이 깨지는 신호(target_cut: 목표가 하향 전환 등)를 포함하라.
            6. 너의 학습 지식 속 이 회사 수치는 낡았다 — 모든 숫자는 사실 데이터에서만.
        """.trimIndent()
    }
}
