package com.haky.edge.ai

/**
 * AI 코멘트 호출의 트리거별 모델 라우팅.
 *
 * 정책(2026-07-08 사용자 결정으로 개정): **사용자에게 보이는 해석 코멘트는 전부 기본 Opus.**
 * 전 코멘트가 당일(또는 5분 쿨다운) 캐시 뒤에 있어 호출 횟수 자연 상한이 있고, 해석 품질이
 * 체감 가치라는 판단. 판단력이 필요 없는 JSON 분류(catalyst·섹터 분류)만 Sonnet 유지.
 *   briefing / analysis_* / ask / portfolio / premortem / overseas /
 *   macro_impact / sector_briefing / comparison                      → Opus
 *   catalyst (재료 JSON 분류 — 판단력 불필요, 볼륨 트리거)              → Sonnet
 *
 * (구 정책 2026-07-03: 하루 기준점만 Opus, 볼륨 트리거 Sonnet — docs/decisions.md #10·#11.
 *  비용이 부담되면 env로 구 정책 복귀: `OPUS_TRIGGERS=briefing,analysis_initial,premortem`)
 *
 * 롤백/재튜닝은 코드 수정 없이 env `OPUS_TRIGGERS`(콤마 목록)로 제어:
 *   미설정                → 위 기본 정책
 *   `OPUS_TRIGGERS=`      → 전부 Sonnet (비용 즉시 절감)
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
        const val ASK = "ask" // 종목 자유 질문 Q&A — 지연은 늘지만 해석 품질 우선(일일 상한으로 볼륨 방어)
        const val PORTFOLIO = "portfolio" // 포트폴리오 종합 진단 — 당일 캐시 1회, 구조 해석
        const val PREMORTEM = "premortem" // F5 매수 프리모템 — 매수 기록당 1회(자연 상한)·고판단 지점
        const val OVERSEAS = "overseas" // O4 해외 간단 코멘트 — 당일 공유 캐시(1일 1회/종목)
        const val MACRO_IMPACT = "macro_impact" // 브리핑 "내 종목 영향" — 당일 캐시 1회
        const val SECTOR_BRIEFING = "sector_briefing" // 브리핑 "섹터 분석" — 당일 캐시 1회
        const val COMPARISON = "comparison" // 종목 비교 — (쌍·날짜·모드) 당일 캐시
        const val TRADE_REVIEW = "trade_review" // 매매 복기 — 완결 매매당 수동 1회(당일 캐시)·고판단 지점
        const val DEEP_RESEARCH = "deep_research" // C 딥리서치 2단계(합성 리포트) — 일일 상한+당일 캐시, 최고 판단 지점
        const val WEEKLY_REVIEW = "weekly_review" // B 주간 회고 — 주 1회 스케줄(자연 상한 최강), 한 주 패턴 해석
        const val GUIDANCE = "guidance" // N2 가이던스 구조화 — 분기당 종목 1회(자연 상한)·발언/추정 구분이 고판단

        /**
         * 기본 Opus 대상 = 사용자에게 보이는 해석 코멘트 전부(2026-07-08 사용자 결정).
         * 전부 당일 캐시(수동 재생성은 5분 쿨다운, Q&A는 일일 상한) 뒤라 호출 횟수 자연 상한이 있다.
         * CATALYST(재료 JSON 분류)만 제외 — 해석이 아니라 분류 작업이라 판단력 프리미엄이 없고 볼륨만 있다.
         * (MacroImpactService.inferSectors 같은 라우터 미경유 JSON 분류도 기본 Sonnet 그대로.)
         */
        val DEFAULT_OPUS_TRIGGERS = setOf(
            BRIEFING, ANALYSIS_INITIAL, ANALYSIS_AUTO_REFRESH, ANALYSIS_MANUAL,
            ASK, PORTFOLIO, PREMORTEM, OVERSEAS,
            MACRO_IMPACT, SECTOR_BRIEFING, COMPARISON, TRADE_REVIEW, DEEP_RESEARCH,
            WEEKLY_REVIEW, GUIDANCE,
        )

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
