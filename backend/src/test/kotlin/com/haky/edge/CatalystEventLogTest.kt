package com.haky.edge

import com.haky.edge.ai.CatalystEvent
import com.haky.edge.ai.CatalystEventLog
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F2 슬라이스 2-0 — 재료 이벤트 jsonl 로그(append-only). */
class CatalystEventLogTest {

    private fun tempDir(): File = File.createTempFile("catalyst-events", "").let {
        it.delete(); it.mkdirs(); it
    }

    private fun event(url: String, code: String = "005930") = CatalystEvent(
        code = code, date = "20260704", source = "공시", category = "수주·공급계약",
        sentiment = "호재", strength = "상", preReflected = false, url = url,
        judgedAt = "2026-07-04T15:00:00",
    )

    @Test
    fun `append 후 readAll 왕복`() = runBlocking {
        val dir = tempDir()
        val log = CatalystEventLog(dir.absolutePath)
        log.append(listOf(event("http://a"), event("http://b")))
        log.append(listOf(event("http://c", code = "000660")))

        val all = log.readAll()
        assertEquals(3, all.size)
        assertEquals(listOf("http://a", "http://b", "http://c"), all.map { it.url })
        assertEquals("000660", all[2].code)
        dir.deleteRecursively()
    }

    @Test
    fun `빈 목록 append는 파일을 만들지 않는다`() = runBlocking {
        val dir = tempDir()
        val log = CatalystEventLog(dir.absolutePath)
        log.append(emptyList())
        assertTrue(log.readAll().isEmpty())
        assertTrue(!File(dir, "catalyst_events.jsonl").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `손상 줄은 건너뛰고 나머지는 읽는다`() = runBlocking {
        val dir = tempDir()
        val log = CatalystEventLog(dir.absolutePath)
        log.append(listOf(event("http://a")))
        File(dir, "catalyst_events.jsonl").appendText("{{{corrupted\n\n")
        log.append(listOf(event("http://b")))

        val all = log.readAll()
        assertEquals(2, all.size)
        assertEquals(listOf("http://a", "http://b"), all.map { it.url })
        dir.deleteRecursively()
    }

    @Test
    fun `파일 없으면 readAll은 빈 목록`() = runBlocking {
        val dir = tempDir()
        val log = CatalystEventLog(dir.absolutePath)
        assertTrue(log.readAll().isEmpty())
        dir.deleteRecursively()
    }
}
