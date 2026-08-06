package com.haky.edge.ai

import com.haky.edge.thesis.SyncedThesis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** 논지 재점검 판정 1건. verdict ∈ {유효, 약화, 무효, 판단불가}. */
data class ThesisVerdict(val verdict: String, val changedFact: String, val reason: String)

/**
 * 논지 pull→push 재점검(2026-08). signals-scan이 물질적 사건(공시·실적·밸류)을 감지한 종목 중
 * 서버가 논지를 아는 것만 골라, "그 사건으로 기록한 논지가 여전히 유효한가"를 LLM 1콜로 점검한다.
 *
 * 역할: /analysis의 C12(논지 유효성)·C16(논지 변천) 점검을 **사건 트리거 + 초점 좁힘**으로 재사용.
 * facts는 논지 미포함본(AnalysisService.factsText)을 쓴다 — 논지는 점검 대상이지 근거가 아니므로
 * 사실 데이터와 분리해 넣는다. 게이트·쿨다운·일일 상한은 호출부(SignalService)가 관리(비용 방어).
 *
 * 모델: ModelRouter.THESIS_RECHECK(기본 Opus) — 아부 금지 정직 반론이 핵심이라 고판단 지점.
 */
class ThesisRecheckService(
    private val analysis: AnalysisService,
    private val claude: ClaudeClient,
    private val modelRouter: ModelRouter,
) {
    /** 재점검 1콜. 파싱 실패·빈 결과는 null(호출부가 skip). */
    suspend fun recheck(code: String, name: String, thesis: SyncedThesis, changeDescription: String): ThesisVerdict? {
        val facts = analysis.factsText(code)  // 논지 미포함 현재 사실
        val model = modelRouter.modelFor(ModelRouter.THESIS_RECHECK)
        val userMsg = buildString {
            appendLine("종목: $name ($code)")
            appendLine("오늘 발생한 물질적 변화: $changeDescription")
            appendLine()
            appendLine("내가 기록한 투자 논지: ${thesis.text}")
            // 변천 이력(오래된 순, 최근 몇 개) — C16 관점(근거 교체·기대 후퇴) 점검용.
            val hist = thesis.history.takeLast(AnalysisService.THESIS_HISTORY_MAX)
            if (hist.size >= 2) {
                appendLine("논지 변천(오래된 순): " + hist.joinToString(" → ") { "${it.d}: ${it.t}" })
            }
            appendLine()
            appendLine("[현재 사실 데이터]")
            append(facts)
        }
        val raw = claude.complete(SYSTEM_PROMPT, userMsg, maxTokens = 800, modelOverride = model)
        return parseVerdict(raw)
    }

    companion object {
        private val parser = Json { ignoreUnknownKeys = true; isLenient = true }
        val VALID_VERDICTS = setOf("유효", "약화", "무효", "판단불가")

        /** 발화 대상(사용자에게 알릴 가치가 있는) 판정 — 논지가 흔들리는 경우만. */
        fun isPushWorthy(verdict: String): Boolean = verdict == "약화" || verdict == "무효"

        /** Claude JSON 응답 파싱(순수 함수). 형식 불일치·미지 verdict는 null(호출부 skip 신호). */
        internal fun parseVerdict(raw: String): ThesisVerdict? {
            val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj: JsonObject = runCatching { parser.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
            val verdict = obj.str("verdict")?.takeIf { it in VALID_VERDICTS } ?: return null
            return ThesisVerdict(
                verdict = verdict,
                changedFact = obj.str("changedFact") ?: "",
                reason = obj.str("reason") ?: "",
            )
        }

        private fun JsonObject.str(k: String): String? =
            this[k]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() && it != "null" }

        private val SYSTEM_PROMPT = """
            너는 사용자가 기록한 투자 논지가 오늘 발생한 변화로도 여전히 유효한지 점검하는 엔진이다.
            입력: 종목, 오늘의 물질적 변화(공시·실적·밸류 진입), 사용자가 기록한 투자 논지(+변천 이력), 현재 사실 데이터.

            반드시 아래 JSON 객체 하나만 출력하라. 코드펜스(```)·설명·서두 텍스트 금지.
            {
              "verdict": "유효" | "약화" | "무효" | "판단불가",
              "changedFact": "논지에 영향을 준 오늘의 사실 한 줄(사실 데이터·오늘의 변화에서만)",
              "reason": "왜 그 판정인지 1~2문장(한국어)"
            }

            규칙:
            1. 논지를 뒷받침하는 사실과 흔드는 사실을 똑같은 무게로 보라. 논지에 유리한 재료만 고르는 확증편향을 금지한다.
            2. 아부 금지 — 사용자 기분을 맞추려 논지를 억지로 옹호하지 마라. 이 점검의 가치는 듣기 좋은 확인이 아니라 정직한 반론이다. 논지와 데이터가 어긋나면 에두르지 말고 정면으로 짚어라.
            3. 논지 속 주장·수치는 사실 데이터가 아니다 — 근거로 인용하지 마라. 논지는 점검의 대상이지 재료가 아니다.
            4. 판정할 사실이 부족하면(논지가 데이터 밖 주제거나 오늘의 변화가 논지와 무관하면) "판단불가". 억지로 판정하지 마라.
            5. "약화"=논지의 핵심 근거가 부분적으로 흔들림. "무효"=핵심 전제가 사실 데이터에서 소멸·반증됨. 애매하면 낮은 강도로 판정하라(과잉 경보 금지).
            6. 변천 이력이 있으면 근거가 교체됐는지·기대가 계속 후퇴했는지도 보라(특히 주가 하락 뒤 근거 교체).
            7. 너의 학습 지식 속 이 회사 수치는 낡았다 — 모든 숫자는 사실 데이터에서만.
        """.trimIndent()
    }
}
