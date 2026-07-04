package com.haky.edge.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 재료 이벤트 1건 — 판정 시점에 append되는 시계열 정본.
 * verdictStore(code|url)에는 날짜가 없어 시계열 조인이 불가하므로(F2 전제),
 * 판정이 "처음" 이뤄질 때 재료 날짜와 함께 여기 남긴다.
 */
@Serializable
data class CatalystEvent(
    val code: String,
    val date: String,          // 재료 날짜 — 공시=YYYYMMDD, 뉴스=발행 표기(정규화는 통계 단계에서)
    val source: String,        // "공시" | "뉴스"
    val category: String,
    val sentiment: String,     // "호재" | "악재" | "중립" (백필분은 룰 추정)
    val strength: String,      // "상" | "중" | "하" | "미상"(백필)
    val preReflected: Boolean,
    val url: String,
    val judgedAt: String = "", // 기록 시각(ISO, KST) — 백필/실시간 구분·디버그용
)

/**
 * append-only jsonl 이벤트 로그({DATA_DIR}/catalyst_events.jsonl).
 * 이벤트는 수정할 일이 없으므로 PersistentMap(전체 재직렬화) 대신 줄 단위 append.
 * 손상 줄은 읽기에서 건너뛴다(로그 전체가 죽지 않게).
 */
class CatalystEventLog(dataDir: String = System.getenv("DATA_DIR") ?: ".data") {
    private val file = File(dataDir, "catalyst_events.jsonl").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun append(events: List<CatalystEvent>) {
        if (events.isEmpty()) return
        val lines = buildString {
            events.forEach { appendLine(json.encodeToString(CatalystEvent.serializer(), it)) }
        }
        mutex.withLock { runCatching { file.appendText(lines) } }
    }

    suspend fun readAll(): List<CatalystEvent> = mutex.withLock {
        if (!file.exists()) return@withLock emptyList()
        file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(CatalystEvent.serializer(), line) }.getOrNull()
        }
    }
}
