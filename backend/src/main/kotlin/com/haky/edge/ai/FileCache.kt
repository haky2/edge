package com.haky.edge.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

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
        if (!key.contains(today)) return null   // 날짜 불일치 → stale
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
