package com.haky.edge

import com.haky.edge.kis.KisInvestorRow
import com.haky.edge.kis.KisPriceOutput
import com.haky.edge.kis.signMultiplier
import com.haky.edge.kis.toDoubleSafe
import com.haky.edge.kis.toInvestorFlow
import com.haky.edge.kis.toLongSafe
import com.haky.edge.kis.toQuote
import kotlin.test.Test
import kotlin.test.assertEquals

class KisMappersTest {

    // ── signMultiplier ────────────────────────────────────────────────────

    @Test fun `signMultiplier 상승 계열(1,2,3)은 +1`() {
        assertEquals(1, signMultiplier("1"))
        assertEquals(1, signMultiplier("2"))
        assertEquals(1, signMultiplier("3"))
    }

    @Test fun `signMultiplier 하락 계열(4,5)은 -1`() {
        assertEquals(-1, signMultiplier("4"))
        assertEquals(-1, signMultiplier("5"))
    }

    @Test fun `signMultiplier 앞뒤 공백을 trim하고 판단`() {
        assertEquals(-1, signMultiplier(" 4 "))
        assertEquals(-1, signMultiplier(" 5"))
        assertEquals(1,  signMultiplier("3 "))
    }

    @Test fun `signMultiplier 알 수 없는 값은 +1로 폴백`() {
        assertEquals(1, signMultiplier(""))
        assertEquals(1, signMultiplier("0"))
        assertEquals(1, signMultiplier("X"))
    }

    // ── toLongSafe ────────────────────────────────────────────────────────

    @Test fun `toLongSafe 정상 정수 파싱`() {
        assertEquals(86400L, "86400".toLongSafe())
        assertEquals(-500L,  "-500".toLongSafe())
        assertEquals(0L,     "0".toLongSafe())
    }

    @Test fun `toLongSafe 빈 문자열·공백은 0`() {
        assertEquals(0L, "".toLongSafe())
        assertEquals(0L, "   ".toLongSafe())
    }

    @Test fun `toLongSafe 파싱 불가 값은 0`() {
        assertEquals(0L, "abc".toLongSafe())
        assertEquals(0L, "1.5".toLongSafe())   // 소수점은 Long 파싱 실패
        assertEquals(0L, "--1".toLongSafe())
    }

    @Test fun `toLongSafe 앞뒤 공백 trim 후 파싱`() {
        assertEquals(1000L, " 1000 ".toLongSafe())
        assertEquals(-200L, " -200".toLongSafe())
    }

    // ── toDoubleSafe ──────────────────────────────────────────────────────

    @Test fun `toDoubleSafe 정상 실수 파싱`() {
        assertEquals(9.58,  "9.58".toDoubleSafe(),  1e-9)
        assertEquals(-9.58, "-9.58".toDoubleSafe(), 1e-9)
        assertEquals(0.0,   "0".toDoubleSafe(),     1e-9)
    }

    @Test fun `toDoubleSafe 빈 문자열·공백은 0`() {
        assertEquals(0.0, "".toDoubleSafe(),   1e-9)
        assertEquals(0.0, "  ".toDoubleSafe(), 1e-9)
    }

    @Test fun `toDoubleSafe 파싱 불가 값은 0`() {
        assertEquals(0.0, "abc".toDoubleSafe(), 1e-9)
    }

    // ── toQuote ───────────────────────────────────────────────────────────

    @Test fun `toQuote 필드 정상 변환`() {
        val raw = KisPriceOutput(
            price      = "86000",
            change     = "-192000",   // 이미 부호 포함
            changeRate = "-9.58",     // 이미 부호 포함
            volume     = "1234567",
            open       = "84000",
            high       = "87000",
            low        = "83000",
            high52w    = "95000",
            low52w     = "62000",
            per        = "23.5",
            pbr        = "1.2",
            sectorName = "전기·전자",
        )
        val q = raw.toQuote("009150")
        assertEquals("009150", q.code)
        assertEquals(86000L,   q.price)
        assertEquals(-192000L, q.change)       // 부호 보존
        assertEquals(-9.58,    q.changeRate, 1e-9)
        assertEquals(1234567L, q.volume)
        assertEquals(84000L,   q.open)
        assertEquals(87000L,   q.high)
        assertEquals(83000L,   q.low)
        assertEquals(95000L,   q.high52w)
        assertEquals(62000L,   q.low52w)
        assertEquals(23.5,     q.per,  1e-9)
        assertEquals(1.2,      q.pbr,  1e-9)
        assertEquals("전기·전자", q.sectorName)
    }

    @Test fun `toQuote 빈 문자열 필드는 0으로 안전 처리`() {
        val raw = KisPriceOutput()   // 모든 필드 기본값("0")
        val q = raw.toQuote("000000")
        assertEquals(0L,  q.price)
        assertEquals(0L,  q.change)
        assertEquals(0.0, q.changeRate, 1e-9)
        assertEquals("",  q.sectorName)
    }

    // ── toInvestorFlow ────────────────────────────────────────────────────

    @Test fun `toInvestorFlow 정상 변환(부호 포함)`() {
        val row = KisInvestorRow(
            date        = "20260610",
            foreign     = "150000",
            institution = "-80000",
            individual  = "-70000",
        )
        val flow = row.toInvestorFlow()
        assertEquals("20260610", flow.date)
        assertEquals(150000L,  flow.foreign)
        assertEquals(-80000L,  flow.institution)
        assertEquals(-70000L,  flow.individual)
    }

    @Test fun `toInvestorFlow 빈 문자열 필드는 0`() {
        val row = KisInvestorRow()
        val flow = row.toInvestorFlow()
        assertEquals("",  flow.date)
        assertEquals(0L,  flow.foreign)
        assertEquals(0L,  flow.institution)
        assertEquals(0L,  flow.individual)
    }
}
