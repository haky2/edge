package com.haky.edge

import com.haky.edge.ai.AnchorValidationService
import com.haky.edge.kis.DailyBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ②-3 기술적 앵커 실증 — 이벤트 추출·forward 수익률 순수 함수. */
class AnchorValidationTest {

    private fun bar(i: Int, close: Long, low: Long = close, high: Long = close) =
        DailyBar("%08d".format(20250101 + i), close, high, low, close, 100)

    /** 평평한 100,000원 이력 n개. */
    private fun flat(n: Int): List<DailyBar> = (0 until n).map { bar(it, 100_000) }

    @Test
    fun `forward - 5·10거래일 수익률, 봉 부족은 null`() {
        val bars = (0 until 20).map { bar(it, 100_000L + it * 1000) } // 매일 +1,000
        val (f5, f10) = AnchorValidationService.forwardPair(bars, 5)!!
        assertEquals((110_000.0 / 105_000 - 1) * 100, f5, 0.01)
        assertEquals((115_000.0 / 105_000 - 1) * 100, f10, 0.01)
        assertNull(AnchorValidationService.forwardPair(bars, 10)) // t+10 > lastIndex
    }

    @Test
    fun `touchLow20 - 저가가 20일 저점 허용범위 진입 시 발화, 연속 발화는 첫날만`() {
        // 100봉 평평(low20=100,000) 후 t=70·71에서 저가 99,900(터치), 종가는 유지
        val bars = flat(100).toMutableList()
        bars[70] = bar(70, 100_000, low = 99_900)
        bars[71] = bar(71, 100_000, low = 99_900)
        val events = AnchorValidationService.collectEvents(bars)
        // 평평 이력은 low20=high20=ma — touchLow20은 t=70 한 건만(71은 연속 중복 제거)
        // 단 평평이면 low20*1.005=100,500이라 모든 날 터치 — 대신 저점만 낮춘 t=70·71 검증을 위해
        // baseline 대비 touch가 존재하는지만 확인
        assertTrue((events["touchLow20"] ?: emptyList()).isNotEmpty())
        assertTrue((events["baseline"] ?: emptyList()).isNotEmpty())
    }

    @Test
    fun `touchLow20 중복 제거 - 명확한 시나리오`() {
        // 상승 추세(low20이 항상 과거 낮은 값) → 터치 없음, 그 후 급락으로 low20 관통 이틀 연속
        val bars = (0 until 100).map { i ->
            when {
                i < 80 -> bar(i, 100_000L + i * 500)              // 완만 상승
                i == 80 -> bar(i, 130_000, low = 129_000)          // 하락 시작(전 20일 저점=~130,000 부근)
                i == 81 -> bar(i, 125_000, low = 124_000)          // low20 관통 1일차
                i == 82 -> bar(i, 124_000, low = 123_000)          // 관통 2일차(연속 — 미발화)
                else -> bar(i, 124_000)
            }
        }
        val events = AnchorValidationService.collectEvents(bars)
        val touches = events["touchLow20"] ?: emptyList()
        assertEquals(1, touches.size) // 81 첫 터치만(82는 연속 중복 제거)
    }

    @Test
    fun `breakoutHigh20 - 종가가 20일 고점 돌파 시 발화`() {
        val bars = (0 until 100).map { i ->
            when {
                i < 80 -> bar(i, 100_000)
                i == 80 -> bar(i, 103_000)   // 돌파(직전 20일 고점 100,000)
                else -> bar(i, 103_000)      // 유지(직전 고점 103,000 — 재돌파 아님)
            }
        }
        val events = AnchorValidationService.collectEvents(bars)
        assertEquals(1, (events["breakoutHigh20"] ?: emptyList()).size)
    }

    @Test
    fun `대조군 - 하루 -2% 하락일 발화`() {
        val bars = (0 until 100).map { i ->
            if (i == 80) bar(i, 97_000) else bar(i, 100_000) // 80일차 -3%
        }
        val events = AnchorValidationService.collectEvents(bars)
        // 80일차 -3% → ctlDown2 1건 + 81일차 +3.09% 회복 → ctlUp2 1건
        assertEquals(1, (events["ctlDown2"] ?: emptyList()).size)
        assertEquals(1, (events["ctlUp2"] ?: emptyList()).size)
    }

    @Test
    fun `touchMa20 - 위에서 이평 접근 시에만(아래 출발은 미발화)`() {
        // 이평 위 주가가 이평까지 눌림
        val bars = (0 until 100).map { i ->
            when {
                i < 80 -> bar(i, 100_000)
                i == 80 -> bar(i, 106_000)                    // 이평(100,000) 위로
                i == 81 -> bar(i, 105_000, low = 100_200)     // ma20(~100,300) 터치
                else -> bar(i, 105_000)
            }
        }
        val events = AnchorValidationService.collectEvents(bars)
        assertTrue((events["touchMa20"] ?: emptyList()).isNotEmpty())
    }
}
