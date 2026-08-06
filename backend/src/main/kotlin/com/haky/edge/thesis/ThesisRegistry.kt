package com.haky.edge.thesis

import com.haky.edge.ai.ThesisSnapshot
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 서버가 보관하는 종목 1건의 논지(+변천 이력). 앱 로컬 DB가 정본이고 이건 사본. */
@Serializable
data class SyncedThesis(
    val text: String,
    val history: List<ThesisSnapshot> = emptyList(),
    val updatedAt: String,   // 앱이 마지막으로 이 논지를 sync한 시각(ISO-8601)
)

/**
 * 앱(기기)이 올려준 투자 논지를 기기별로 보관하는 저장소 — [[WatchlistRegistry]]의 논지 판.
 *
 * 논지는 원래 클라 로컬 DB가 정본이고 `/analysis` 호출 시에만 서버로 넘어왔다(요청 중 일시적).
 * signals-scan(서버 스케줄)이 논지 파손을 push하려면 서버가 논지를 상시 알아야 하므로,
 * 앱이 `POST /thesis/sync`로 현재 논지를 올린다(watchlist sync와 같은 트리거).
 *
 * 저장: {DATA_DIR}/thesis_registry.json = { deviceId: {theses{code→SyncedThesis}, updatedAt} }
 * 만료: expiryDays(기본 30일) 넘게 sync 없는 기기는 조회에서 제외(유령 논지 방지).
 * 프라이버시: 논지 자유텍스트가 서버(GCS)에 영속된다 — 1인·본인 백엔드 전제(2026-08 사용자 승인).
 */
class ThesisRegistry(
    dataDir: String = System.getenv("DATA_DIR") ?: ".data",
    private val expiryDays: Long = 30,
) {
    @Serializable
    data class DeviceEntry(val theses: Map<String, SyncedThesis>, val updatedAt: String)

    private val file = File(dataDir, "thesis_registry.json").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = MapSerializer(String.serializer(), DeviceEntry.serializer())

    /** 기기의 현재 논지 전체를 교체(upsert, 마지막 sync 시각 갱신). 빈 텍스트는 제외. */
    @Synchronized
    fun sync(deviceId: String, theses: Map<String, SyncedThesis>) {
        if (deviceId.isBlank()) return
        val clean = theses.filterValues { it.text.isNotBlank() }
        val map = load().toMutableMap()
        map[deviceId] = DeviceEntry(clean, Instant.now().toString())
        save(map)
    }

    /**
     * 활성(만료 전) 기기들 중 이 종목의 논지를 가진 것 중 가장 최근에 갱신된 것.
     * 여러 기기가 같은 종목에 다른 논지를 가지면 최신(updatedAt) 채택 — 1인 다기기 전제.
     */
    @Synchronized
    fun activeThesis(code: String): SyncedThesis? {
        val cutoff = Instant.now().minus(expiryDays, ChronoUnit.DAYS)
        return load().values
            .filter { runCatching { Instant.parse(it.updatedAt).isAfter(cutoff) }.getOrDefault(false) }
            .mapNotNull { it.theses[code] }
            .maxByOrNull { it.updatedAt }
    }

    private fun load(): Map<String, DeviceEntry> =
        if (file.exists())
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
        else emptyMap()

    private fun save(map: Map<String, DeviceEntry>) {
        runCatching { file.writeText(json.encodeToString(serializer, map)) }
    }
}
