package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.Position
import com.haky.edge.macro.AnalysisMode
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.MarketMoodService
import com.haky.edge.macro.SectorBriefingService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CacheKeyTest {

    // ── AnalysisService.buildKey ──────────────────────────────────────────

    @Test fun `Analysis 포지션 없을 때 키 형식 code_date_mode`() {
        val key = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null)
        assertEquals("009150:2026-06-11:DEFENSIVE", key)
    }

    @Test fun `Analysis 포지션 있을 때 키에 평단·수량·목표가·손절가 포함`() {
        val pos = Position(avgPrice = 86000.0, qty = 10L, targetPrice = 95000.0, stopPrice = 80000.0)
        val key = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, pos)
        assertEquals("009150:2026-06-11:DEFENSIVE:86000:10:95000:80000", key)
    }

    @Test fun `Analysis 포지션 있을 때와 없을 때 키가 달라야 한다`() {
        val pos = Position(avgPrice = 86000.0, qty = 10L)
        val keyNoPos   = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null)
        val keyWithPos = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, pos)
        assertNotEquals(keyNoPos, keyWithPos)
    }

    @Test fun `Analysis DEFENSIVE vs AGGRESSIVE 키가 달라야 한다`() {
        val def = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null)
        val agg = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.AGGRESSIVE, null)
        assertNotEquals(def, agg)
    }

    @Test fun `Analysis 목표가·손절가 미입력은 키에 0으로 포함`() {
        val pos = Position(avgPrice = 86000.0, qty = 5L)  // targetPrice=0.0, stopPrice=0.0 기본값
        val key = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, pos)
        assertEquals("009150:2026-06-11:DEFENSIVE:86000:5:0:0", key)
    }

    // ── MacroImpactService.buildKey ───────────────────────────────────────

    @Test fun `MacroImpact 키 형식 date_H_sorted_W_sorted_mode`() {
        val key = MacroImpactService.buildKey(
            "2026-06-11",
            holdings  = listOf("009150", "329180"),
            watchlist = listOf("000660"),
            mode      = AnalysisMode.DEFENSIVE,
        )
        assertEquals("2026-06-11|H:009150,329180|W:000660|DEFENSIVE", key)
    }

    @Test fun `MacroImpact holdings 순서가 달라도 같은 키`() {
        val k1 = MacroImpactService.buildKey("2026-06-11", listOf("329180", "009150"), listOf("000660"), AnalysisMode.DEFENSIVE)
        val k2 = MacroImpactService.buildKey("2026-06-11", listOf("009150", "329180"), listOf("000660"), AnalysisMode.DEFENSIVE)
        assertEquals(k1, k2)
    }

    @Test fun `MacroImpact watchlist 순서가 달라도 같은 키`() {
        val k1 = MacroImpactService.buildKey("2026-06-11", listOf("009150"), listOf("000660", "005930"), AnalysisMode.DEFENSIVE)
        val k2 = MacroImpactService.buildKey("2026-06-11", listOf("009150"), listOf("005930", "000660"), AnalysisMode.DEFENSIVE)
        assertEquals(k1, k2)
    }

    @Test fun `MacroImpact DEFENSIVE vs AGGRESSIVE 키가 달라야 한다`() {
        val def = MacroImpactService.buildKey("2026-06-11", listOf("009150"), listOf(), AnalysisMode.DEFENSIVE)
        val agg = MacroImpactService.buildKey("2026-06-11", listOf("009150"), listOf(), AnalysisMode.AGGRESSIVE)
        assertNotEquals(def, agg)
    }

    @Test fun `MacroImpact 빈 holdings와 watchlist도 키 생성 가능`() {
        val key = MacroImpactService.buildKey("2026-06-11", emptyList(), emptyList(), AnalysisMode.DEFENSIVE)
        assertEquals("2026-06-11|H:|W:|DEFENSIVE", key)
    }

    // ── MarketMoodService.buildKey ────────────────────────────────────────

    @Test fun `MarketMood 키 형식 date_mode`() {
        assertEquals("2026-06-11|DEFENSIVE",  MarketMoodService.buildKey("2026-06-11", AnalysisMode.DEFENSIVE))
        assertEquals("2026-06-11|AGGRESSIVE", MarketMoodService.buildKey("2026-06-11", AnalysisMode.AGGRESSIVE))
    }

    @Test fun `MarketMood 날짜가 다르면 키가 달라야 한다`() {
        val k1 = MarketMoodService.buildKey("2026-06-10", AnalysisMode.DEFENSIVE)
        val k2 = MarketMoodService.buildKey("2026-06-11", AnalysisMode.DEFENSIVE)
        assertNotEquals(k1, k2)
    }

    // ── SectorBriefingService.buildKey ────────────────────────────────────

    @Test fun `SectorBriefing 키 형식 date_sorted_codes`() {
        val key = SectorBriefingService.buildKey("2026-06-11", listOf("009150", "000660", "329180"))
        assertEquals("2026-06-11|000660,009150,329180", key)
    }

    @Test fun `SectorBriefing codes 순서가 달라도 같은 키`() {
        val k1 = SectorBriefingService.buildKey("2026-06-11", listOf("329180", "000660", "009150"))
        val k2 = SectorBriefingService.buildKey("2026-06-11", listOf("009150", "000660", "329180"))
        assertEquals(k1, k2)
    }

    @Test fun `SectorBriefing 종목 집합이 다르면 키가 달라야 한다`() {
        val k1 = SectorBriefingService.buildKey("2026-06-11", listOf("009150"))
        val k2 = SectorBriefingService.buildKey("2026-06-11", listOf("000660"))
        assertNotEquals(k1, k2)
    }
}
