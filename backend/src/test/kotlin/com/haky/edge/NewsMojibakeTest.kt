package com.haky.edge

import com.haky.edge.news.fixMojibake
import com.haky.edge.news.stripHtml
import kotlin.test.Test
import kotlin.test.assertEquals

class NewsMojibakeTest {

    /** UTF-8 바이트를 Latin-1로 잘못 디코딩한 깨진 문자열(네이버 일부 제목에서 관측된 형태)을 시뮬레이션. */
    private fun mojibake(s: String): String =
        String(s.toByteArray(Charsets.UTF_8).map { (it.toInt() and 0xFF).toChar() }.toCharArray())

    @Test fun `순수 한글 mojibake 복원`() {
        assertEquals("당신에게 드리는 글", mojibake("당신에게 드리는 글").fixMojibake())
    }

    @Test fun `숫자+한글 mojibake 복원`() {
        assertEquals("4,50대인 당신께", mojibake("4,50대인 당신께").fixMojibake())
    }

    @Test fun `정상 한글은 그대로 — 가운뎃점 포함`() {
        val s = "삼성전자·에스케이하이닉스, 외국인 매수세에 강세"
        assertEquals(s, s.fixMojibake())
    }

    @Test fun `유럽어(라틴 확장) 제목은 손대지 않음`() {
        val s = "café résumé naïve Über"
        assertEquals(s, s.fixMojibake())
    }

    @Test fun `정상 한글 제목 그대로`() {
        val s = "[마감] 코스피, 미·이란 종전 합의에 8700선 돌파"
        assertEquals(s, s.fixMojibake())
    }

    @Test fun `mojibake와 정상 한글 혼재 시 안전하게 미수정(보수적)`() {
        // 진짜 한글(강세, >U+00FF)이 섞이면 byte 재해석이 그걸 깨뜨리므로 손대지 않는다.
        val mixed = mojibake("반도체") + " 강세"
        assertEquals(mixed, mixed.fixMojibake())
    }

    @Test fun `stripHtml 태그 제거 후 순수 mojibake 복원`() {
        val input = "<b>" + mojibake("반도체주") + "</b>"
        assertEquals("반도체주", input.stripHtml())
    }
}
