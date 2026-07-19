package com.haky.edge

import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.InvestorHistoryLog
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 수급 아카이브(재평가 ④) — (code,date) 디듀프 append-only jsonl. */
class InvestorHistoryLogTest {

    private fun tempDir(): File = File.createTempFile("investor-history", "").let {
        it.delete(); it.mkdirs(); it
    }

    private fun flow(date: String, foreign: Long = 100, institution: Long = -50) =
        InvestorFlow(date = date, foreign = foreign, institution = institution, individual = -50)

    @Test
    fun `append 후 readAll 왕복`(): Unit = runBlocking {
        val dir = tempDir()
        val log = InvestorHistoryLog(dir.absolutePath)
        assertEquals(2, log.appendNew("005930", listOf(flow("20260716"), flow("20260717"))))
        assertEquals(1, log.appendNew("000660", listOf(flow("20260717"))))

        val all = log.readAll()
        assertEquals(3, all.size)
        assertEquals(listOf("005930", "005930", "000660"), all.map { it.code })
        assertEquals(100, all[0].foreign)
        assertEquals(-50, all[0].institution)
        dir.deleteRecursively()
    }

    @Test
    fun `같은 (code,date)는 다시 append되지 않는다 - 매일 10일치 재조회 디듀프`(): Unit = runBlocking {
        val dir = tempDir()
        val log = InvestorHistoryLog(dir.absolutePath)
        assertEquals(2, log.appendNew("005930", listOf(flow("20260716"), flow("20260717"))))
        // 다음날 스캔: 겹치는 2일 + 새 1일 → 새 1건만 기록.
        assertEquals(1, log.appendNew("005930", listOf(flow("20260716"), flow("20260717"), flow("20260718"))))
        // 같은 날짜라도 종목이 다르면 별건.
        assertEquals(1, log.appendNew("000660", listOf(flow("20260718"))))
        assertEquals(4, log.readAll().size)
        dir.deleteRecursively()
    }

    @Test
    fun `재시작 후에도 디듀프 유지 - 키 셋을 파일에서 재로드`(): Unit = runBlocking {
        val dir = tempDir()
        InvestorHistoryLog(dir.absolutePath).appendNew("005930", listOf(flow("20260717")))
        val reopened = InvestorHistoryLog(dir.absolutePath)
        assertEquals(0, reopened.appendNew("005930", listOf(flow("20260717"))))
        assertEquals(1, reopened.readAll().size)
        dir.deleteRecursively()
    }

    @Test
    fun `손상 줄은 건너뛰고 나머지는 읽는다`(): Unit = runBlocking {
        val dir = tempDir()
        val log = InvestorHistoryLog(dir.absolutePath)
        log.appendNew("005930", listOf(flow("20260716")))
        File(dir, "investor_history.jsonl").appendText("{{{corrupted\n\n")
        log.appendNew("005930", listOf(flow("20260717")))

        val all = log.readAll()
        assertEquals(2, all.size)
        assertEquals(listOf("20260716", "20260717"), all.map { it.date })
        dir.deleteRecursively()
    }

    @Test
    fun `빈 목록 append는 파일을 만들지 않는다`(): Unit = runBlocking {
        val dir = tempDir()
        val log = InvestorHistoryLog(dir.absolutePath)
        assertEquals(0, log.appendNew("005930", emptyList()))
        assertTrue(!File(dir, "investor_history.jsonl").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `stats - 행수 종목수 기간`(): Unit = runBlocking {
        val dir = tempDir()
        val log = InvestorHistoryLog(dir.absolutePath)
        log.appendNew("005930", listOf(flow("20260716"), flow("20260717")))
        log.appendNew("000660", listOf(flow("20260718")))

        val s = log.stats()
        assertEquals(3, s.rows)
        assertEquals(2, s.codes)
        assertEquals("20260716", s.firstDate)
        assertEquals("20260718", s.lastDate)
        dir.deleteRecursively()
    }
}
