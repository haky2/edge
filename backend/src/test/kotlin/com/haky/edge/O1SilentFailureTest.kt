package com.haky.edge

import com.haky.edge.ai.FileCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O1-3 FileCache 알림 디듀프 유닛 테스트.
 * alertedPrefixes(ConcurrentHashMap.Set)의 "프로세스당 최초 1회" 의미론 검증.
 * 실 GCS / OpsAlerter 의존 없음.
 */
class O1SilentFailureTest {

    @Test fun `같은 prefix 는 최초 1회만 add 성공`() {
        FileCache.alertedPrefixes.clear()
        val firstAdded = FileCache.alertedPrefixes.add("o1-test-analysis")
        val secondAdded = FileCache.alertedPrefixes.add("o1-test-analysis")
        assertTrue(firstAdded)
        assertFalse(secondAdded)
        assertEquals(1, FileCache.alertedPrefixes.filter { it.startsWith("o1-test") }.size)
    }

    @Test fun `다른 prefix 는 각각 독립 추적`() {
        FileCache.alertedPrefixes.clear()
        assertTrue(FileCache.alertedPrefixes.add("o1-mood"))
        assertTrue(FileCache.alertedPrefixes.add("o1-catalyst"))
        assertFalse(FileCache.alertedPrefixes.add("o1-mood"))
        assertEquals(2, FileCache.alertedPrefixes.size)
    }

    @Test fun `clear 후 재추가 가능`() {
        FileCache.alertedPrefixes.add("o1-analysis")
        FileCache.alertedPrefixes.clear()
        assertTrue(FileCache.alertedPrefixes.add("o1-analysis"))
    }
}
