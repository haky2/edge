package com.haky.edge

import com.haky.edge.routes.parseOverseasCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverseasCodeParseTest {

    @Test fun `NAS code parses correctly`() =
        assertEquals(Pair("NAS", "AAPL"), parseOverseasCode("US:NAS:AAPL"))

    @Test fun `NYS code parses correctly`() =
        assertEquals(Pair("NYS", "MSFT"), parseOverseasCode("US:NYS:MSFT"))

    @Test fun `symbol with dot parses correctly`() =
        assertEquals(Pair("NYS", "BRK.B"), parseOverseasCode("US:NYS:BRK.B"))

    @Test fun `domestic 6-digit code returns null`() =
        assertNull(parseOverseasCode("005930"))

    @Test fun `missing exchange segment returns null`() =
        assertNull(parseOverseasCode("US:AAPL"))

    @Test fun `empty string returns null`() =
        assertNull(parseOverseasCode(""))

    @Test fun `wrong prefix returns null`() =
        assertNull(parseOverseasCode("KR:KSE:005930"))

    @Test fun `TSE code parses correctly`() =
        assertEquals(Pair("TSE", "7203"), parseOverseasCode("US:TSE:7203"))
}
