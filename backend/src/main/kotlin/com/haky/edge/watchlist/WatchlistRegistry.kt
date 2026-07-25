package com.haky.edge.watchlist

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 앱(기기)이 올려준 관심종목을 기기별로 보관하는 저장소.
 *
 * 슬랙 신호·주간회고는 스케줄로 서버에서 도는데 사용자 컨텍스트가 없어서, 예전엔 하드코딩
 * 목록(SIGNAL_CODES 폴백 11종목)을 스캔했다. 이제 앱이 `POST /watchlist/sync`로 현재
 * 관심종목을 올려주면 그걸 스캔한다.
 *
 * 공유 슬랙 채널이므로 스캔 대상 = **등록된 기기들의 합집합**(내 것 + 지인 것 다 커버).
 * 사용자 수가 한두 명인 현 규모에 맞춘 설계 — 계정/로그인 없이 기기 UUID만 쓴다.
 * 규모가 커지면 per-user DM 분리가 다음 방향.
 *
 * 저장: {DATA_DIR}/watchlist_registry.json = { deviceId: {codes, updatedAt(ISO-8601)} }
 * 만료: expiryDays(기본 30일) 넘게 sync 없는 기기는 합집합에서 자동 제외(유령 목록 방지).
 * 폴백: 등록된 활성 기기가 하나도 없으면 fallback(기본 삼성전자 1종목) — 배포 직후~첫 sync 공백기 방지.
 */
class WatchlistRegistry(
    dataDir: String = System.getenv("DATA_DIR") ?: ".data",
    private val fallback: List<String> = listOf("005930"),
    private val expiryDays: Long = 30,
) {
    @Serializable
    data class DeviceEntry(val codes: List<String>, val updatedAt: String)

    private val file = File(dataDir, "watchlist_registry.json").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = MapSerializer(String.serializer(), DeviceEntry.serializer())

    /** 기기의 현재 관심종목을 upsert(마지막 sync 시각 갱신). */
    @Synchronized
    fun sync(deviceId: String, codes: List<String>) {
        if (deviceId.isBlank()) return
        val clean = codes.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val map = load().toMutableMap()
        map[deviceId] = DeviceEntry(clean, Instant.now().toString())
        save(map)
    }

    /** 만료되지 않은 기기들의 관심종목 합집합. 비어 있으면 fallback. */
    @Synchronized
    fun activeCodes(): List<String> {
        val cutoff = Instant.now().minus(expiryDays, ChronoUnit.DAYS)
        val union = load().values
            .filter { runCatching { Instant.parse(it.updatedAt).isAfter(cutoff) }.getOrDefault(false) }
            .flatMap { it.codes }
            .distinct()
        return union.ifEmpty { fallback }
    }

    private fun load(): Map<String, DeviceEntry> =
        if (file.exists())
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
        else emptyMap()

    private fun save(map: Map<String, DeviceEntry>) {
        runCatching { file.writeText(json.encodeToString(serializer, map)) }
    }
}
