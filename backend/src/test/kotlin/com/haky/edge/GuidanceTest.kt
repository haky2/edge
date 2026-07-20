package com.haky.edge

import com.haky.edge.ai.Guidance
import com.haky.edge.ai.GuidanceItem
import com.haky.edge.ai.GuidanceService
import com.haky.edge.ai.ModelRouter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N2 가이던스 — 파싱·URL 후검증·일일 예산·(code,rceptNo) 캐시.
 * 캐시 테스트는 빈 API 키 ClaudeClient로 구성 — LLM이 호출되면 예외가 나므로,
 * 예외 없이 저장본이 반환되는 것 자체가 "캐시 적중 시 재검색 없음"의 증명이다.
 */
class GuidanceTest {

    // ── parseItems ────────────────────────────────────────────────────────

    @Test
    fun `정상 JSON 배열 파싱`(): Unit {
        val raw = """[{"topic":"하반기 수주 목표","statement":"연간 수주 20조 원 목표 유지","sourceUrl":"https://a.com/1","saidAt":"7/15 컨콜"}]"""
        val items = GuidanceService.parseItems(raw)
        assertNotNull(items)
        assertEquals(1, items.size)
        assertEquals("하반기 수주 목표", items[0].topic)
        assertEquals("7/15 컨콜", items[0].saidAt)
    }

    @Test
    fun `코드펜스·서두 텍스트 방어`(): Unit {
        val raw = "다음은 결과입니다.\n```json\n[{\"topic\":\"CAPEX\",\"statement\":\"내년 1조 투자\"}]\n```"
        val items = GuidanceService.parseItems(raw)
        assertNotNull(items)
        assertEquals(1, items.size)
        assertEquals("", items[0].sourceUrl)   // 누락 필드는 기본값
    }

    @Test
    fun `빈 배열은 빈 리스트, 비JSON은 null`(): Unit {
        assertEquals(emptyList<GuidanceItem>(), GuidanceService.parseItems("[]"))
        assertNull(GuidanceService.parseItems("가이던스를 찾지 못했습니다."))
        assertNull(GuidanceService.parseItems("{\"topic\":\"객체는 배열이 아님\"}"))
    }

    @Test
    fun `topic·statement 없는 항목은 버린다`(): Unit {
        val raw = """[{"topic":"유효","statement":"수주 5조"},{"topic":"statement 없음"},{"statement":"topic 없음"}]"""
        val items = GuidanceService.parseItems(raw)
        assertNotNull(items)
        assertEquals(1, items.size)
        assertEquals("유효", items[0].topic)
    }

    // ── guardSourceUrls ───────────────────────────────────────────────────

    @Test
    fun `검색 출처에 없는 URL은 빈 문자열로`(): Unit {
        val items = listOf(
            GuidanceItem("a", "s1", sourceUrl = "https://real.com/x"),
            GuidanceItem("b", "s2", sourceUrl = "https://fabricated.com/y"),
            GuidanceItem("c", "s3", sourceUrl = ""),
        )
        val guarded = GuidanceService.guardSourceUrls(items, listOf("https://real.com/x"))
        assertEquals("https://real.com/x", guarded[0].sourceUrl)
        assertEquals("", guarded[1].sourceUrl)   // 모델이 지어낸 URL 차단
        assertEquals("", guarded[2].sourceUrl)
    }

    // ── DailyBudget ───────────────────────────────────────────────────────

    @Test
    fun `일일 예산 - 한도까지 true, 초과 false, 날짜 바뀌면 리셋`(): Unit {
        val b = GuidanceService.DailyBudget(2)
        assertTrue(b.tryTick("2026-07-20"))
        assertTrue(b.tryTick("2026-07-20"))
        assertFalse(b.tryTick("2026-07-20"))
        assertTrue(b.tryTick("2026-07-21"))   // 날짜 회전 → 리셋
    }

    // ── renderStage2Input ─────────────────────────────────────────────────

    @Test
    fun `2단계 입력 - 노트와 출처 URL 목록 병기`(): Unit {
        val input = GuidanceService.renderStage2Input(
            "- [전자신문, 7/15] 하반기 수주 목표 유지",
            listOf("전자신문" to "https://a.com/1", "중복" to "https://a.com/1"),
        )
        assertTrue("[리서치 노트]" in input)
        assertTrue("[출처 URL 목록" in input)
        assertEquals(1, Regex("https://a\\.com/1").findAll(input).count())  // URL 중복 제거
    }

    // ── (code, rceptNo) 캐시 — 저장본 로드·재검색 없음 ─────────────────────

    @Test
    fun `같은 rceptNo 재수집은 저장본 반환 - LLM 미호출`(): Unit = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("guidance-test").toFile()
        try {
            // 저장 파일 선기록(수집 완료 상태 재현).
            val stored = Guidance(
                code = "005930", name = "삼성전자", periodLabel = "2026년 반기",
                rceptNo = "20260814000123",
                items = listOf(GuidanceItem("수주 목표", "연간 20조")),
                collectedAt = "2026-07-20",
            )
            val ser = MapSerializer(String.serializer(), Guidance.serializer())
            File(dir, "guidance.json").writeText(Json.encodeToString(ser, mapOf("005930" to stored)))

            // 빈 API 키 — LLM이 호출되면 ClaudeException. 캐시 적중이면 예외 없이 반환.
            val svc = GuidanceService(
                master = com.haky.edge.master.StockMaster(io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)),
                claude = com.haky.edge.ai.ClaudeClient(apiKey = ""),
                modelRouter = ModelRouter("s", "o", emptySet()),
                dataDir = dir.absolutePath,
            )
            val hit = svc.collectForReview("005930", "반기보고서 (2026.06)", "20260814000123", "2026년 반기")
            assertNotNull(hit)
            assertEquals("연간 20조", hit.items[0].statement)
            // latest()도 같은 저장본.
            assertEquals(hit, svc.latest("005930"))
            // 다른 종목 latest는 null.
            assertNull(svc.latest("000660"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
