package com.haky.edge.macro

import com.haky.edge.ai.ClaudeClient
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class MarketEvent(
    val date: String,               // YYYY-MM-DD
    val title: String,
    val category: String,           // "고비" | "온기"
    val impact: String,             // 한 줄 영향 설명
    val source: String? = null,     // 출처 URL
    val confirmed: Boolean = false, // 룰 계산=true, 웹검색=false
)

@Serializable
private data class EventStore(
    val events: List<MarketEvent>,
    val syncedAt: String,
)

@Serializable
data class EventSyncResult(
    val total: Int,
    val fromSearch: Int,
    val fromRules: Int,
    val syncedAt: String,
)

class EventSyncService(private val claude: ClaudeClient) {
    private val storeFile = File("${System.getenv("CACHE_DIR") ?: ".cache"}/events/events.json")
        .also { it.parentFile.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun getUpcoming(days: Int): List<MarketEvent> {
        if (!storeFile.exists()) return emptyList()
        val store = runCatching {
            json.decodeFromString(EventStore.serializer(), storeFile.readText())
        }.getOrNull() ?: return emptyList()

        val today = LocalDate.now()
        val until = today.plusDays(days.toLong())
        return store.events.filter { event ->
            runCatching {
                val d = LocalDate.parse(event.date)
                !d.isBefore(today) && !d.isAfter(until)
            }.getOrDefault(false)
        }.sortedBy { it.date }
    }

    suspend fun sync(): EventSyncResult {
        val today = LocalDate.now()
        val until = today.plusWeeks(6)

        val ruleEvents = buildRuleEvents(today, until)
        val searchEvents = runCatching { fetchFromSearch(today, until) }.onFailure { e ->
            System.err.println("[EventSync] fetchFromSearch failed: ${e.message}")
        }.getOrDefault(emptyList())
        val merged = mergeEvents(ruleEvents, searchEvents)
        val syncedAt = LocalDateTime.now().toString()

        storeFile.writeText(json.encodeToString(EventStore.serializer(), EventStore(merged, syncedAt)))
        return EventSyncResult(total = merged.size, fromSearch = searchEvents.size, fromRules = ruleEvents.size, syncedAt = syncedAt)
    }

    // 계산 가능한 정기 이벤트: 동시만기일(선물옵션 3/6/9/12월 둘째 목요일)
    private fun buildRuleEvents(from: LocalDate, until: LocalDate): List<MarketEvent> {
        val events = mutableListOf<MarketEvent>()
        for (year in from.year..until.year) {
            for (month in listOf(3, 6, 9, 12)) {
                val d = secondThursdayOf(year, month)
                if (!d.isBefore(from) && !d.isAfter(until)) {
                    events.add(MarketEvent(
                        date = d.toString(),
                        title = "선물옵션 동시만기일",
                        category = "주의",
                        impact = "선물·옵션 만기일이라 기관들이 포지션을 청산하면서 장 후반에 주가가 크게 흔들릴 수 있어요.",
                        confirmed = true,
                    ))
                }
            }
        }
        return events
    }

    private fun secondThursdayOf(year: Int, month: Int): LocalDate {
        var d = LocalDate.of(year, month, 1)
        while (d.dayOfWeek != DayOfWeek.THURSDAY) d = d.plusDays(1)
        return d.plusWeeks(1) // 두 번째 목요일
    }

    private suspend fun fetchFromSearch(from: LocalDate, until: LocalDate): List<MarketEvent> {
        // 1단계: 웹검색으로 이벤트 정보를 자유형식 텍스트로 수집
        // (JSON 출력을 동시에 요구하면 tool_use 중간에 코드블록을 열고 비워두는 문제 발생)
        // max_uses=1, maxTokens=400 으로 최소 토큰 사용 (30k/min rate limit 대응)
        // 출력을 "날짜: 이벤트" 형식 짧은 목록으로만 요청해 Stage 2 입력 토큰 절감
        val gathered = claude.completeWithWebSearch(
            systemPrompt = "웹 검색으로 이벤트 일정을 찾아 '날짜: 이벤트명(호재 또는 주의 또는 중립)' 형식 목록만 반환하세요. 불필요한 설명 없이 목록만.",
            userFacts = "$from ~ $until 한국·미국 증시 주요 이벤트(CPI·PPI·FOMC·한은·일은·MSCI·주요실적·휴장일) 날짜 목록:",
            maxTokens = 400,
            maxSearchUses = 1,
        )
        if (gathered.text.isBlank()) return emptyList()

        // 2단계: 짧은 목록(~400자)에서 JSON 구조 추출 (토큰 최소)
        val jsonText = claude.complete(
            systemPrompt = """아래 목록에서 날짜가 명확한 이벤트만 JSON 배열로 반환하세요.
category 규칙:
- "호재": 결과가 좋으면 주가에 긍정적인 이벤트 (물가 둔화, 고용 개선 등)
- "주의": 결과에 따라 주가가 크게 흔들릴 수 있는 이벤트 (금리 결정, 무역협상 등)
- "중립": 방향과 무관한 이벤트 (휴장일, 지수 정기변경 등)
impact 규칙: 주식 투자에 익숙하지 않은 일반인도 이해할 수 있도록 구어체로 작성.
'~하면 주가가 오를 수 있어요 / 내릴 수 있어요' 형식으로 조건부 시나리오를 포함해 2문장 이내.
형식: [{"date":"YYYY-MM-DD","title":"이벤트명","category":"호재 또는 주의 또는 중립","impact":"설명","source":null}]
JSON만, 다른 텍스트 없음.""",
            userFacts = gathered.text.take(1500),
            maxTokens = 1500,
        )
        return parseEventsJson(jsonText)
    }

    private fun parseEventsJson(text: String): List<MarketEvent> {
        val jsonText = extractJsonArray(text) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(MarketEvent.serializer()), jsonText)
        }.onFailure { e ->
            System.err.println("[EventSync] JSON 파싱 실패: ${e.message}")
        }.getOrDefault(emptyList())
    }

    private fun extractJsonArray(text: String): String? {
        // ```json ... ``` 코드 블록 우선
        val codeBlock = Regex("```(?:json)?\\s*(\\[.*?])\\s*```", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(text)?.groupValues?.getOrNull(1)
        if (!codeBlock.isNullOrBlank()) return codeBlock
        // 대괄호로 둘러싼 JSON 배열 직접 추출
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        return null
    }

    private fun mergeEvents(ruleEvents: List<MarketEvent>, searchEvents: List<MarketEvent>): List<MarketEvent> {
        val map = linkedMapOf<String, MarketEvent>()
        // 룰 이벤트 먼저 삽입 (confirmed=true, 신뢰도 높음)
        ruleEvents.forEach { map["${it.date}|${it.title}"] = it }
        // 검색 이벤트: 동일 키면 룰 이벤트 우선, 새 이벤트만 추가
        searchEvents.forEach { e -> map.putIfAbsent("${e.date}|${e.title}", e) }
        return map.values.sortedBy { it.date }
    }
}
