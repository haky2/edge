package com.haky.edge.macro

import com.haky.edge.ai.ClaudeClient
import com.haky.edge.ai.FileCache
import com.haky.edge.ai.effectiveMarketDate
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.SectorIndex
import com.haky.edge.master.StockMaster
import kotlinx.serialization.Serializable
import java.time.LocalDate
import com.haky.edge.util.DayScopedCache
import kotlin.math.roundToInt

/** 앱에 내려주는 섹터 브리핑 DTO. */
@Serializable
data class SectorBriefing(
    val date: String,
    val comment: String,              // Claude 섹터 트렌드 해석 + 관심종목 연결 코멘트
    val spotlight: List<SpotlightStock>, // 오늘 강세 섹터에 속한 관심종목 (알고리즘 계산)
    val generatedAt: String = "",     // 캐시 최초 생성 시각 HH:mm (KST)
)

/** 주목 종목 1건. 앱 표시용으로 이름·섹터 라벨 포함. */
@Serializable
data class SpotlightStock(
    val code: String,
    val name: String,
    val sectorLabel: String,
)

/**
 * 오늘 KOSPI 업종지수 흐름 + 내 관심종목과의 연결 고리를 Claude가 해석해 브리핑 한 단락으로 만든다.
 *
 * 원칙(CLAUDE.md): 사실(섹터 지수 등락·종목 섹터 분류)은 우리가 계산 → Claude는 해석만.
 * spotlight는 "강세 섹터(+0.5% 이상) ∩ 관심종목 섹터" 교집합으로 알고리즘 결정 — LLM 환각 없음.
 */
