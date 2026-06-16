package com.haky.edge.macro

import com.haky.edge.kis.MacroIndicator
import com.haky.edge.util.KST
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * 한국은행 ECOS Open API 클라이언트 — 국고채 3년 금리 조회.
 * https://ecos.bok.or.kr/api/ (인증키 무료 발급 필요)
 *
 * apiKey 가 비어있으면 get()은 null을 반환해 /macro 섹션이 이 지표 없이도 동작한다.
 * 일별 캐시: 채권 금리는 장 마감 후 1회 확정되므로 당일 첫 조회 후 자정까지 재사용.
 */
class EcosClient(private val apiKey: String) {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private data class Cached(val indicator: MacroIndicator, val expiryMs: Long)
    private val cached = AtomicReference<Cached?>(null)

    suspend fun get(): MacroIndicator? {
        if (apiKey.isBlank()) return null
        cached.get()?.takeIf { System.currentTimeMillis() < it.expiryMs }?.let { return it.indicator }
        return runCatching { fetch() }.getOrNull()?.also {
            // 자정까지 캐시 (일별 금리는 장 마감 후 갱신이라 당일 중 여러번 호출 불필요)
            val nowMs = System.currentTimeMillis()
            val midnightMs = java.time.LocalDateTime.now(KST)
                .toLocalDate().plusDays(1)
                .atStartOfDay()
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toInstant().toEpochMilli()
            cached.set(Cached(it, midnightMs.coerceAtLeast(nowMs + 30 * 60_000L)))
        }
    }

    private suspend fun fetch(): MacroIndicator {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
        val today = LocalDate.now(KST).format(fmt)
        // 14일 범위를 요청해 주말·공휴일 없는 날에도 최소 2개 영업일 데이터를 확보한다.
        val startDate = LocalDate.now(KST).minusDays(14).format(fmt)
        // 요청 건수(1~10)를 넉넉히 10으로 설정해 14일치가 다 들어오게 한다.
        val url = "https://ecos.bok.or.kr/api/StatisticSearch/$apiKey/json/kr/1/10/817Y002/D/$startDate/$today/010300000"

        val resp: EcosResponse = http.get(url).body()
        // 데이터가 없으면(주말·공휴일, 키 오류 등) 예외를 던져 runCatching 이 null로 처리하게 한다.
        val rows = resp.search?.rows
            ?.filter { it.value.isNotBlank() && it.value != "-" }
            ?.sortedBy { it.time }
            ?.takeIf { it.isNotEmpty() }
            ?: error("ECOS 데이터 없음")

        val current = rows.last().value.toDoubleOrNull() ?: error("ECOS 값 파싱 실패")
        val prev = rows.getOrNull(rows.size - 2)?.value?.toDoubleOrNull() ?: current
        val change = current - prev
        val changeRate = if (prev > 0.0) change / prev * 100.0 else 0.0

        return MacroIndicator(
            key = "rate3y",
            label = "국고채3년",
            value = current,
            change = change,
            changeRate = changeRate,
        )
    }
}

@Serializable
private data class EcosResponse(
    @SerialName("StatisticSearch") val search: EcosStatisticSearch? = null,
)

@Serializable
private data class EcosStatisticSearch(
    @SerialName("row") val rows: List<EcosRow> = emptyList(),
)

@Serializable
private data class EcosRow(
    @SerialName("TIME") val time: String = "",
    @SerialName("DATA_VALUE") val value: String = "",
)
