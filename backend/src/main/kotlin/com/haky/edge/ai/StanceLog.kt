package com.haky.edge.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 종목 코멘트 스탠스 1건 — 생성 시점에 append(캐시 적중 시엔 기록 안 함).
 * 20거래일 뒤 실제 수익률과 대조해 앱 AI의 편향을 정량 측정한다(F6).
 * 주의: 기존 "AI 적중률"(시장 방향 예측 채점, MarketMoodLog)과는 별도 지표.
 */
@Serializable
data class StanceEntry(
    val code: String,
    val date: String,          // 생성 기준일(effectiveMarketDate, YYYY-MM-DD)
    val mode: String,          // "defensive" | "aggressive"
    val stance: String,        // "긍정" | "중립" | "부정" | "미상"(태그 파싱 실패 — 채점 제외)
    val priceAtGen: Double,    // 생성 시점 주가 — 20거래일 후 수익률 채점 기준
    val generatedAt: String = "", // HH:mm(KST)
)

/** append-only jsonl({DATA_DIR}/stance_log.jsonl). 손상 줄은 읽기에서 건너뛴다. */
class StanceLog(dataDir: String = System.getenv("DATA_DIR") ?: ".data") {
    private val file = File(dataDir, "stance_log.jsonl").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun append(entry: StanceEntry) {
        mutex.withLock {
            runCatching { file.appendText(json.encodeToString(StanceEntry.serializer(), entry) + "\n") }
        }
    }

    suspend fun readAll(): List<StanceEntry> = mutex.withLock {
        if (!file.exists()) return@withLock emptyList()
        file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(StanceEntry.serializer(), line) }.getOrNull()
        }
    }
}
