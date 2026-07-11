package com.haky.edge

import com.haky.edge.util.DayScopedCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayScopedCacheTest {

    @Test fun `같은 날짜 get-put 정상 동작`() {
        val cache = DayScopedCache<String>()
        cache.put("2026-07-11", "k1", "v1")
        assertEquals("v1", cache.get("2026-07-11", "k1"))
    }

    @Test fun `날짜 바뀌면 이전 엔트리 자동 제거`() {
        val cache = DayScopedCache<String>()
        cache.put("2026-07-10", "k1", "old")
        // 다음날 날짜로 접근 → 전체 clear 후 miss 반환
        assertNull(cache.get("2026-07-11", "k1"))
    }

    @Test fun `날짜 바뀐 뒤 새 값은 정상 저장`() {
        val cache = DayScopedCache<String>()
        cache.put("2026-07-10", "k1", "old")
        cache.put("2026-07-11", "k2", "new")
        assertNull(cache.get("2026-07-11", "k1"))   // 이전 날짜 엔트리 없음
        assertEquals("new", cache.get("2026-07-11", "k2"))
    }

    @Test fun `같은 날짜 여러 키 공존`() {
        val cache = DayScopedCache<Int>()
        cache.put("2026-07-11", "a", 1)
        cache.put("2026-07-11", "b", 2)
        assertEquals(1, cache.get("2026-07-11", "a"))
        assertEquals(2, cache.get("2026-07-11", "b"))
    }

    @Test fun `miss 시 null 반환`() {
        val cache = DayScopedCache<String>()
        assertNull(cache.get("2026-07-11", "unknown"))
    }
}
