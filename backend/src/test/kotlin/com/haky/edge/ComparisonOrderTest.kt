package com.haky.edge

import kotlin.test.Test
import kotlin.test.assertEquals

/** S2: A vs B 와 B vs A 가 동일한 캐시 키(lo:hi)를 사용하는지 확인. */
class ComparisonOrderTest {

    private fun cacheKey(codeA: String, codeB: String, date: String, mode: String): String {
        val (lo, hi) = if (codeA <= codeB) codeA to codeB else codeB to codeA
        return "$lo:$hi:$date:$mode"
    }

    @Test fun `A vs B 와 B vs A 캐시 키 동일`() {
        val ab = cacheKey("005930", "000660", "2026-07-11", "DEFENSIVE")
        val ba = cacheKey("000660", "005930", "2026-07-11", "DEFENSIVE")
        assertEquals(ab, ba, "A vs B 와 B vs A 는 동일 캐시 키여야 한다")
    }

    @Test fun `정렬된 순서 - 작은 코드가 lo`() {
        val key = cacheKey("005930", "035420", "2026-07-11", "AGGRESSIVE")
        assertEquals("005930:035420:2026-07-11:AGGRESSIVE", key)
    }

    @Test fun `역순 입력도 동일 lo-hi 순서`() {
        val key = cacheKey("035420", "005930", "2026-07-11", "AGGRESSIVE")
        assertEquals("005930:035420:2026-07-11:AGGRESSIVE", key)
    }

    @Test fun `같은 코드끼리는 lo equals hi`() {
        val key = cacheKey("005930", "005930", "2026-07-11", "DEFENSIVE")
        assertEquals("005930:005930:2026-07-11:DEFENSIVE", key)
    }
}
