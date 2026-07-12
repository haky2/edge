package com.haky.edge

import com.haky.edge.macro.MarketMoodService
import com.haky.edge.macro.MoodAccuracyReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ① 자기 교정 — 방향 예측 성적표 facts 블록(표본 가드·문구). */
class MoodAccuracyFactsTest {

    @Test
    fun `표본 15 미만은 침묵(null)`() {
        assertNull(MarketMoodService.accuracyFactsText(MoodAccuracyReport(total = 14, correct = 7, pending = 3, recentEntries = emptyList())))
        assertNull(MarketMoodService.accuracyFactsText(MoodAccuracyReport(total = 0, correct = 0, pending = 0, recentEntries = emptyList())))
    }

    @Test
    fun `표본 15 이상이면 누적 성적 한 줄`() {
        val text = MarketMoodService.accuracyFactsText(MoodAccuracyReport(total = 21, correct = 9, pending = 2, recentEntries = emptyList()))!!
        assertTrue(text.contains("방향 예측 자기 성적표"))
        assertTrue(text.contains("누적 21회 채점 중 9회 적중(43%)"))
    }

    @Test
    fun `반올림 확인`() {
        val text = MarketMoodService.accuracyFactsText(MoodAccuracyReport(total = 16, correct = 8, pending = 0, recentEntries = emptyList()))!!
        assertTrue(text.contains("(50%)"))
        assertEquals(2, text.lines().size)
    }
}
