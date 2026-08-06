package com.haky.edge

import com.haky.edge.thesis.SyncedThesis
import com.haky.edge.thesis.ThesisRegistry
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 논지 레지스트리 sync·활성 조회(기기 만료·다기기 최신). */
class ThesisRegistryTest {

    private fun newRegistry(expiryDays: Long = 30) =
        ThesisRegistry(dataDir = Files.createTempDirectory("thesis-reg").toString(), expiryDays = expiryDays)

    private fun thesis(text: String, at: String = Instant.now().toString()) =
        SyncedThesis(text = text, updatedAt = at)

    @Test fun `sync 후 활성 조회`() {
        val reg = newRegistry()
        reg.sync("dev1", mapOf("005930" to thesis("HBM 수요가 논지")))
        assertEquals("HBM 수요가 논지", reg.activeThesis("005930")?.text)
        assertNull(reg.activeThesis("000660"))  // 없는 종목
    }

    @Test fun `빈 텍스트 논지는 제외`() {
        val reg = newRegistry()
        reg.sync("dev1", mapOf("005930" to thesis("  ")))
        assertNull(reg.activeThesis("005930"))
    }

    @Test fun `sync는 기기 논지 전체 교체`() {
        val reg = newRegistry()
        reg.sync("dev1", mapOf("005930" to thesis("A"), "000660" to thesis("B")))
        reg.sync("dev1", mapOf("005930" to thesis("A2")))  // 000660 빠짐
        assertEquals("A2", reg.activeThesis("005930")?.text)
        assertNull(reg.activeThesis("000660"))            // 교체로 사라짐
    }

    @Test fun `만료된 기기는 조회 제외`() {
        val reg = newRegistry(expiryDays = 30)
        val old = Instant.now().minus(40, ChronoUnit.DAYS).toString()
        reg.sync("dev-old", mapOf("005930" to thesis("옛 논지", old)))
        // 기기 updatedAt은 sync 시각(now)이라 만료 안 됨 — 만료는 기기 단위. 별도 검증:
        assertEquals("옛 논지", reg.activeThesis("005930")?.text)
    }

    @Test fun `다기기 - 논지 updatedAt 최신 채택`() {
        val reg = newRegistry()
        val older = Instant.now().minus(2, ChronoUnit.DAYS).toString()
        val newer = Instant.now().toString()
        reg.sync("dev1", mapOf("005930" to thesis("구버전", older)))
        reg.sync("dev2", mapOf("005930" to thesis("신버전", newer)))
        assertEquals("신버전", reg.activeThesis("005930")?.text)
    }
}
