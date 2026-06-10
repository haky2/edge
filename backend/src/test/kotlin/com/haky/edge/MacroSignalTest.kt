package com.haky.edge

import com.haky.edge.kis.MacroIndicator
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.MacroImpactService.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacroSignalTest {

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private fun indicator(key: String, label: String, changeRate: Double) =
        MacroIndicator(key = key, label = label, value = 0.0, change = 0.0, changeRate = changeRate)

    // ── 방향 계산 기본 논리 ───────────────────────────────────────────────

    @Test fun `지표 상승과 민감도 양수 → 우호 direction 1`() {
        val sectors = listOf(Sector.MEMORY)
        val indicators = listOf(indicator("usdkrw", "원/달러", +1.5))   // 원화 약세: SEMI 우호
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        val usd = signals.first { it.indicator == "원/달러" }
        assertEquals(1, usd.direction)
    }

    @Test fun `지표 하락과 민감도 양수 → 부담 direction -1`() {
        val sectors = listOf(Sector.MEMORY)
        val indicators = listOf(indicator("usdkrw", "원/달러", -1.0))   // 원화 강세: SEMI 부담
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        val usd = signals.first { it.indicator == "원/달러" }
        assertEquals(-1, usd.direction)
    }

    @Test fun `지표 상승과 민감도 음수 → 부담 direction -1`() {
        val sectors = listOf(Sector.MEMORY)
        val indicators = listOf(indicator("crude", "WTI유가", +2.0))    // 유가 상승: SEMI 부담
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        val crude = signals.first { it.indicator == "WTI유가" }
        assertEquals(-1, crude.direction)
    }

    @Test fun `지표 하락과 민감도 음수 → 우호 direction 1`() {
        val sectors = listOf(Sector.MEMORY)
        val indicators = listOf(indicator("crude", "WTI유가", -2.0))    // 유가 하락: SEMI 우호
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        val crude = signals.first { it.indicator == "WTI유가" }
        assertEquals(1, crude.direction)
    }

    @Test fun `지표 보합일 때 direction 0`() {
        val sectors = listOf(Sector.MEMORY)
        val indicators = listOf(indicator("usdkrw", "원/달러", 0.0))
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        val usd = signals.first { it.indicator == "원/달러" }
        assertEquals(0, usd.direction)
    }

    // ── computeNet 종합 판정 ──────────────────────────────────────────────

    @Test fun `빈 신호 목록은 대시 반환`() {
        assertEquals("-", MacroImpactService.computeNet(emptyList()))
    }

    @Test fun `신호 합계 양수이면 우호적`() {
        val sectors = listOf(Sector.DEFENSE)
        // DEFENSE 민감도: usdkrw +1. 상승 → 우호
        val indicators = listOf(indicator("usdkrw", "원/달러", +1.0))
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        assertEquals("우호적", MacroImpactService.computeNet(signals))
    }

    @Test fun `신호 합계 음수이면 부담`() {
        val sectors = listOf(Sector.MEMORY)
        // crude 상승 → 부담(-1)
        val indicators = listOf(indicator("crude", "WTI유가", +3.0))
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        assertEquals("부담", MacroImpactService.computeNet(signals))
    }

    @Test fun `우호와 부담이 상쇄되면 중립`() {
        val sectors = listOf(Sector.MEMORY)
        // MEMORY: usdkrw +1, nasdaq +1, crude -1, rate3y -1
        // usdkrw 하락(-1.0) → effDir=-1, nasdaq 상승(+1.0) → effDir=+1 → 합=0
        val indicators = listOf(
            indicator("usdkrw", "원/달러", -1.0),
            indicator("nasdaq", "나스닥",  +1.0),
        )
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        assertEquals("중립", MacroImpactService.computeNet(signals))
    }

    // ── note 결정 ────────────────────────────────────────────────────────

    @Test fun `단일 방향 note는 기여한 섹터의 note 사용`() {
        val sectors = listOf(Sector.DEFENSE)
        val indicators = listOf(indicator("usdkrw", "원/달러", +1.0))
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        val note = signals.first().note
        assertTrue(note.isNotEmpty())
        assertTrue(note.contains("원화 약세"), "DEFENSE usdkrw note에 '원화 약세' 포함 필요, 실제: $note")
    }

    @Test fun `매핑 없는 지표는 신호 목록에 포함되지 않는다`() {
        val sectors = listOf(Sector.MEMORY)
        val indicators = listOf(indicator("unknown_key", "알수없음", +5.0))
        val signals = MacroImpactService.computeSignals(sectors, indicators)
        assertTrue(signals.isEmpty(), "매핑 없는 지표는 신호로 생성되지 않아야 함")
    }

    // ── SENSITIVITY 테이블 정합성 ──────────────────────────────────────────

    @Test fun `SENSITIVITY의 모든 direction 값은 -1, 0, +1 중 하나`() {
        MacroImpactService.SENSITIVITY.values.flatten().forEach { s ->
            assertTrue(s.direction in listOf(-1, 0, 1),
                "direction=${s.direction} for key=${s.indicatorKey}")
        }
    }

    @Test fun `SENSITIVITY의 모든 note는 비어있지 않다`() {
        MacroImpactService.SENSITIVITY.values.flatten().forEach { s ->
            assertTrue(s.note.isNotBlank(),
                "note가 비어있음: indicatorKey=${s.indicatorKey}")
        }
    }

    @Test fun `모든 Sector enum의 label이 비어있지 않다`() {
        Sector.entries.forEach { sector ->
            assertTrue(sector.label.isNotBlank(), "label 비어있음: $sector")
        }
    }

    @Test fun `모든 Sector는 SENSITIVITY에 등록된 MacroGroup을 가진다`() {
        val registeredGroups = MacroImpactService.SENSITIVITY.keys.toSet()
        Sector.entries.forEach { sector ->
            assertTrue(sector.group in registeredGroups,
                "${sector.name} 의 group=${sector.group} 이 SENSITIVITY에 없음")
        }
    }
}
