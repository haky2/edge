package com.haky.edge.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 주말 캐시 통합용 '실효 거래일'(YYYY-MM-DD). 일요일은 토요일로 접어 같은 캐시 키를 쓰게 한다
 * → 데이터가 동일한 주말(금요일 종가로 고정, 미국장도 휴장) 동안 일요일이 토요일 분석을 그대로
 * 재사용해 Claude 재호출을 아낀다. 토요일은 그대로(금요일과 구분돼 새로 생성), 평일도 당일 그대로.
 */
fun effectiveMarketDate(): String = effectiveMarketDate(LocalDate.now())

/** 테스트 가능하도록 날짜를 받는 오버로드. 일요일 → 전날(토요일), 그 외 → 그대로. */
internal fun effectiveMarketDate(d: LocalDate): String =
    if (d.dayOfWeek == DayOfWeek.SUNDAY) d.minusDays(1).toString() else d.toString()

/**
 * Claude 응답 파일 캐시. 백엔드 재시작 후에도 오늘치 캐시를 재사용해 불필요한 API 호출을 막는다.
 * 저장 위치: {CACHE_DIR}/{prefix}/{key}.json (key는 날짜 포함이라 날짜 바뀌면 자동 stale).
 * CACHE_DIR 환경변수로 GCS 볼륨 마운트 경로를 지정하면 콜드 스타트에도 캐시가 유지된다.
 */
class FileCache<T>(
    private val prefix: String,
    private val serializer: KSerializer<T>,
) {
    private val dir = File("${System.getenv("CACHE_DIR") ?: ".cache"}/$prefix").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val today = LocalDate.now().toString()

    fun get(key: String): T? {
        // 날짜 불일치 → stale. 단 일요일은 effectiveMarketDate가 토요일을 돌려주므로,
        // 토요일에 생성된 주말 캐시 키도 통과시켜 일요일이 그대로 재사용하게 한다.
        if (!key.contains(today) && !key.contains(effectiveMarketDate())) return null
        return runCatching {
            val file = fileFor(key)
            if (!file.exists()) return null
            json.decodeFromString(serializer, file.readText())
        }.getOrNull()
    }

    fun put(key: String, value: T) {
        runCatching {
            fileFor(key).writeText(json.encodeToString(serializer, value))
        }
    }

    private fun fileFor(key: String): File {
        // 파일명에 쓸 수 없는 문자 치환
        val safe = key.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return File(dir, "$safe.json")
    }
}
