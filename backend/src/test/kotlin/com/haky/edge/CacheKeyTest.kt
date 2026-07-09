package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.Position
import com.haky.edge.ai.effectiveMarketDate
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

    // ── 투자 논지(thesis) 키 분리 ─────────────────────────────────────────

    @Test fun `Analysis 논지 없음·빈 문자열·공백은 기존 키 그대로(공유 캐시 불변)`() {
        val base = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null)
        assertEquals(base, AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null, null))
        assertEquals(base, AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null, ""))
        assertEquals(base, AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null, "  "))
    }

    @Test fun `Analysis 논지 있으면 키 분리, 논지가 다르면 다른 키`() {
        val noThesis = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null)
        val t1 = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null, "수주 모멘텀")
        val t2 = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null, "밸류 저평가")
        assertNotEquals(noThesis, t1)
        assertNotEquals(t1, t2)
    }

    @Test fun `Analysis 같은 논지는 같은 키(캐시 적중), 앞뒤 공백 무시`() {
        val k1 = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null, "수주 모멘텀")
        val k2 = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, null, " 수주 모멘텀 ")
        assertEquals(k1, k2)
    }

    @Test fun `Analysis 포지션+논지 조합 키에 둘 다 반영`() {
        val pos = Position(avgPrice = 86000.0, qty = 10L)
        val posOnly = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, pos)
        val both = AnalysisService.buildKey("009150", "2026-06-11", AnalysisMode.DEFENSIVE, pos, "수주 모멘텀")
        assertNotEquals(posOnly, both)
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

    // ── effectiveMarketDate (주말 통합 거래일) ──────────────────────────────

    @Test fun `평일은 그대로 반환`() {
        // 2026-06-11 = 목요일
        assertEquals("2026-06-11", effectiveMarketDate(java.time.LocalDate.of(2026, 6, 11)))
    }

    @Test fun `토요일은 그대로 반환(금요일과 구분돼 새로 생성)`() {
        // 2026-06-13 = 토요일
        assertEquals("2026-06-13", effectiveMarketDate(java.time.LocalDate.of(2026, 6, 13)))
    }

    @Test fun `일요일은 토요일로 접혀 재사용`() {
        // 2026-06-14(일) → 2026-06-13(토)
        assertEquals("2026-06-13", effectiveMarketDate(java.time.LocalDate.of(2026, 6, 14)))
    }

    @Test fun `토요일과 일요일은 같은 거래일 키`() {
        val sat = effectiveMarketDate(java.time.LocalDate.of(2026, 6, 13))
        val sun = effectiveMarketDate(java.time.LocalDate.of(2026, 6, 14))
        assertEquals(sat, sun)
    }

    @Test fun `금요일과 토요일은 다른 거래일 키`() {
        val fri = effectiveMarketDate(java.time.LocalDate.of(2026, 6, 12))
        val sat = effectiveMarketDate(java.time.LocalDate.of(2026, 6, 13))
        assertNotEquals(fri, sat)
    }
}
