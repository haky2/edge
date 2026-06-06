package com.haky.edge.macro

import com.haky.edge.ai.ClaudeClient
import com.haky.edge.ai.FileCache
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.MacroIndicator
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Serializable
data class MarketMood(
    val date: String,
    val comment: String,               // Claude 시장 전체 분위기 코멘트
    val indicators: List<MacroIndicator>, // 코멘트 생성에 쓴 지표 전체
    val generatedAt: String = "",      // 캐시 최초 생성 시각 HH:mm (KST)
)

/**
 * 기존 10개 매크로 지표를 그대로 받아 "오늘 코스피 방향"을 Claude가 해석하는 시장 분위기 서비스.
 * 새 데이터 소스 없이 /macro 지표만 재사용 → 추가 외부 API 비용 없음.
 *
 * 사실(지표 방향) → Claude 해석 분리 원칙은 MacroImpactService와 동일.
 * 캐시 키: date + 주요 지표 등락 0.5% 반올림 (의미 있는 변화 시에만 재생성).
 */
class MarketMoodService(
    private val kis: KisClient,
    private val claude: ClaudeClient,
    private val fearGreed: FearGreedClient,
    private val copper: CopperClient,
    private val ecos: EcosClient,
) {
    private val cache = ConcurrentHashMap<String, MarketMood>()
    private val fileCache = FileCache("market_mood", MarketMood.serializer())

    suspend fun get(): MarketMood {
        val today = LocalDate.now().toString()
        val kisIndicators = kis.getMacroIndicators()
        val extras = listOfNotNull(copper.get(), fearGreed.get(), ecos.get())
        val indicators = kisIndicators + extras

        val cacheKey = buildCacheKey(today, indicators)
        cache[cacheKey]?.let { return it }
        fileCache.get(cacheKey)?.let { cache[cacheKey] = it; return it }

        val facts = buildFacts(indicators)
        val comment = claude.complete(SYSTEM_PROMPT, facts, maxTokens = 600)

        val now = LocalTime.now(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val result = MarketMood(
            date = today,
            comment = comment,
            indicators = indicators,
            generatedAt = now,
        )
        cache[cacheKey] = result
        fileCache.put(cacheKey, result)
        return result
    }

    private fun buildCacheKey(today: String, indicators: List<MacroIndicator>): String {
        val rates = CACHE_INDICATORS.joinToString(",") { key ->
            val r = indicators.firstOrNull { it.key == key }?.changeRate ?: 0.0
            "$key=${(r * 2).roundToInt()}"
        }
        return "$today|$rates"
    }

    private fun buildFacts(indicators: List<MacroIndicator>): String {
        val sb = StringBuilder()
        sb.appendLine("현재 시장 지표 (전일 대비):")
        indicators.forEach { ind ->
            val sign = if (ind.changeRate >= 0) "+" else ""
            sb.appendLine("  - ${ind.label}: ${ind.value} ($sign${"%.2f".format(ind.changeRate)}%)")
        }
        return sb.toString()
    }

    companion object {
        // 캐시 키에 포함할 지표 — 시장 방향 판단에 핵심인 것들.
        private val CACHE_INDICATORS = listOf("nasdaq", "sp500", "dow", "usdkrw", "fear_greed", "crude")

        private val SYSTEM_PROMPT = """
            너는 한국 주식 투자 보조 앱의 장 전 시장 분위기 해석 어시스턴트다.
            독자는 주식에 관심 있는 일반인이다. 전문 용어는 괄호로 짧게 풀어준다.
            예) 원화 강세(원/달러 환율이 내릴 때), 외국인 수급(외국인 투자자가 사는지 파는지)

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 지표 수치만 근거로 삼는다. 거기 없는 수치를 절대 만들어내지 마라.
            2. 3~5문장의 연속 문단 하나로만 써라. 불릿·번호 목록, 소제목 금지.
            3. 이 흐름으로 써라:
               ① 가장 눈에 띄는 1~2개 지표가 오늘 코스피 출발 분위기에 어떤 방향을 만드는지.
               ② 추가 지표(환율·유가·심리 등)가 그 방향을 강화하는지 완화하는지.
               ③ 오늘 특히 챙겨볼 만한 포인트 한 문장으로 마무리.
            4. "지금 사라/팔라"처럼 매매를 지시하지 마라.
            5. 지표가 전부 보합(0%대)이면 "오늘은 해외 발 변수가 크지 않은 날"이라고 담백하게 써도 된다.
            6. 핵심 방향 키워드(우호적/부담/강세/약세 등)는 **굵게** 강조해 한눈에 들어오게 하라.
        """.trimIndent()
    }
}
