package com.haky.edge

import com.haky.edge.ai.ModelRouter
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelRouterTest {

    private val sonnet = "claude-sonnet-4-6"
    private val opus = "claude-opus-4-8"

    private fun router(triggers: Set<String>) = ModelRouter(sonnet, opus, triggers)

    // ── 기본 정책: 브리핑·최초=Opus / 자동재생성·수동·재료판정=Sonnet ─────────

    @Test fun `기본 정책 라우팅 - 브리핑·최초생성·프리모템만 Opus`() {
        val r = router(ModelRouter.DEFAULT_OPUS_TRIGGERS)
        assertEquals(opus, r.modelFor(ModelRouter.BRIEFING))
        assertEquals(opus, r.modelFor(ModelRouter.ANALYSIS_INITIAL))
        assertEquals(opus, r.modelFor(ModelRouter.PREMORTEM)) // 매수당 1회·고판단 저빈도
        assertEquals(sonnet, r.modelFor(ModelRouter.ANALYSIS_AUTO_REFRESH))
        assertEquals(sonnet, r.modelFor(ModelRouter.ANALYSIS_MANUAL))
        assertEquals(sonnet, r.modelFor(ModelRouter.CATALYST))
    }

    @Test fun `미정의 트리거는 Sonnet 폴백`() {
        assertEquals(sonnet, router(ModelRouter.DEFAULT_OPUS_TRIGGERS).modelFor("unknown_trigger"))
    }

    // ── parseTriggers: env 제어 ───────────────────────────────────────────

    @Test fun `null env 는 기본 정책`() {
        assertEquals(ModelRouter.DEFAULT_OPUS_TRIGGERS, ModelRouter.parseTriggers(null))
    }

    @Test fun `빈 문자열 env 는 전부 Sonnet`() {
        val triggers = ModelRouter.parseTriggers("")
        assertEquals(emptySet(), triggers)
        val r = router(triggers)
        assertEquals(sonnet, r.modelFor(ModelRouter.BRIEFING))
        assertEquals(sonnet, r.modelFor(ModelRouter.ANALYSIS_INITIAL))
        assertEquals(sonnet, r.modelFor(ModelRouter.ANALYSIS_AUTO_REFRESH))
        assertEquals(sonnet, r.modelFor(ModelRouter.ANALYSIS_MANUAL))
    }

    @Test fun `공백 패딩 목록 파싱`() {
        assertEquals(
            setOf("briefing", "analysis_initial"),
            ModelRouter.parseTriggers(" briefing , analysis_initial "),
        )
    }

    @Test fun `자동재생성만 Sonnet 으로 빼는 절감 프로필`() {
        val r = router(ModelRouter.parseTriggers("briefing,analysis_initial"))
        assertEquals(opus, r.modelFor(ModelRouter.BRIEFING))
        assertEquals(opus, r.modelFor(ModelRouter.ANALYSIS_INITIAL))
        assertEquals(sonnet, r.modelFor(ModelRouter.ANALYSIS_AUTO_REFRESH)) // 절감 대상
        assertEquals(sonnet, r.modelFor(ModelRouter.ANALYSIS_MANUAL))
    }

    @Test fun `전부 Opus 프로필`() {
        val r = router(
            ModelRouter.parseTriggers("briefing,analysis_initial,analysis_auto_refresh,analysis_manual"),
        )
        assertEquals(opus, r.modelFor(ModelRouter.ANALYSIS_MANUAL))
    }
}