class SectorBriefingService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val macroImpact: MacroImpactService,
    // 해석 코멘트 모델 라우팅(기본 Opus). null이면 ClaudeClient 기본 모델(Sonnet).
    private val modelRouter: com.haky.edge.ai.ModelRouter? = null,
) {
    private val cache = DayScopedCache<SectorBriefing>()
    private val fileCache = FileCache("sector_briefing", SectorBriefing.serializer())

    suspend fun analyze(codes: List<String>, force: Boolean = false): SectorBriefing {
        // 주말 통합 거래일: 일요일은 토요일로 접어 재사용(데이터 동일). 평일·토요일은 당일.
        val today = effectiveMarketDate()
        val sectorIndices = kis.getSectorIndices()

        // 모든 섹터가 0% = 장 전 또는 데이터 미수신. Claude 호출 불필요, 캐시도 하지 않는다.
        // (캐시에 저장하면 장 중 재호출 시에도 빈 결과를 반환하게 됨)
        val allZero = sectorIndices.all { kotlin.math.abs(it.changeRate) < 0.01 }
        if (allZero) return SectorBriefing(today, comment = "", spotlight = emptyList())

        // 키 = 날짜 + 종목집합. 섹터 등락은 제외 — 하루에 한 번만 Claude 호출하고 당일은 캐시 재사용.
        // force=true면 캐시 건너뜀(수동 재생성).
        val cacheKey = buildKey(today, codes)
        if (!force) {
            cache.get(today, cacheKey)?.let { return it }
            fileCache.get(cacheKey)?.let { cache.put(today, cacheKey, it); return it }
        }

        // 각 종목의 이름 + 섹터 분류(MacroImpactService 7일 캐시 재사용).
        val stockSectors: List<Triple<String, String, List<MacroImpactService.Sector>>> = codes.map { code ->
            val name = master.findByCode(code)?.name ?: code
            val kisName = runCatching { kis.getPrice(code).sectorName }.getOrElse { "" }
            Triple(code, name, macroImpact.resolveStockSectors(code, name, kisName))
        }

        // 오늘 강세 섹터(+0.5% 이상) → 해당 관심종목 추출(알고리즘, LLM 아님).
        // KRX 업종지수는 거친 단위라 세부 Sector 대신 대분류(group)로 매칭한다.
        val strongGroups: Set<MacroImpactService.MacroGroup> = sectorIndices
            .filter { it.changeRate > 0.5 }
            .flatMap { SECTOR_INDEX_TO_OUR[it.key].orEmpty() }
            .toSet()
        val spotlight = stockSectors
            .filter { (_, _, sectors) -> sectors.any { it.group in strongGroups } }
            .map { (code, name, sectors) ->
                SpotlightStock(
                    code = code,
                    name = name,
                    sectorLabel = sectors.joinToString("·") { it.label }.ifEmpty { "기타" },
                )
            }
            .take(3)

        val facts = buildFacts(sectorIndices, stockSectors, spotlight)
        // 상한(ceiling)일 뿐 — 2~3문단이면 보통 그 안에서 end_turn, 길어져도 ClaudeClient가 이어써 안 잘림.
        val model = modelRouter?.modelFor(com.haky.edge.ai.ModelRouter.SECTOR_BRIEFING)
        val comment = claude.complete(SYSTEM_PROMPT, facts, maxTokens = 2800, modelOverride = model)

        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val result = SectorBriefing(today, comment, spotlight, generatedAt = now)
        cache.put(today, cacheKey, result)
        fileCache.put(cacheKey, result)
        return result
    }

    private fun buildFacts(
        sectors: List<SectorIndex>,
        stocks: List<Triple<String, String, List<MacroImpactService.Sector>>>,
        spotlight: List<SpotlightStock>,
    ): String = buildString {
        appendLine("오늘 KOSPI 업종지수 (전일 대비):")
        sectors.forEach { appendLine("  ${it.label}: ${signed(it.changeRate)}%") }
        appendLine()
        appendLine("관심종목 섹터 분류:")
        stocks.forEach { (code, name, secs) ->
            val secLabel = if (secs.isEmpty()) "기타" else secs.joinToString("·") { it.label }
            appendLine("  $name($code): $secLabel")
        }
        appendLine()
        if (spotlight.isNotEmpty()) {
            val names = spotlight.joinToString(", ") { "${it.name}(${it.code}) — ${it.sectorLabel}" }
            appendLine("오늘 강세 섹터(+0.5% 이상)에 속하는 관심종목: $names")
        } else {
            appendLine("오늘 강세 섹터에 속하는 관심종목 없음 (또는 모든 섹터 보합).")
        }
    }

    private fun signed(v: Double) = (if (v >= 0) "+" else "") + "%.2f".format(v)

    companion object {
        // KOSPI 업종지수 key → 매크로 대분류(MacroGroup) (1:N 가능. 예: 전기전자에 반도체+전자 혼재).
        private val SECTOR_INDEX_TO_OUR = mapOf(
            "sector_0014" to listOf(MacroImpactService.MacroGroup.SEMICONDUCTOR, MacroImpactService.MacroGroup.ELECTRONICS),
            "sector_0013" to listOf(MacroImpactService.MacroGroup.SHIPBUILDING),
            "sector_0016" to listOf(MacroImpactService.MacroGroup.AUTOMOBILE, MacroImpactService.MacroGroup.DEFENSE),
            "sector_0018" to listOf(MacroImpactService.MacroGroup.POWER_EQUIP),
            "sector_0028" to listOf(MacroImpactService.MacroGroup.TECH_GROWTH),
            "sector_0012" to listOf(MacroImpactService.MacroGroup.POWER_EQUIP),
        )

        private val SYSTEM_PROMPT = """
            너는 한국 주식 투자 앱의 섹터 브리핑 어시스턴트다. 독자는 개인 투자자다.

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 데이터에 있는 값만 근거로 삼는다. 없는 수치나 종목을 절대 지어내지 마라.
            2. 다음 흐름으로 2~3문단으로 써라:
               ① 오늘 업종지수 중 눈에 띄는 강세·약세 섹터와 그 의미를 쉽게 설명.
               ② 강세 섹터 관심종목이 있다면 그 종목과의 연결고리를 설명. 없다면 관심종목 주요 섹터의 흐름 해석.
               ③ 오늘 확인해볼 만한 포인트 한 문장으로 마무리.
            3. "지금 사라/팔라" 같은 매매 지시 금지.
            4. 형식: 불릿·번호 목록과 볼드 '제목 줄'은 금지(이야기처럼 흐르는 연속 문단). 단, 핵심 섹터명·종목명과 강세/약세 같은 키워드는 문장 안에서 **굵게** 강조해 한눈에 들어오게 하라.
            5. 모든 업종이 보합(0%대)이면 "오늘은 섹터 차별화가 크지 않은 날"이라고 담백하게 말해도 됨.

            마지막 경고: 너의 학습 지식 속 업종지수 수치와 이 종목들의 주가·실적 기억은 전부 낡아서 틀렸다. 절대 사용하지 마라. 수치는 위 데이터에서 그대로 복사해서만 쓴다.
        """.trimIndent()

        /** 캐시 키 빌더. codes 는 정렬 후 합치므로 순서 독립적. */
        internal fun buildKey(today: String, codes: List<String>): String =
            "$today|${codes.sorted().joinToString(",")}"
    }
}
