package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.ThesisSnapshot
import com.haky.edge.kis.DailyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C16 논지 변천 facts 블록 — 주가 조인·등락 병기·가드. */
class ThesisHistoryTest {

    private fun bar(ymd: String, close: Long) = DailyBar(ymd, close, close, close, close, 100)

    // 최신 앞(desc): 7/10=30,000 · 7/07=35,000 · 7/01=41,000
    private val bars = listOf(bar("20260710", 30_000), bar("20260707", 35_000), bar("20260701", 41_000))

    @Test
    fun `이력 2건 미만이면 null(변천 없음)`() {
        assertNull(AnalysisService.thesisHistoryText(emptyList(), bars))
        assertNull(AnalysisService.thesisHistoryText(listOf(ThesisSnapshot("2026-07-01", "가설A")), bars))
        // 빈 텍스트는 유효 스냅샷이 아님
        assertNull(AnalysisService.thesisHistoryText(
            listOf(ThesisSnapshot("2026-07-01", "가설A"), ThesisSnapshot("2026-07-10", "  ")), bars))
    }

    @Test
    fun `변천 블록 - 시점 주가 조인 + 최초 대비 등락 병기 + 현재 표시`() {
        val text = AnalysisService.thesisHistoryText(
            listOf(
                ThesisSnapshot("2026-07-10", "장기 성장성은 유효"),   // 순서 뒤섞여 들어와도
                ThesisSnapshot("2026-07-01", "수주 사이클 회복 초입"), // 날짜로 정렬됨
            ), bars)!!
        val lines = text.trim().lines()
        assertTrue(lines[0].contains("내 투자 논지 변천"))
        assertTrue(lines[1].contains("2026-07-01") && lines[1].contains("41,000원") && lines[1].contains("수주 사이클"))
        // 7/10 주가 30,000 = 최초(41,000) 대비 -26.8%
        assertTrue(lines[2].contains("30,000원") && lines[2].contains("-26.8%") && lines[2].endsWith("← 현재"))
    }

    @Test
    fun `일봉 범위 밖 날짜는 주가 생략, 조인은 이전 거래일로 접힘`() {
        val text = AnalysisService.thesisHistoryText(
            listOf(
                ThesisSnapshot("2026-06-01", "옛 가설"),         // 일봉(7/1~) 이전 → 주가 생략
                ThesisSnapshot("2026-07-09", "새 가설"),         // 7/9 봉 없음 → 7/7(35,000)로 접힘
            ), bars)!!
        val lines = text.trim().lines()
        assertTrue(lines[1].contains("2026-06-01") && !lines[1].contains("원"))
        assertTrue(lines[2].contains("35,000원"))
    }

    @Test
    fun `상승 후 변경은 플러스 부호`() {
        val up = listOf(bar("20260710", 50_000), bar("20260701", 41_000))
        val text = AnalysisService.thesisHistoryText(
            listOf(ThesisSnapshot("2026-07-01", "A"), ThesisSnapshot("2026-07-10", "B")), up)!!
        assertTrue(text.contains("+22.0%"))
    }
}
