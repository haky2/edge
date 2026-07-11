package com.haky.edge

import com.haky.edge.ai.RegimeDetector
import com.haky.edge.news.TargetPriceTrend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegimeDetectorTest {

    private fun trend(direction: String, changePct: Double = 5.0) = TargetPriceTrend(
        current = 500000, baseline = 450000, baselineDate = "2026-06-01",
        changePct = changePct, direction = direction, snapshotCount = 10, daySpan = 30,
    )

    @Test fun `목표가 초과 + 상향 추세 = 리레이팅`() {
        // 삼성전자 시나리오: 주가가 목표가를 못 쫓아오고 목표가는 계속 상향.
        val r = RegimeDetector.detect(
            price = 460000, consensusTarget = 450000,
            targetTrend = trend("상향"), quarterlyYoyPct = null, perPercentile = null,
        )
        assertEquals(true, r?.label?.contains("리레이팅"))
        assertEquals(2, r?.signals?.size)
    }

    @Test fun `이익 급증 + 상향 추세 = 리레이팅, 밴드 상단 신호 가산`() {
        val r = RegimeDetector.detect(
            price = 300000, consensusTarget = 450000, // 목표가엔 한참 못 미침 — 신호 아님
            targetTrend = trend("상향"), quarterlyYoyPct = 474.3, perPercentile = 100,
        )
        assertEquals(true, r?.label?.contains("리레이팅"))
        // 상향 + YoY 급증 + (up이 있으므로) 밴드 상단 보강 = 3개
        assertEquals(3, r?.signals?.size)
    }

    @Test fun `신호 1개뿐이면 판정 없음 - 일반 국면`() {
        assertNull(RegimeDetector.detect(
            price = 300000, consensusTarget = 450000,
            targetTrend = trend("상향"), quarterlyYoyPct = 10.0, perPercentile = null,
        ))
    }

    @Test fun `밴드 상단 단독으로는 리레이팅 아님 - 그냥 고평가일 뿐`() {
        assertNull(RegimeDetector.detect(
            price = 300000, consensusTarget = 450000,
            targetTrend = null, quarterlyYoyPct = null, perPercentile = 100,
        ))
    }

    @Test fun `목표가 하향 + 이익 급감 = 디레이팅 경계, 밴드 하단 신호 가산`() {
        val r = RegimeDetector.detect(
            price = 100000, consensusTarget = 200000,
            targetTrend = trend("하향", -8.0), quarterlyYoyPct = -45.0, perPercentile = 10,
        )
        assertEquals(true, r?.label?.contains("디레이팅"))
        // 하향 + YoY 급감 + (down 성립이므로) 밴드 하단 보강 = 3개(상단 보강과 대칭)
        assertEquals(3, r?.signals?.size)
    }

    @Test fun `실질 신호 1개 + 밴드 상단으로는 판정 성립 안 됨 - O1 부스터 루프홀 회귀 방지`() {
        // 기존 버그: 상향 추세 1개 + 밴드 상단 보강 = 2개로 MIN_SIGNALS 충족 → 리레이팅 판정.
        // 보강 신호는 카운트 미포함이어야 한다 — 실질 신호 2개가 안 되면 일반 국면.
        assertNull(RegimeDetector.detect(
            price = 300000, consensusTarget = 450000, // 목표가엔 한참 못 미침 — 신호 아님
            targetTrend = trend("상향"), quarterlyYoyPct = null, perPercentile = 100,
        ))
    }

    @Test fun `데이터 전부 없으면 null`() {
        assertNull(RegimeDetector.detect(100000, null, null, null, null))
    }

    @Test fun `근거 문구에 수치 포함`() {
        val r = RegimeDetector.detect(
            price = 470000, consensusTarget = 450000,
            targetTrend = trend("상향", 5.0), quarterlyYoyPct = null, perPercentile = null,
        )
        assertTrue(r!!.signals.any { it.contains("4.4%") }, "초과율 수치 포함: ${r.signals}")
        assertTrue(r.signals.any { it.contains("+5.0%") }, "추세 수치 포함: ${r.signals}")
    }
}
