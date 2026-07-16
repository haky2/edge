package com.haky.edge.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaxEngineTest {

    private fun pos(
        code: String = "005930",
        type: AccountTaxType = AccountTaxType.GENERAL,
        avg: Double,
        qty: Double,
        cur: Double,
    ) = TaxablePosition(code, type, avg, qty, cur)

    // ── 국내: 차익 비과세 + 거래세는 매도가액 전체 기준 ─────────────────────────

    @Test
    fun `국내 일반 - 세후 = 세전 - 매도가액x0_2pct`() {
        // 평단 50,000 → 현재 60,000 × 100주: 세전 +1,000,000, 매도가액 6,000,000
        val r = TaxEngine.compute(listOf(pos(avg = 50_000.0, qty = 100.0, cur = 60_000.0)))
        assertEquals(1_000_000L, r.taxableGross)
        assertEquals(12_000L, r.transactionTax) // 6,000,000 × 0.2%
        assertEquals(0L, r.overseasTax)
        assertEquals(988_000L, r.netPnl)
    }

    @Test
    fun `국내 - 손실 종목을 팔아도 거래세는 낸다`() {
        // 평단 100,000 → 현재 80,000 × 10주: 세전 -200,000, 그래도 거래세 800,000×0.2%=1,600
        val r = TaxEngine.compute(listOf(pos(avg = 100_000.0, qty = 10.0, cur = 80_000.0)))
        assertEquals(-200_000L, r.taxableGross)
        assertEquals(1_600L, r.transactionTax)
        assertEquals(-201_600L, r.netPnl) // 손실 + 거래세 = 더 큰 손실
    }

    @Test
    fun `ISA - 매매차익 기준 일반과 동일한 숫자, hasIsa만 참`() {
        val general = TaxEngine.compute(listOf(pos(avg = 50_000.0, qty = 100.0, cur = 60_000.0)))
        val isa = TaxEngine.compute(
            listOf(pos(type = AccountTaxType.ISA, avg = 50_000.0, qty = 100.0, cur = 60_000.0))
        )
        assertEquals(general.netPnl, isa.netPnl)
        assertEquals(general.transactionTax, isa.transactionTax)
        assertFalse(general.hasIsa)
        assertTrue(isa.hasIsa)
    }

    // ── 연금: 과세이연 — 숫자 계산에서 제외, 분리 표시 ─────────────────────────

    @Test
    fun `연금 - 세후 계산에서 제외되고 pensionGross로 분리된다`() {
        val r = TaxEngine.compute(
            listOf(
                pos(avg = 50_000.0, qty = 100.0, cur = 60_000.0), // 일반 +1,000,000
                pos(type = AccountTaxType.PENSION, avg = 10_000.0, qty = 50.0, cur = 14_000.0), // 연금 +200,000
            )
        )
        assertEquals(1_000_000L, r.taxableGross)   // 연금분 미포함
        assertEquals(200_000L, r.pensionGross)
        assertEquals(12_000L, r.transactionTax)    // 연금 매도가액은 거래세 대상 아님
        assertTrue(r.hasPension)
    }

    // ── 해외: 손익통산 → 250만 공제 → 22%/27.5% ────────────────────────────────

    @Test
    fun `해외 - 통산 차익이 250만 이하면 세금 0`() {
        // +2,000,000: 공제 안에서 소화
        val r = TaxEngine.compute(
            listOf(pos(code = "US:NAS:AAPL", avg = 100_000.0, qty = 20.0, cur = 200_000.0))
        )
        assertEquals(2_000_000L, r.taxableGross)
        assertEquals(0L, r.overseasTax)
        assertEquals(0L, r.transactionTax) // 해외엔 국내 거래세 없음
        assertEquals(2_000_000L, r.netPnl)
    }

    @Test
    fun `해외 - 250만 초과분에 22pct`() {
        // +10,000,000 → 과세표준 7,500,000 × 22% = 1,650,000
        val r = TaxEngine.compute(
            listOf(pos(code = "US:NAS:NVDA", avg = 100_000.0, qty = 100.0, cur = 200_000.0))
        )
        assertEquals(1_650_000L, r.overseasTax)
        assertEquals(8_350_000L, r.netPnl)
    }

    @Test
    fun `해외 - 종목 간 손익통산 후 공제`() {
        // +10,000,000 -8,000,000 = 통산 +2,000,000 ≤ 250만 → 세금 0
        val r = TaxEngine.compute(
            listOf(
                pos(code = "US:NAS:NVDA", avg = 100_000.0, qty = 100.0, cur = 200_000.0),
                pos(code = "US:NAS:TSLA", avg = 200_000.0, qty = 80.0, cur = 100_000.0),
            )
        )
        assertEquals(0L, r.overseasTax)
        assertEquals(2_000_000L, r.netPnl)
    }

    @Test
    fun `해외 - 통산이 음수면 세금 0 (음수 공제로 환급 계산하지 않음)`() {
        val r = TaxEngine.compute(
            listOf(pos(code = "US:NAS:TSLA", avg = 200_000.0, qty = 10.0, cur = 100_000.0))
        )
        assertEquals(-1_000_000L, r.taxableGross)
        assertEquals(0L, r.overseasTax)
        assertEquals(-1_000_000L, r.netPnl)
    }

    @Test
    fun `해외 - 과세표준 3억 초과분은 27_5pct`() {
        // 차익 402,500,000 → 과세표준 400,000,000 = 3억×22% + 1억×27.5% = 66,000,000+27,500,000
        val r = TaxEngine.compute(
            listOf(pos(code = "US:NAS:NVDA", avg = 0.0, qty = 1.0, cur = 402_500_000.0))
        )
        assertEquals(93_500_000L, r.overseasTax)
    }

    // ── 계좌 타입 추론: 프리셋 정확 매칭만 ─────────────────────────────────────

    @Test
    fun `taxTypeOf - 프리셋 매칭, 커스텀 이름은 일반`() {
        assertEquals(AccountTaxType.ISA, TaxEngine.taxTypeOf("ISA"))
        assertEquals(AccountTaxType.PENSION, TaxEngine.taxTypeOf("IRP개인연금"))
        assertEquals(AccountTaxType.PENSION, TaxEngine.taxTypeOf("퇴직연금"))
        assertEquals(AccountTaxType.GENERAL, TaxEngine.taxTypeOf("기본"))
        assertEquals(AccountTaxType.GENERAL, TaxEngine.taxTypeOf("일반"))
        // 커스텀 이름은 추론하지 않는다(스펙: 카드가 적용 타입을 노출해 오분류를 보이게 함)
        assertEquals(AccountTaxType.GENERAL, TaxEngine.taxTypeOf("미래에셋 IRP"))
    }

    @Test
    fun `빈 포지션 - 전부 0, 플래그 전부 거짓`() {
        val r = TaxEngine.compute(emptyList())
        assertEquals(0L, r.netPnl)
        assertEquals(0L, r.taxableGross)
        assertFalse(r.hasPension)
        assertFalse(r.hasIsa)
        assertFalse(r.hasOverseas)
    }

    @Test
    fun `혼합 - 국내 일반 + ISA + 연금 + 해외 동시`() {
        val r = TaxEngine.compute(
            listOf(
                pos(avg = 50_000.0, qty = 100.0, cur = 60_000.0),                                  // 일반 +1,000,000 / 거래세 12,000
                pos(type = AccountTaxType.ISA, avg = 10_000.0, qty = 100.0, cur = 12_000.0),       // ISA +200,000 / 거래세 2,400
                pos(type = AccountTaxType.PENSION, avg = 20_000.0, qty = 10.0, cur = 25_000.0),    // 연금 +50,000 (제외)
                pos(code = "US:NAS:AAPL", avg = 100_000.0, qty = 50.0, cur = 200_000.0),           // 해외 +5,000,000 → (500-250)만×22%=550,000
            )
        )
        assertEquals(6_200_000L, r.taxableGross)  // 1,000,000+200,000+5,000,000
        assertEquals(14_400L, r.transactionTax)
        assertEquals(550_000L, r.overseasTax)
        assertEquals(5_635_600L, r.netPnl)
        assertEquals(50_000L, r.pensionGross)
        assertTrue(r.hasPension)
        assertTrue(r.hasIsa)
        assertTrue(r.hasOverseas)
    }
}
