package com.haky.edge

import com.haky.edge.slack.SignalFired
import com.haky.edge.slack.SignalFiredLog
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 신호 발화 로그(N3-a) — (date,code,kind,detail) 디듀프 append-only jsonl. */
class SignalFiredLogTest {

    private fun tempDir(): File = File.createTempFile("signal-fired", "").let {
        it.delete(); it.mkdirs(); it
    }

    private fun entry(date: String, code: String, kind: String, detail: String) =
        SignalFired(date = date, code = code, kind = kind, detail = detail)

    @Test
    fun `append 후 stats 왕복`(): Unit = runBlocking {
        val dir = tempDir()
        val log = SignalFiredLog(dir.absolutePath)
        assertEquals(2, log.appendNew(listOf(
            entry("2026-07-19", "005930", "FLOW", "외국인 3일 연속 순매수"),
            entry("2026-07-19", "000660", "VALUATION", "PER 8.0배 (역사적 하위 5%)"),
        )))
        val s = log.stats()
        assertEquals(2, s.rows)
        assertEquals(mapOf("FLOW" to 1, "VALUATION" to 1), s.kinds)
        assertEquals("2026-07-19", s.firstDate)
        assertEquals("2026-07-19", s.lastDate)
        dir.deleteRecursively()
    }

    @Test
    fun `같은 (date,code,kind,detail)은 재스캔해도 중복 기록되지 않는다`(): Unit = runBlocking {
        val dir = tempDir()
        val log = SignalFiredLog(dir.absolutePath)
        val e = entry("2026-07-19", "005930", "FLOW", "외국인 3일 연속 순매수")
        assertEquals(1, log.appendNew(listOf(e)))
        // 같은 날 재스캔 — 동일 엔트리는 0건.
        assertEquals(0, log.appendNew(listOf(e)))
        assertEquals(1, log.stats().rows)
        dir.deleteRecursively()
    }

    @Test
    fun `재시작 후에도 디듀프 유지 - 키 셋을 파일에서 재로드`(): Unit = runBlocking {
        val dir = tempDir()
        val e = entry("2026-07-19", "005930", "DISCLOSURE", "수주계약 체결")
        SignalFiredLog(dir.absolutePath).appendNew(listOf(e))
        val reopened = SignalFiredLog(dir.absolutePath)
        assertEquals(0, reopened.appendNew(listOf(e)))
        assertEquals(1, reopened.stats().rows)
        dir.deleteRecursively()
    }

    @Test
    fun `손상 줄은 건너뛰고 나머지는 읽는다`(): Unit = runBlocking {
        val dir = tempDir()
        val log = SignalFiredLog(dir.absolutePath)
        log.appendNew(listOf(entry("2026-07-19", "005930", "FLOW", "외국인 3일 연속 순매수")))
        File(dir, "signal_fired.jsonl").appendText("{{{corrupted\n\n")
        log.appendNew(listOf(entry("2026-07-19", "000660", "VALUATION", "PER 하단권")))

        assertEquals(2, log.stats().rows)
        dir.deleteRecursively()
    }

    @Test
    fun `빈 목록 append는 파일을 만들지 않는다`(): Unit = runBlocking {
        val dir = tempDir()
        val log = SignalFiredLog(dir.absolutePath)
        assertEquals(0, log.appendNew(emptyList()))
        assertTrue(!File(dir, "signal_fired.jsonl").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `REBALANCE 신호는 code 빈 문자열로 기록된다`(): Unit = runBlocking {
        val dir = tempDir()
        val log = SignalFiredLog(dir.absolutePath)
        log.appendNew(listOf(entry("2026-07-19", "", "REBALANCE", "비중 초과 종목 발생")))
        val s = log.stats()
        assertEquals(1, s.rows)
        assertEquals(mapOf("REBALANCE" to 1), s.kinds)
        dir.deleteRecursively()
    }
}
