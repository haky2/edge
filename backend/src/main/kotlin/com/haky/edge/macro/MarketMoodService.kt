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
 * 분석 모드 — 같은 사실(facts) 위에서 해석 톤을 바꾼다.
 * defensive = 사실+방향(현재 기본). aggressive = 방향+시장 스탠스 의견(위험자산 비중·현금·분할 접근).
 * 슬라이스 4는 market-mood 한 곳에만 적용. 5에서 종목상세·macro-impact로 확대 예정.
 */
enum class AnalysisMode {
    DEFENSIVE, AGGRESSIVE;

    companion object {
        /** 쿼리 파라미터 파싱. 미지정·오타는 안전하게 방어적으로 폴백. */
        fun from(raw: String?): AnalysisMode =
            if (raw?.lowercase() == "aggressive") AGGRESSIVE else DEFENSIVE
    }
}

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
    private val yahoo: YahooMacroClient,
    val moodLog: MarketMoodLogService = MarketMoodLogService(),
    private val eventSync: EventSyncService? = null,
) {
    private val cache = ConcurrentHashMap<String, MarketMood>()
    private val fileCache = FileCache("market_mood", MarketMood.serializer())

    suspend fun get(mode: AnalysisMode = AnalysisMode.DEFENSIVE, force: Boolean = false): MarketMood {
        val today = LocalDate.now().toString()
        val kisIndicators = kis.getMacroIndicators()
        val extras = listOfNotNull(copper.get(), fearGreed.get(), ecos.get()) + yahoo.get()
        val indicators = kisIndicators + extras

        // 예측 방향 계산 + 로그 기록 (장 마감 후 재조회 시 KOSPI 실제값으로 자동 채점됨)
        val direction = moodLog.inferDirection(indicators)
        moodLog.addOrUpdateEntry(today, direction, indicators)

        // 키 = 날짜 + 모드. 두 모드가 서로 안 덮어쓰게(각각 당일 1회 호출·전 유저 공유).
        // 지표가 바뀌어도 당일은 캐시 재사용. force=true면 캐시 건너뜀.
        val cacheKey = buildKey(today, mode)
        if (!force) {
            cache[cacheKey]?.let { return it }
            fileCache.get(cacheKey)?.let { cache[cacheKey] = it; return it }
        }

        val eventsText = runCatching { eventSync?.upcomingFactsText() }.getOrNull()
        val facts = buildFacts(indicators, eventsText)
        val prompt = if (mode == AnalysisMode.AGGRESSIVE) AGGRESSIVE_PROMPT else DEFENSIVE_PROMPT
        // 상한(ceiling)일 뿐 — 3문단이면 보통 그 안에서 end_turn, 길어져도 ClaudeClient가 이어써 안 잘림.
        val comment = claude.complete(prompt, facts, maxTokens = 2000)

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

    private fun buildFacts(indicators: List<MacroIndicator>, eventsText: String?): String {
        val sb = StringBuilder()
        sb.appendLine("현재 시장 지표 (전일 대비):")
        indicators.forEach { ind ->
            val sign = if (ind.changeRate >= 0) "+" else ""
            sb.appendLine("  - ${ind.label}: ${ind.value} ($sign${"%.2f".format(ind.changeRate)}%)")
        }
        if (eventsText != null) {
            sb.appendLine()
            sb.append(eventsText)
        }
        return sb.toString()
    }

    companion object {
        // 방어적(기본): 사실 + 방향만. 매매 스탠스 의견 없음.
        private val DEFENSIVE_PROMPT = """
            너는 한국 주식 투자 보조 앱의 장 전 시장 분위기 해석 어시스턴트다.
            독자는 주식에 관심 있는 일반인이다. 전문 용어는 괄호로 짧게 풀어준다.
            예) 원화 강세(원/달러 환율이 내릴 때), 외국인 수급(외국인 투자자가 사는지 파는지)

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 지표 수치만 근거로 삼는다. 거기 없는 수치를 절대 만들어내지 마라.
            2. 아래 3개 문단을 각각 빈 줄(줄바꿈 2번)로 구분해서 써라. 불릿·번호 목록, 소제목 금지.
               문단 ①: 가장 눈에 띄는 1~2개 지표가 오늘 코스피 출발 분위기에 어떤 방향을 만드는지. (2~3문장)
               문단 ②: 나머지 지표(환율·유가·심리·금리 등)가 그 방향을 강화하는지 완화하는지. (2~3문장)
               문단 ③: 오늘 특히 챙겨볼 만한 포인트 한 문장으로 마무리.
            3. "지금 사라/팔라"처럼 매매를 지시하지 마라.
            4. 지표가 전부 보합(0%대)이면 "오늘은 해외 발 변수가 크지 않은 날"이라고 담백하게 써도 된다.
            5. 핵심 방향 키워드(우호적/부담/강세/약세 등)는 **굵게** 강조해 한눈에 들어오게 하라.
            6. "임박 거시 이벤트" 섹션이 있으면, 그중 코스피 방향에 가장 영향이 큰 일정 1~2개만 문단 ③에서
               날짜(또는 D-day)와 함께 짚어라(예: "이번 주 목요일 FOMC 금리결정을 앞두고 관망 심리가 짙어질 수 있다").
               날짜·이름은 사실대로, 영향은 조건부로. 일정 전체를 나열하지 말고 핵심만.
        """.trimIndent()

        // 공격적: 방어적과 같은 사실·환각가드 위에서, 마지막 문단에 "오늘 어떤 자세가 합리적인지"
        // 시장 레벨 스탠스 의견까지 더한다. 개별 종목 지정은 금지(그건 종목상세 분석의 몫).
        private val AGGRESSIVE_PROMPT = """
            너는 한국 주식 투자 보조 앱의 장 전 시장 분위기 해석 어시스턴트다.
            지금은 "공격적 모드" — 사용자가 단호하고 직설적인 시장 스탠스 의견을 직접 요청해 켠 상태다.
            에두르거나 양비론으로 빠지지 말고, 핵심을 자신감 있게 딱 잘라 말하라.
            독자는 주식에 관심 있는 일반인이다. 전문 용어는 괄호로 짧게 풀어준다.
            예) 원화 강세(원/달러 환율이 내릴 때), 위험자산(주식처럼 변동성 큰 자산)

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 지표 수치만 근거로 삼는다. 거기 없는 수치를 절대 만들어내지 마라.
               모든 의견은 반드시 위 지표 사실에 묶어서 말하라("나스닥 -4%이므로 ~").
            2. 아래 3개 문단을 각각 빈 줄(줄바꿈 2번)로 구분해서 써라. 불릿·번호 목록, 소제목 금지.
               문단 ①: 가장 눈에 띄는 1~2개 지표가 오늘 코스피 출발 분위기를 어느 방향으로 끌지 단정적으로 못박아라. (2~3문장)
               문단 ②: 나머지 지표(환율·유가·심리·금리 등)가 그 방향을 강화하는지 완화하는지 분명히 정리하라. (2~3문장)
               문단 ③: 오늘 같은 분위기에 어떤 시장 자세를 취해야 하는지 직설적인 명령형으로 제시하라. (2~3문장)
                       허용: "위험자산 비중을 줄여라/늘려라", "현금을 확보하라", "낙폭과대 업종을 분할로 담아라",
                             "반등은 비중 축소 기회로 써라" 등 시장 전체 차원의 단호한 스탠스 명령.
            3. 단, 특정 개별 종목을 지목해 "X를 사라/팔라"고는 하지 마라(시장 전체·업종 스탠스만).
            4. 어조는 단호하게, 한쪽으로 명확히 결론지어라. 어설픈 양비론·"~수도 있다"식 회피는 금지.
               지표가 진짜로 정면충돌할 때만 우세한 쪽을 고른 뒤 반대 리스크를 한 문장으로 덧붙여라.
               단, 이것은 어조의 단호함이지 미래 보장이 아니다 — "반드시 오른다/떨어진다"처럼
               결과를 확정하는 표현은 쓰지 마라(스탠스는 단호하게, 결과 단정은 금지).
            5. 핵심 방향·스탠스 키워드(비중 축소/분할 매수/현금 확보/강세 등)는 **굵게** 강조하라.
            6. "임박 거시 이벤트" 섹션이 있으면, 코스피 방향에 가장 영향이 큰 일정 1~2개를 문단 ③ 스탠스에 묶어라
               (예: "D-2 FOMC 전까지 신규 베팅은 줄이고 결과를 보고 대응하라"). 날짜·이름은 사실대로, 결과 방향은 조건부로.
        """.trimIndent()

        /** 캐시 키 빌더. 날짜 + 모드로 두 모드가 서로 덮어쓰지 않게 분리. */
        internal fun buildKey(today: String, mode: AnalysisMode): String = "$today|${mode.name}"
    }
}
