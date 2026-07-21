package com.haky.edge.ai

import com.haky.edge.util.KST
import com.haky.edge.util.writeTextAtomic
import java.io.File
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DailyUsage(
    val date: String,
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreatedTokens: Long = 0,
    val webSearches: Int = 0,
)

/**
 * Claude API 일별 토큰/요청 수를 {DATA_DIR}/claude_usage/YYYY-MM-DD.json에 누적한다.
 * max-instances=1이라 @Synchronized read-modify-write로 충분. GCS 마운트 파일이라 인스턴스 재시작 후에도 유지.
 */
class ClaudeUsageTracker(dataDir: String) {
    private val dir = File("$dataDir/claude_usage").also { it.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun readToday(): DailyUsage {
        val f = file()
        if (!f.exists()) return DailyUsage(LocalDate.now(KST).toString())
        return runCatching {
            json.decodeFromString(DailyUsage.serializer(), f.readText())
        }.getOrDefault(DailyUsage(LocalDate.now(KST).toString()))
    }

    @Synchronized
    fun record(inputTokens: Int, outputTokens: Int, cacheRead: Int, cacheCreated: Int) {
        val date = LocalDate.now(KST).toString()
        val f = file(date)
        val current = if (f.exists()) {
            runCatching { json.decodeFromString(DailyUsage.serializer(), f.readText()) }
                .getOrDefault(DailyUsage(date))
        } else DailyUsage(date)
        val updated = current.copy(
            requests = current.requests + 1,
            inputTokens = current.inputTokens + inputTokens,
            outputTokens = current.outputTokens + outputTokens,
            cacheReadTokens = current.cacheReadTokens + cacheRead,
            cacheCreatedTokens = current.cacheCreatedTokens + cacheCreated,
        )
        f.writeTextAtomic(json.encodeToString(DailyUsage.serializer(), updated))
    }

    @Synchronized
    fun recordWebSearch() {
        val date = LocalDate.now(KST).toString()
        val f = file(date)
        val current = if (f.exists()) {
            runCatching { json.decodeFromString(DailyUsage.serializer(), f.readText()) }
                .getOrDefault(DailyUsage(date))
        } else DailyUsage(date)
        f.writeTextAtomic(json.encodeToString(DailyUsage.serializer(), current.copy(webSearches = current.webSearches + 1)))
    }

    private fun file(date: String = LocalDate.now(KST).toString()) = File(dir, "$date.json")
}
