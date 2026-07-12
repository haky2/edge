package com.haky.edge

import com.haky.edge.ai.CatalystValidationService
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.IndexPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ②-1 catalyst 판정 실증 — forward (raw, excess) 수익률 순수 함수. */
class CatalystValidationTest {

    private fun bar(ymd: String, close: Long) = DailyBar(ymd, close, close, close, close, 100)
    private fun idx(ymd: String, close: Double) = IndexPoint(ymd, close)

    // 종목: 100 → 105 → 110 → 120 (3거래일), 코스피: 1000 → 1010 → 1000 → 990
    private val stock = listOf(
        bar("20260706", 100), bar("20260707", 105), bar("20260708", 110), bar("20260709", 120),
    )
    private val kospi = listOf(
        idx("20260706", 1000.0), idx("20260707", 1010.0), idx("20260708", 1000.0), idx("20260709", 990.0),
    )

    @Test
    fun `초과수익 - 종목 상승분에서 코스피 상승분을 뺀다`() {
        // 이벤트 7/6, horizon 1: raw = +5%, kospi = +1% → excess = +4%
        val (raw, excess) = CatalystValidationService.forwardPair("20260706", stock, kospi, 1)!!
        assertEquals(5.0, raw, 0.01)
        assertEquals(4.0, excess, 0.01)
    }

    @Test
    fun `초과수익 - 시장 하락 국면의 호재 판정을 구제한다`() {
        // 이벤트 7/7, horizon 2: raw = (120/105-1) = +14.29%, kospi = (990/1010-1) = -1.98% → excess ≈ +16.27%
        val (raw, excess) = CatalystValidationService.forwardPair("20260707", stock, kospi, 2)!!
        assertEquals(14.29, raw, 0.01)
        assertEquals(16.27, excess, 0.01)
    }

    @Test
    fun `기준봉 - 이벤트일이 휴장일이면 직전 거래일`() {
        // 7/6과 7/7 사이 주말 가정: 이벤트 "20260707" 이전(당일 포함) 마지막 봉 규약 확인 —
        // 존재하지 않는 날짜 "2026-07-06 저녁 뉴스=20260706" 처리와 동일하게 indexOfLast(date<=ymd)
        val (raw, _) = CatalystValidationService.forwardPair("20260706", stock, kospi, 1)!!
        assertEquals(5.0, raw, 0.01)
    }

    @Test
    fun `forward 봉 부족·조인 실패는 null`() {
        // 마지막 봉이 기준 → horizon 1 불가
        assertNull(CatalystValidationService.forwardPair("20260709", stock, kospi, 1))
        // 이벤트가 이력보다 과거 → 기준봉 없음
        assertNull(CatalystValidationService.forwardPair("20260601", stock, kospi, 1))
        // 코스피 이력이 짧아 조인 실패
        assertNull(CatalystValidationService.forwardPair("20260708", stock, kospi.take(2), 1))
    }

    @Test
    fun `중립 밴드 - horizon별 휴리스틱`() {
        assertEquals(1.0, CatalystValidationService.neutralBand(1))
        assertEquals(2.0, CatalystValidationService.neutralBand(5))
        assertEquals(3.0, CatalystValidationService.neutralBand(20))
    }
}
