package com.haky.edge.kis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 일별 확정 수급 1건 — signals-scan(18:00)이 매일 append하는 시계열 정본.
 * KIS inquire-investor는 최근 N일 조회만 되고 과거 이력을 안 주므로(F4 백테스트 불가의 원인),
 * 매일 보고 버리던 확정값을 여기 영속한다. catalyst_events(2-0)와 동일 논리.
 */
@Serializable
data class InvestorDay(
    val code: String,
    val date: String,        // 확정 영업일 YYYYMMDD (미확정 당일은 getInvestorFlow가 이미 제외)
    val foreign: Long,       // 외국인 순매수 수량(부호 포함)
    val institution: Long,   // 기관계 순매수 수량
    val individual: Long,    // 개인 순매수 수량
    val recordedAt: String = "", // 기록 시각(ISO, KST) — 디버그용
)

/**
 * append-only jsonl 수급 아카이브({DATA_DIR}/investor_history.jsonl).
 * 스캔이 매일 최근 10일을 다시 받아오므로 (code,date) 중복 제거가 필수 —
 * 키 셋은 첫 append 때 파일에서 1회 로드 후 메모리 유지(mutex 보호). 손상 줄은 읽기에서 건너뛴다.
 */
class InvestorHistoryLog(dataDir: String = System.getenv("DATA_DIR") ?: ".data") {
    private val file = File(dataDir, "investor_history.jsonl").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var seenKeys: MutableSet<String>? = null

    private fun keyOf(code: String, date: String) = "$code|$date"

    /** 확정 일별 수급을 (code,date) 중복 없이 append. 반환 = 새로 기록한 건수. */
    suspend fun appendNew(code: String, flows: List<InvestorFlow>): Int = mutex.withLock {
        val keys = seenKeys ?: loadKeys().also { seenKeys = it }
        val fresh = flows.filter { keyOf(code, it.date) !in keys }
        if (fresh.isEmpty()) return@withLock 0
        val now = nowKstIso()
        val lines = buildString {
            fresh.forEach {
                appendLine(json.encodeToString(InvestorDay.serializer(), InvestorDay(code, it.date, it.foreign, it.institution, it.individual, now)))
            }
        }
        val ok = runCatching { file.appendText(lines) }.isSuccess
        if (ok) fresh.forEach { keys += keyOf(code, it.date) }
        if (ok) fresh.size else 0
    }

    suspend fun readAll(): List<InvestorDay> = mutex.withLock { readLines() }

    /** 운영 적재 확인용 요약(GET /investor-history/stats). */
    @Serializable
    data class Stats(val rows: Int, val codes: Int, val firstDate: String? = null, val lastDate: String? = null)

    suspend fun stats(): Stats = mutex.withLock {
        val all = readLines()
        Stats(
            rows = all.size,
            codes = all.distinctBy { it.code }.size,
            firstDate = all.minOfOrNull { it.date },
            lastDate = all.maxOfOrNull { it.date },
        )
    }

    private fun readLines(): List<InvestorDay> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(InvestorDay.serializer(), line) }.getOrNull()
        }
    }

    private fun loadKeys(): MutableSet<String> =
        readLines().mapTo(mutableSetOf()) { keyOf(it.code, it.date) }

    private fun nowKstIso(): String =
        java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}
