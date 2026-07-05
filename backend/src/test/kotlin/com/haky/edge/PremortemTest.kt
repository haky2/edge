package com.haky.edge

import com.haky.edge.ai.Invalidation
import com.haky.edge.ai.Premortem
import com.haky.edge.ai.PremortemService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F5 — 프리모템 JSON 파싱·환각 가드·발동 평가 순수 함수. */
class PremortemTest {

    // ── parsePremortem ─────────────────────────────────────────────────

    @Test
    fun `정상 JSON 파싱`() {
        val raw = """
            {"bullCase": "수주 모멘텀이 이어지면 상승.", "bearCase": "수주 지연 시 하락.",
             "invalidations": [
               {"type": "price_below", "threshold": 320400, "anchor": "20일 저점", "description": "20일 저점 이탈"},
               {"type": "flow_exit", "threshold": 5, "description": "외국인 5일 연속 순매도"}
             ]}
        """.trimIndent()
        val pm = PremortemService.parsePremortem(raw)
        assertNotNull(pm)
        assertEquals(2, pm.invalidations.size)
        assertEquals(320400.0, pm.invalidations[0].threshold)
        assertEquals("20일 저점", pm.invalidations[0].anchor)
        assertTrue(pm.bullCase.contains("수주"))
    }

    @Test
    fun `코드펜스·서두 텍스트 방어`() {
        val raw = "다음은 결과입니다.\n```json\n{\"bullCase\": \"a\", \"bearCase\": \"b\", \"invalidations\": []}\n```"
        val pm = PremortemService.parsePremortem(raw)
        assertNotNull(pm)
        assertEquals("a", pm.bullCase)
    }

    @Test
    fun `파싱 실패·빈 결과는 null - 산문 폴백 신호`() {
        assertNull(PremortemService.parsePremortem("이 종목은 좋아 보입니다. JSON 없이 산문만."))
        assertNull(PremortemService.parsePremortem("{}"))
        assertNull(PremortemService.parsePremortem("{broken"))
    }

    @Test
    fun `type·description 없는 조건은 드롭`() {
        val raw = """{"bullCase": "a", "bearCase": "b", "invalidations": [
            {"threshold": 100, "description": "타입 없음"},
            {"type": "price_below", "threshold": 100}
        ]}"""
        val pm = PremortemService.parsePremortem(raw)
        assertNotNull(pm)
        assertTrue(pm.invalidations.isEmpty())
    }

    // ── guardInvalidations ─────────────────────────────────────────────

    private val facts = "현재가 350,000원. 기술적 앵커: 20일 저점 320,400원 · 20일 고점 380,000원. 본인 손절가: 310,000원."

    private fun inv(type: String, threshold: Double? = null, desc: String = "d") =
        Invalidation(type = type, threshold = threshold, description = desc)

    @Test
    fun `가격 threshold는 facts에 있는 값만 통과`() {
        val out = PremortemService.guardInvalidations(
            listOf(
                inv("price_below", 320400.0),   // facts에 있음 → 통과
                inv("price_below", 315000.0),   // 창작 값 → 드롭
                inv("price_above", 380000.0),   // 있음 → 통과
                inv("price_below", null),       // threshold 없음 → 드롭
            ), facts)
        assertEquals(listOf(320400.0, 380000.0), out.map { it.threshold })
    }

    @Test
    fun `flow_exit threshold는 1~30 클램프, 없으면 드롭`() {
        val out = PremortemService.guardInvalidations(
            listOf(inv("flow_exit", 5.0), inv("flow_exit", 99.0), inv("flow_exit", null)), facts)
        assertEquals(listOf(5.0, 30.0), out.map { it.threshold })
    }

    @Test
    fun `target_cut 등 표시용 타입은 threshold 무관 통과`() {
        val out = PremortemService.guardInvalidations(
            listOf(inv("target_cut"), inv("event_before"), inv("custom_type")), facts)
        assertEquals(3, out.size)
    }

    // ── firedInvalidations ─────────────────────────────────────────────

    private fun pm(vararg invs: Invalidation) = Premortem(
        code = "005930", name = "삼성전자", createdAt = "2026-07-04T15:00:00",
        reason = "수주 모멘텀", invalidations = invs.toList(),
    )

    @Test
    fun `price_below - 이탈 시 발동`() {
        val p = pm(inv("price_below", 320400.0))
        assertEquals(listOf(0), PremortemService.firedInvalidations(p, price = 320000, foreignSellStreak = 0))
        assertTrue(PremortemService.firedInvalidations(p, price = 320400, foreignSellStreak = 0).isEmpty())
        assertTrue(PremortemService.firedInvalidations(p, price = null, foreignSellStreak = 0).isEmpty())
    }

    @Test
    fun `price_above·flow_exit 발동`() {
        val p = pm(inv("price_above", 380000.0), inv("flow_exit", 5.0))
        assertEquals(listOf(0), PremortemService.firedInvalidations(p, price = 385000, foreignSellStreak = 4))
        assertEquals(listOf(1), PremortemService.firedInvalidations(p, price = 350000, foreignSellStreak = 5))
        assertEquals(listOf(0, 1), PremortemService.firedInvalidations(p, price = 385000, foreignSellStreak = 6))
    }

    @Test
    fun `비활성 조건과 표시용 타입은 평가 제외`() {
        val p = pm(
            inv("price_below", 320400.0).copy(active = false),
            inv("target_cut"),
        )
        assertTrue(PremortemService.firedInvalidations(p, price = 100, foreignSellStreak = 10).isEmpty())
    }
}
