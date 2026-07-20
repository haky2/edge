package com.haky.edge

import com.haky.edge.usage.UsageEvent
import com.haky.edge.usage.UsageEventLog
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageEventLogTest {

    private val tmpDir = File(System.getProperty("java.io.tmpdir"), "usage_test_${System.nanoTime()}")
        .also { it.mkdirs() }
    private val log = UsageEventLog(tmpDir.path)

    @AfterTest fun cleanup() { tmpDir.deleteRecursively() }

    private fun ev(screen: String, card: String, action: String, at: String) =
        UsageEvent(screen = screen, card = card, action = action, at = at)

    private val today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString() + "T10:00:00"

    @Test fun `append then stats round-trip`() = runBlocking {
        val n = log.appendBatch(listOf(
            ev("detail", "", "view", today),
            ev("detail", "배당", "expand", today),
            ev("detail", "배당", "expand", "${today}0"),  // 다른 at → 별도 건
        ))
        assertEquals(3, n)
        val stats = log.stats()
        assertEquals(3, stats.totalEvents)
        // (detail,배당,expand) 2건 집계
        val dividend = stats.cards.first { it.card == "배당" && it.action == "expand" }
        assertEquals(2, dividend.count)
        val view = stats.cards.first { it.action == "view" }
        assertEquals(1, view.count)
    }

    @Test fun `dedup on screen-card-action-at makes batch idempotent`() = runBlocking {
        val batch = listOf(ev("detail", "배당", "expand", today))
        assertEquals(1, log.appendBatch(batch))
        // 같은 배치 재전송(부분 실패 재시도) → 0건 추가.
        assertEquals(0, log.appendBatch(batch))
        assertEquals(1, log.stats().totalEvents)
    }

    @Test fun `dedup within a single batch`() = runBlocking {
        // 큐에 같은 이벤트가 두 번 들어간 경우 배치 내부에서도 1건으로.
        val n = log.appendBatch(listOf(
            ev("stats", "", "view", today),
            ev("stats", "", "view", today),
        ))
        assertEquals(1, n)
    }

    @Test fun `invalid action is dropped`() = runBlocking {
        val n = log.appendBatch(listOf(ev("detail", "배당", "collapse", today)))
        assertEquals(0, n)
    }

    @Test fun `entries older than retention window are excluded from stats`() = runBlocking {
        val old = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(100).toString() + "T10:00:00"
        log.appendBatch(listOf(
            ev("detail", "", "view", old),
            ev("detail", "", "view", today),
        ))
        // 파일엔 2건 append됐지만 stats는 최근 90일만 → 1건.
        val stats = log.stats()
        assertEquals(1, stats.totalEvents)
        assertEquals(today.take(10), stats.lastDate)
    }
}
