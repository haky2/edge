package com.haky.edge.usage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 카드 노출·펼침 사용량 1건 — 앱이 배치로 append하는 시계열.
 *
 * ⚠️ **단일 사용자 전제.** 이 로그는 "이 앱을 쓰는 한 사람"이 어떤 카드를 실제로 펼쳐 보는지
 * 30일간 측정해 K2~K4(비교/배당/Discovery 제거·강등) 결정 근거를 만들기 위한 것이다.
 * 여러 사용자 구분(userId)·개인정보 개념이 없다 — 다중 사용자로 확장 시 스키마 재설계 필요.
 */
@Serializable
data class UsageEvent(
    val screen: String,   // detail|briefing|stats|portfolio|comparison|ask|deep-research ...
    val card: String,     // 펼침(expand) 이벤트의 카드 id(표시 제목). 화면 진입(view)은 ""
    val action: String,   // view(화면·카드 노출) | expand(접이식 카드 펼침)
    val at: String,       // 클라 이벤트 시각(ISO local, KST) — 최근 사용일 계산·디듀프 키
)

/**
 * append-only 사용량 로그({DATA_DIR}/usage_events.jsonl). [com.haky.edge.slack.SignalFiredLog] 패턴 복제.
 * - 디듀프: (screen,card,action,at) 키 — 앱의 배치 flush 재전송(부분 실패 재시도)에 멱등.
 * - 보존: 로드 시 최근 [RETENTION_DAYS]일만 집계(무한 append 방지 — 파일 컴팩션은 안 함, 읽기 필터로 충분).
 * - 손상 줄은 읽기에서 건너뛴다.
 */
class UsageEventLog(dataDir: String = System.getenv("DATA_DIR") ?: ".data") {
    private val file = File(dataDir, "usage_events.jsonl").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val seoulZone = ZoneId.of("Asia/Seoul")

    private fun keyOf(e: UsageEvent) = "${e.screen}|${e.card}|${e.action}|${e.at}"

    /** 배치를 (screen,card,action,at) 중복 없이 append. 반환 = 새로 기록한 건수. */
    suspend fun appendBatch(events: List<UsageEvent>): Int = mutex.withLock {
        if (events.isEmpty()) return@withLock 0
        val seen = loadRecent().mapTo(mutableSetOf()) { keyOf(it) }
        // 배치 내부 중복도 제거(같은 이벤트가 큐에 두 번 들어간 경우).
        val fresh = LinkedHashMap<String, UsageEvent>()
        for (e in events) {
            if (!e.action.isValid()) continue
            val k = keyOf(e)
            if (k !in seen && k !in fresh) fresh[k] = e
        }
        if (fresh.isEmpty()) return@withLock 0
        val lines = buildString { fresh.values.forEach { appendLine(json.encodeToString(UsageEvent.serializer(), it)) } }
        val ok = runCatching { file.appendText(lines) }.isSuccess
        if (ok) fresh.size else 0
    }

    /** GET /usage-stats — 카드별 노출·펼침 수·최근 사용일(최근 [RETENTION_DAYS]일 창). */
    suspend fun stats(): UsageStats = mutex.withLock {
        val all = loadRecent()
        val cards = all.groupBy { Triple(it.screen, it.card, it.action) }
            .map { (k, v) ->
                CardUsage(
                    screen = k.first, card = k.second, action = k.third,
                    count = v.size,
                    lastUsed = v.maxOf { it.at }.take(10),
                )
            }
            .sortedWith(compareBy({ it.screen }, { it.action }, { -it.count }))
        UsageStats(
            totalEvents = all.size,
            windowDays = RETENTION_DAYS,
            cards = cards,
            firstDate = all.minOfOrNull { it.at }?.take(10),
            lastDate = all.maxOfOrNull { it.at }?.take(10),
        )
    }

    /** 파일 전체를 읽고 최근 [RETENTION_DAYS]일만 남긴다. 날짜 파싱 실패 줄은 보존(오분류 방지). */
    private fun loadRecent(): List<UsageEvent> {
        if (!file.exists()) return emptyList()
        val cutoff = LocalDate.now(seoulZone).minusDays(RETENTION_DAYS.toLong())
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val e = runCatching { json.decodeFromString(UsageEvent.serializer(), line) }.getOrNull()
                ?: return@mapNotNull null
            val d = runCatching { LocalDate.parse(e.at.take(10)) }.getOrNull() ?: return@mapNotNull e
            if (d.isBefore(cutoff)) null else e
        }
    }

    private fun String.isValid() = this == "view" || this == "expand"

    companion object {
        const val RETENTION_DAYS = 90
    }
}

@Serializable
data class UsageStats(
    val totalEvents: Int,
    val windowDays: Int,
    val cards: List<CardUsage>,
    val firstDate: String? = null,
    val lastDate: String? = null,
)

@Serializable
data class CardUsage(
    val screen: String,
    val card: String,     // "" = 화면 진입(view)
    val action: String,   // view | expand
    val count: Int,
    val lastUsed: String, // YYYY-MM-DD
)
