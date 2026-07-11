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
    val regime: String? = null,   // facts의 국면 판정 라벨(리레이팅/디레이팅, 없으면 null) — 레짐별 편향 집계용
    val summary: String? = null,  // 생성 시점 핵심 요약 — 다음 분석에서 "직전 판단 대비 무엇이 바뀌었나" 대조용(판단 변화 추적)
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

    /**
     * 판단 변화 추적: 같은 종목·같은 모드의 "beforeDate 이전" 마지막 유효 스탠스(미상 제외).
     * "어제"가 아니라 "지난 생성분"인 이유 — 캐시 적중일이나 안 연 날은 기록이 없으므로,
     * 비교의 올바른 기준은 마지막으로 실제 생성된 분석이다. 같은 날 재생성(force)은 비교
     * 대상에서 빠진다(엄격히 이전 날짜만) — 일중 재생성 간 비교는 노이즈라 의도적으로 제외.
     */
    suspend fun latestBefore(code: String, mode: String, beforeDate: String): StanceEntry? =
        readAll().lastOrNull { it.code == code && it.mode == mode && it.date < beforeDate && it.stance != "미상" }
}
