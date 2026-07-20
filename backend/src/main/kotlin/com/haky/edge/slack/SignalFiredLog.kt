package com.haky.edge.slack

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 신호 발화 1건 — signals-scan(18:00)이 매일 append하는 시계열 정본. */
@Serializable
data class SignalFired(
    val date: String,    // YYYY-MM-DD (KST 당일)
    val code: String,    // 종목코드 (REBALANCE는 빈 문자열)
    val kind: String,    // FLOW|DISCLOSURE|VALUATION|REVERSAL|EARNINGS_REVIEW|PREMORTEM|REBALANCE
    val detail: String,  // 발화 설명 문자열
    val firedAt: String = "", // 기록 시각(ISO, KST) — 디버그용
)

/**
 * append-only 신호 발화 로그({DATA_DIR}/signal_fired.jsonl).
 * scan의 기존 state가 신호별 디듀프를 이미 보장하므로, 로그 자체는
 * 같은 날 재스캔에 의한 중복만 (date,code,kind,detail) 키로 방지한다.
 * 손상 줄은 읽기에서 건너뛴다.
 */
class SignalFiredLog(dataDir: String = System.getenv("DATA_DIR") ?: ".data") {
    private val file = File(dataDir, "signal_fired.jsonl").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var seenKeys: MutableSet<String>? = null

    private fun keyOf(e: SignalFired) = "${e.date}|${e.code}|${e.kind}|${e.detail}"

    /** 발화 목록을 (date,code,kind,detail) 중복 없이 append. 반환 = 새로 기록한 건수. */
    suspend fun appendNew(entries: List<SignalFired>): Int = mutex.withLock {
        if (entries.isEmpty()) return@withLock 0
        val keys = seenKeys ?: loadKeys().also { seenKeys = it }
        val fresh = entries.filter { keyOf(it) !in keys }
        if (fresh.isEmpty()) return@withLock 0
        val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val lines = buildString {
            fresh.forEach { appendLine(json.encodeToString(SignalFired.serializer(), it.copy(firedAt = now))) }
        }
        val ok = runCatching { file.appendText(lines) }.isSuccess
        if (ok) fresh.forEach { keys += keyOf(it) }
        if (ok) fresh.size else 0
    }

    /** 운영 적재 확인용 요약(GET /signal-fired/stats). */
    @Serializable
    data class Stats(
        val rows: Int,
        val kinds: Map<String, Int>,
        val firstDate: String? = null,
        val lastDate: String? = null,
    )

    suspend fun stats(): Stats = mutex.withLock {
        val all = readLines()
        Stats(
            rows = all.size,
            kinds = all.groupBy { it.kind }.mapValues { it.value.size },
            firstDate = all.minOfOrNull { it.date },
            lastDate = all.maxOfOrNull { it.date },
        )
    }

    private fun readLines(): List<SignalFired> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(SignalFired.serializer(), line) }.getOrNull()
        }
    }

    /** 특정 날짜·종목의 발화 목록(deltaLines 재료용). 뮤텍스 불필요 — 읽기 전용. */
    fun todayFor(code: String, date: String): List<SignalFired> {
        if (!file.exists()) return emptyList()
        return readLines().filter { it.date == date && it.code == code }
    }

    private fun loadKeys(): MutableSet<String> =
        readLines().mapTo(mutableSetOf()) { keyOf(it) }
}
