package com.haky.edge

import com.haky.edge.watchlist.WatchlistRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WatchlistRegistry — 기기별 등록·합집합·만료·폴백 의미론 검증. 실 파일(임시 디렉터리)만 사용.
 */
class WatchlistRegistryTest {

    private fun tempDir(): String =
        Files.createTempDirectory("wl-reg-test").toFile().absolutePath

    @Test fun `등록 없으면 폴백 반환`() {
        val reg = WatchlistRegistry(dataDir = tempDir(), fallback = listOf("005930"))
        assertEquals(listOf("005930"), reg.activeCodes())
    }

    @Test fun `여러 기기 관심종목 합집합(중복 제거)`() {
        val reg = WatchlistRegistry(dataDir = tempDir(), fallback = listOf("005930"))
        reg.sync("dev-A", listOf("005930", "000660"))
        reg.sync("dev-B", listOf("000660", "012450"))
        val active = reg.activeCodes().toSet()
        assertEquals(setOf("005930", "000660", "012450"), active)
    }

    @Test fun `sync 는 같은 기기를 덮어씀(최신 목록만)`() {
        val reg = WatchlistRegistry(dataDir = tempDir(), fallback = listOf("005930"))
        reg.sync("dev-A", listOf("005930", "000660"))
        reg.sync("dev-A", listOf("047810"))   // 관심종목 교체
        assertEquals(listOf("047810"), reg.activeCodes())
    }

    @Test fun `만료된 기기는 합집합에서 제외되고 폴백으로 복귀`() {
        val dir = tempDir()
        // expiryDays=0 이면 방금 sync 도 cutoff(now) 이전이라 즉시 만료 → 폴백.
        val reg = WatchlistRegistry(dataDir = dir, fallback = listOf("005930"), expiryDays = 0)
        reg.sync("dev-A", listOf("000660"))
        assertEquals(listOf("005930"), reg.activeCodes())
    }

    @Test fun `빈 deviceId 는 무시`() {
        val reg = WatchlistRegistry(dataDir = tempDir(), fallback = listOf("005930"))
        reg.sync("", listOf("000660"))
        assertEquals(listOf("005930"), reg.activeCodes())
    }

    @Test fun `재생성해도 파일에서 복원(영속)`() {
        val dir = tempDir()
        WatchlistRegistry(dataDir = dir, fallback = listOf("005930")).sync("dev-A", listOf("000660", "012450"))
        val reloaded = WatchlistRegistry(dataDir = dir, fallback = listOf("005930"))
        assertTrue(reloaded.activeCodes().containsAll(listOf("000660", "012450")))
    }
}
