package com.haky.edge

import com.haky.edge.lab.ControlUniverseService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R3 대조 유니버스 — 보통주 필터·고정 시드 표본의 순수 함수 검증. */
class ControlUniverseTest {

    @Test
    fun `보통주 판별 - 끝자리 0 보통주만 통과`() {
        assertTrue(ControlUniverseService.isCommonStock("005930", "삼성전자"))
        assertTrue(ControlUniverseService.isCommonStock("329180", "HD현대중공업"))
        // 우선주(끝자리 5) 제외
        assertFalse(ControlUniverseService.isCommonStock("005935", "삼성전자우"))
        // 6자리 숫자 아님 제외
        assertFalse(ControlUniverseService.isCommonStock("00593K", "삼성전자우B"))
    }

    @Test
    fun `보통주 판별 - ETF 스팩 리츠 이름 패턴 제외`() {
        assertFalse(ControlUniverseService.isCommonStock("069500", "KODEX 200"))
        assertFalse(ControlUniverseService.isCommonStock("102110", "TIGER 200"))
        assertFalse(ControlUniverseService.isCommonStock("377190", "미래에셋맵스리츠"))
        assertFalse(ControlUniverseService.isCommonStock("380440", "대신밸런스제8호스팩"))
        assertFalse(ControlUniverseService.isCommonStock("088980", "맥쿼리인프라"))
        // "솔브레인" 같은 이름은 "SOL " 패턴(공백 포함)에 안 걸려야 함
        assertTrue(ControlUniverseService.isCommonStock("357780", "솔브레인"))
    }

    @Test
    fun `표본 추출 - 같은 입력이면 언제나 같은 30종목`() {
        val caps = (1..250).map { i ->
            Triple("%06d".format(i * 10), "종목$i", (1000L - i) * 1_000_000L)
        }
        val a = ControlUniverseService.sampleTop(caps, watch = emptyList())
        val b = ControlUniverseService.sampleTop(caps.shuffled(), watch = emptyList())
        assertEquals(a, b)   // 입력 순서와 무관(시총 정렬 후 시드 셔플)
        assertEquals(ControlUniverseService.SAMPLE_SIZE, a.size)
    }

    @Test
    fun `표본 추출 - 시총 상위 TOP_N 밖 종목은 표본에 못 든다`() {
        val caps = (1..250).map { i ->
            Triple("%06d".format(i * 10), "종목$i", (1000L - i) * 1_000_000L)
        }
        val topCodes = caps.sortedByDescending { it.third }.take(ControlUniverseService.TOP_N)
            .map { it.first }.toSet()
        val sample = ControlUniverseService.sampleTop(caps, watch = emptyList())
        assertTrue(sample.all { it in topCodes })
    }

    @Test
    fun `표본 추출 - 관심종목은 제외된다`() {
        val caps = (1..250).map { i ->
            Triple("%06d".format(i * 10), "종목$i", (1000L - i) * 1_000_000L)
        }
        val noWatch = ControlUniverseService.sampleTop(caps, watch = emptyList())
        // 첫 표본에서 뽑힌 종목을 관심종목으로 지정하면 다음 표본에서 빠져야 한다.
        val watch = noWatch.take(3)
        val excluded = ControlUniverseService.sampleTop(caps, watch = watch)
        assertTrue(watch.none { it in excluded })
        assertEquals(ControlUniverseService.SAMPLE_SIZE, excluded.size)
    }
}
