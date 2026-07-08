package com.haky.edge.ai

/**
 * AI 코멘트 호출의 트리거별 모델 라우팅.
 *
 * 정책(CLAUDE.md "facts 강화로 Sonnet 충분" 결정과 정합, 2026-07-03 재확정 — docs/decisions.md 참고):
 * 판단력 프리미엄은 하루 기준점이 되는 곳에만 쓰고, 반복·볼륨 트리거는 Sonnet.
 *   briefing               (시장 분위기, 1일 1회)          → Opus
 *   analysis_initial       (종목 최초 생성, 하루 기준점)    → Opus
 *   analysis_auto_refresh  (급변 stale 자동 재생성)          → Sonnet (볼륨 트리거)
 *   analysis_manual        (사용자 수동 새로고침, 연타 가능)  → Sonnet
 *   catalyst               (재료 JSON 분류 — 판단력 불필요)   → Sonnet
 *
 * 롤백/재튜닝은 코드 수정 없이 env `OPUS_TRIGGERS`(콤마 목록)로 제어:
 *   미설정                → 위 기본 정책
 *   `OPUS_TRIGGERS=`      → 전부 Sonnet (비용 즉시 절감)
 *   `briefing,analysis_initial,analysis_auto_refresh,analysis_manual,catalyst` → 전부 Opus
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
        const val ASK = "ask" // 종목 자유 질문 Q&A — 대화형(지연 민감)·볼륨 트리거라 기본 Sonnet
        const val PORTFOLIO = "portfolio" // 포트폴리오 종합 진단 — facts가 전부 계산값이라 기본 Sonnet(env로 승격 가능)
        const val PREMORTEM = "premortem" // F5 매수 프리모템 — 매수 기록당 1회(자연 상한)·고판단 지점이라 기본 Opus
        const val OVERSEAS = "overseas" // O4 해외 간단 코멘트 — 당일 공유 캐시(1일 1회/종목)라 저빈도 → 기본 Opus(사용자 결정 2026-07-08)

        /**
         * 기본 Opus 대상: 브리핑 + 종목 최초 생성 + 매수 프리모템 + 해외 코멘트. 나머지(자동 재생성·
         * 수동 새로고침·재료 판정)는 Sonnet — 재료 판정은 JSON 분류라 판단력 프리미엄이 불필요하고,
         * 수동/자동 재생성은 볼륨 트리거라 비용 대비 효용이 낮다(CLAUDE.md "facts 강화로 Sonnet 충분" 결정과 정합).
         * 프리모템은 analysis_initial과 같은 저빈도·고판단 지점(매수당 1회 자연 상한)이라 비용 부담 없이 Opus.
         * 해외(O4)는 당일 공유 캐시로 종목당 1일 1회 자연 상한 — 사용자 결정(2026-07-08)으로 기본 Opus.
         */
        val DEFAULT_OPUS_TRIGGERS = setOf(BRIEFING, ANALYSIS_INITIAL, PREMORTEM, OVERSEAS)

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
