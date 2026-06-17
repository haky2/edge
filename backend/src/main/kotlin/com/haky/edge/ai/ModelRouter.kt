package com.haky.edge.ai

/**
 * AI 코멘트 호출의 트리거별 모델 라우팅.
 *
 * 정책(가치 축): 매일 보는 방향 판단·종목 기준점·급변 재생성은 Opus(판단력 우선),
 * 일상 수동 새로고침은 Sonnet(속도·비용). 트리거별 기본값:
 *   briefing               (시장 분위기, 1일 1회)         → Opus
 *   analysis_initial       (종목 최초 생성, 장 전 기준점)  → Opus
 *   analysis_auto_refresh  (급변 stale 자동 재생성)         → Opus
 *   analysis_manual        (사용자 수동 새로고침)            → Opus
 *
 * 롤백/재튜닝은 코드 수정 없이 env `OPUS_TRIGGERS`(콤마 목록)로 제어:
 *   미설정                → 위 기본 정책
 *   `OPUS_TRIGGERS=`      → 전부 Sonnet (비용 즉시 절감)
 *   `briefing,analysis_initial` → 자동재생성만 Sonnet으로 (볼륨 큰 트리거 절감)
 *   `briefing,analysis_initial,analysis_auto_refresh,analysis_manual` → 전부 Opus
 *
 * 모델 ID는 `CLAUDE_MODEL`(Sonnet)·`CLAUDE_OPUS_MODEL`(Opus) env로 주입.
 */
class ModelRouter(
    private val sonnetModel: String,
    private val opusModel: String,
    private val opusTriggers: Set<String>,
) {
    /** trigger가 Opus 대상이면 Opus 모델 ID, 아니면 Sonnet 모델 ID. */
    fun modelFor(trigger: String): String =
        if (trigger in opusTriggers) opusModel else sonnetModel

    companion object {
        const val BRIEFING = "briefing"
        const val ANALYSIS_INITIAL = "analysis_initial"
        const val ANALYSIS_AUTO_REFRESH = "analysis_auto_refresh"
        const val ANALYSIS_MANUAL = "analysis_manual"
        const val CATALYST = "catalyst"

        /** 기본 Opus 대상: 브리핑 + 종목 최초 생성 + 급변 자동 재생성 + 수동 새로고침 + 재료 판정(전부). */
        val DEFAULT_OPUS_TRIGGERS = setOf(BRIEFING, ANALYSIS_INITIAL, ANALYSIS_AUTO_REFRESH, ANALYSIS_MANUAL, CATALYST)

        /**
         * env 문자열 → 트리거 집합.
         *  - null(미설정) → 기본 정책
         *  - 빈/공백 문자열 → 빈 집합(전부 Sonnet)
         *  - "a,b,c" → 해당 트리거만 Opus
         */
        fun parseTriggers(raw: String?): Set<String> = when {
            raw == null -> DEFAULT_OPUS_TRIGGERS
            raw.isBlank() -> emptySet()
            else -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}
