package com.haky.edge.macro

import com.haky.edge.ai.ClaudeClient
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.MacroIndicator
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// ── 앱에 내려주는 응답 DTO ────────────────────────────────────────────

/** 매크로 → 내 종목 영향 분석 결과. comment = 보유/관심을 묶어 해석한 Claude 종합 코멘트. */
@Serializable
data class MacroImpact(
    val date: String,                  // 생성 기준일 (YYYY-MM-DD)
    val comment: String,               // Claude 종합 해석(보유/관심 구분 서술)
    val indicators: List<MacroIndicator>, // 오늘 시장 지표(브리핑 표시와 동일)
    val holdings: List<StockImpact>,   // 보유 종목별 영향
    val watchlist: List<StockImpact>,  // 관심(미보유) 종목별 영향
    val generatedAt: String = "",      // 캐시 최초 생성 시각 HH:mm (KST)
)

/** 종목 1개에 대한 매크로 영향(계산 기반, LLM 아님). */
@Serializable
data class StockImpact(
    val code: String,
    val name: String,
    val sectorLabel: String,        // 우리 분류 섹터명("반도체") 또는 한투 업종명 폴백
    val net: String,                // "우호적"/"부담"/"중립"/"-"(매핑 없음)
    val signals: List<MacroSignal>, // 지표별 방향 신호
)

/** 종목 × 지표 1건의 방향 신호. direction: +1 우호 / 0 중립 / -1 부담. */
@Serializable
data class MacroSignal(
    val indicator: String,   // "원/달러", "나스닥"
    val changeRate: Double,  // 오늘 해당 지표 등락률 %
    val direction: Int,      // 이 종목에 미치는 방향(+1/0/-1)
    val note: String,        // 근거 한 줄
)

/**
 * 매크로 지표가 내 보유/관심 종목에 어떤 영향인지 분석한다.
 *
 * 원칙(CLAUDE.md): **사실은 우리가 계산(섹터 매핑 × 지표 방향) → Claude 는 해석만.**
 *  - 섹터→지표 민감도를 하드코딩(아래 SENSITIVITY)하고, 종목은 섹터 오버라이드(SECTOR_OVERRIDE)로 연결.
 *  - 각 종목의 "오늘 영향 방향"은 [민감도 부호 × 지표 등락 부호]로 계산해 사실로 제공.
 *  - Claude 는 그 사실을 받아 "그래서 오늘 어떻게 봐야 하나"만 서술(수치 날조 금지, 참고용).
 *
 * 비용: (날짜 + 종목집합 + 지표 등락 0.5%반올림) 캐시. 같은 입력이면 1회만 생성.
 */
class MacroImpactService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val claude: ClaudeClient,
    private val fearGreed: FearGreedClient,
    private val copper: CopperClient,
    private val ecos: EcosClient,
    private val naver: NaverNewsClient,
) {
    private val cache = ConcurrentHashMap<String, MacroImpact>()

    suspend fun analyze(holdings: List<String>, watchlist: List<String>): MacroImpact {
        val today = LocalDate.now().toString()
        val kisIndicators = kis.getMacroIndicators()
        // copper·rate3y는 IMPACT_INDICATORS에 포함(방향 계산 대상). fear_greed는 맥락용(방향 계산 제외).
        val extras = listOfNotNull(copper.get(), fearGreed.get(), ecos.get())
        val indicators = kisIndicators + extras

        // 캐시 키: 날짜 + 종목집합 + 영향 계산에 쓰는 지표 등락(0.5% 반올림) → 의미있는 변화 시 재생성.
        val ratesKey = IMPACT_INDICATORS.joinToString(",") { key ->
            val r = indicators.firstOrNull { it.key == key }?.changeRate ?: 0.0
            "$key=${(r * 2).roundToInt()}"
        }
        val cacheKey = "$today|H:${holdings.sorted().joinToString(",")}|W:${watchlist.sorted().joinToString(",")}|$ratesKey"
        cache[cacheKey]?.let { return it }

        val holdingImpacts = holdings.map { buildStockImpact(it, indicators) }
        val watchImpacts = watchlist.map { buildStockImpact(it, indicators) }

        val facts = buildFacts(indicators, holdingImpacts, watchImpacts)
        val comment = claude.complete(SYSTEM_PROMPT, facts, maxTokens = 1536)

        val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val result = MacroImpact(
            date = today,
            comment = comment,
            indicators = indicators,
            holdings = holdingImpacts,
            watchlist = watchImpacts,
            generatedAt = now,
        )
        cache[cacheKey] = result
        return result
    }

    /** 종목 1개의 섹터(복수 가능)를 결정하고 지표별 방향 신호를 계산한다. */
    private suspend fun buildStockImpact(code: String, indicators: List<MacroIndicator>): StockImpact {
        val name = master.search(code).firstOrNull { it.code == code }?.name ?: code
        val kisName = runCatching { kis.getPrice(code).sectorName }.getOrElse { "" }
        // 수동 오버라이드 → 캐시 → Claude 자동 추론 → KIS 업종명 폴백 순.
        val sectors = resolveSectors(code, name, kisName)

        val sectorLabel = if (sectors.isEmpty()) "기타" else sectors.joinToString("·") { it.label }

        if (sectors.isEmpty()) {
            return StockImpact(code, name, "기타", net = "-", signals = emptyList())
        }

        // 세부 섹터를 매크로 대분류(group)로 환원·중복 제거 후 민감도를 지표별로 집계한다.
        // 같은 지표가 여러 대분류에 나오면 방향을 합산 후 부호만 취한다(+1/-1/0).
        // note: 방향이 모두 같으면 첫 번째 설명, 방향이 갈리면 각 설명을 병기.
        val allSens = sectors.map { it.group }.distinct().flatMap { SENSITIVITY[it].orEmpty() }
        val signals = allSens.groupBy { it.indicatorKey }.mapNotNull { (key, group) ->
            val ind = indicators.firstOrNull { it.key == key } ?: return@mapNotNull null
            val rate = ind.changeRate
            val paired = group.map { s ->
                val effDir = when {
                    s.direction == 0 -> 0
                    rate > 0.0 -> s.direction
                    rate < 0.0 -> -s.direction
                    else -> 0
                }
                Pair(s, effDir)
            }
            val directionSum = paired.sumOf { it.second }
            val direction = when {
                directionSum > 0 -> 1
                directionSum < 0 -> -1
                else -> 0
            }
            // note: 방향이 상충하면 모든 이유 병기. 상충 없으면 실제로 non-zero를 기여한 섹터의 note.
            val nonZeroSet = paired.map { it.second }.filter { it != 0 }.toSet()
            val note = when {
                nonZeroSet.size > 1 -> paired.joinToString(" / ") { it.first.note }
                nonZeroSet.isEmpty() -> paired.first().first.note
                else -> paired.firstOrNull { it.second != 0 }?.first?.note ?: paired.first().first.note
            }
            MacroSignal(indicator = ind.label, changeRate = rate, direction = direction, note = note)
        }
        val sum = signals.sumOf { it.direction }
        val net = when {
            signals.isEmpty() -> "-"
            sum > 0 -> "우호적"
            sum < 0 -> "부담"
            else -> "중립"
        }
        return StockImpact(code, name, sectorLabel, net = net, signals = signals)
    }

    /** Claude 입력용 사실 텍스트. 여기 있는 값(지표·종목별 방향)만 근거로 쓰라고 시스템 프롬프트가 지시. */
    private fun buildFacts(
        indicators: List<MacroIndicator>,
        holdings: List<StockImpact>,
        watchlist: List<StockImpact>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("오늘 시장 지표(전일 대비):")
        indicators.forEach {
            sb.appendLine("  - ${it.label} ${"%.2f".format(it.value)} (${signed(it.changeRate)}%)")
        }
        sb.appendLine()
        appendGroup(sb, "[보유 종목]", holdings)
        sb.appendLine()
        appendGroup(sb, "[관심 종목(미보유)]", watchlist)
        return sb.toString()
    }

    private fun appendGroup(sb: StringBuilder, title: String, items: List<StockImpact>) {
        sb.appendLine(title)
        if (items.isEmpty()) {
            sb.appendLine("  (없음)")
            return
        }
        items.forEach { s ->
            sb.appendLine("  - ${s.name}(${s.code}) · ${s.sectorLabel} · 종합:${s.net}")
            if (s.signals.isEmpty()) {
                sb.appendLine("      (영향 매핑 준비 중)")
            } else {
                s.signals.forEach { sig ->
                    val dir = when {
                        sig.direction > 0 -> "우호"
                        sig.direction < 0 -> "부담"
                        else -> "중립"
                    }
                    sb.appendLine("      · ${sig.indicator} ${signed(sig.changeRate)}% → $dir (${sig.note})")
                }
            }
        }
    }

    private fun signed(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v)

    // ── 섹터 자동 추론 ────────────────────────────────────────────────────────

    /** 7일 캐시: code → (섹터 목록, 만료 epoch ms). 최초 조회 시 Claude 추론, 이후 캐시 반환. */
    private val sectorCache = ConcurrentHashMap<String, Pair<List<Sector>, Long>>()

    /** SectorBriefingService에서 재사용: 종목 코드·이름·KIS업종명으로 섹터 목록 반환. */
    suspend fun resolveStockSectors(code: String, name: String, kisName: String): List<Sector> =
        resolveSectors(code, name, kisName)

    /** 종목 1개의 매크로 지표 영향 신호. Claude 호출 없음 — 섹터 결정 + 지표별 방향 계산만. 상세화면용. */
    suspend fun stockSignals(code: String): StockImpact {
        val indicators = kis.getMacroIndicators()
        return buildStockImpact(code, indicators)
    }

    /**
     * 종목 섹터 결정. 우선순위: 수동 오버라이드 → 7일 캐시 → Claude 자동 추론 → KIS 업종명 폴백.
     * Claude 추론이 틀린 경우에만 MANUAL_OVERRIDES에 수동으로 추가한다.
     */
    private suspend fun resolveSectors(code: String, name: String, kisName: String): List<Sector> {
        MANUAL_OVERRIDES[code]?.let { return it }
        sectorCache[code]?.takeIf { System.currentTimeMillis() < it.second }?.let { return it.first }

        val sectors = runCatching { inferSectors(code, name, kisName) }
            .getOrElse { listOfNotNull(autoSector(kisName)) }
        sectorCache[code] = Pair(sectors, System.currentTimeMillis() + SECTOR_CACHE_TTL_MS)
        return sectors
    }

    /**
     * Claude에 회사명·KIS업종명·최근 뉴스 헤드라인을 주고 섹터 목록을 JSON 배열로 추론받는다.
     * 뉴스를 포함해 "로봇", "AI" 같은 신규 사업도 자동 인식한다.
     * maxTokens=60 — 배열 하나만 오면 충분하므로 비용이 매우 작다. 7일 캐시라 주 1회 이하.
     */
    private suspend fun inferSectors(code: String, name: String, kisName: String): List<Sector> {
        val headlines = runCatching { naver.search(name, display = 5) }.getOrElse { emptyList() }
        val newsBullet = if (headlines.isEmpty()) "(뉴스 없음)"
        else headlines.joinToString("\n") { "- ${it.title}" }

        val enumList = Sector.entries.joinToString("\n") { "- ${it.name}: ${it.label} (${it.description})" }
        val response = claude.complete(
            systemPrompt = "너는 한국 주식 섹터 분류 전문가다. 요청한 JSON 배열 형식으로만 응답해. 설명, 마크다운 코드블록 없이 배열만.",
            userFacts = """
다음 한국 주식이 아래 세부 섹터 중 어디에 해당하는지 JSON 배열로만 답해줘.

분류 규칙:
- 매출·사업 비중이 큰 주력 섹터를 맨 앞에 두고, 최대 3개까지.
- 회사를 가장 구체적으로 설명하는 세부 섹터를 골라라. (예: SI·클라우드 업체가 생성형AI를 핵심으로 밀면 IT_SERVICE보다 AI_CLOUD를 우선)
- KIS 업종명은 거칠다. 최근 뉴스에서 실제 주력·신규 사업(AI, 클라우드, 로봇, 방산, HBM 등)이 보이면 그걸 우선 반영해라.
- 확실하지 않은 부차 섹터는 억지로 채우지 말고 주력 1~2개만 골라도 된다.

종목: $name ($code)
KIS 업종명: $kisName
최근 뉴스:
$newsBullet

선택 가능한 세부 섹터(영어 코드로 응답):
$enumList

응답 예시: ["AI_CLOUD","IT_SERVICE"]
            """.trimIndent(),
            maxTokens = 60,
        )
        return Regex(""""(\w+)"""").findAll(response)
            .mapNotNull { mr -> runCatching { Sector.valueOf(mr.groupValues[1]) }.getOrNull() }
            .toList()
            .ifEmpty { listOfNotNull(autoSector(kisName)) }
    }

    // ── 도메인 매핑(여기를 고치면 영향 규칙이 바뀐다) ───────────────────────────

    /**
     * KIS 업종명(bstp_kor_isnm)으로 섹터를 추론한다. Claude 추론 실패 시 폴백.
     * 업종명이 여러 섹터에 걸치는 경우(예: "전기·전자"에 반도체+가전 혼재)는 가장 넓은 섹터로 보수 매핑.
     */
    private fun autoSector(kisName: String): Sector? = when {
        kisName.contains("서비스")                         -> Sector.IT_SERVICE
        kisName.contains("전기가스") || kisName.contains("전력") -> Sector.POWER_EQUIP
        kisName.contains("전선")                           -> Sector.CABLE
        kisName.contains("철강") || kisName.contains("금속") -> Sector.POWER_EQUIP
        kisName.contains("기계") || kisName.contains("조선") || kisName.contains("중공업") -> Sector.SHIPBUILDING
        // "운수장비"/"운송장비"는 완성차가 주력. 방산/조선 주력 종목은 뉴스 기반 inferSectors가 먼저 잡는다.
        kisName.contains("운수장비") || kisName.contains("운송장비") || kisName.contains("자동차") -> Sector.AUTO_OEM
        kisName.contains("항공")                          -> Sector.DEFENSE
        kisName.contains("반도체")                         -> Sector.MEMORY
        kisName.contains("전기·전자") || kisName.contains("전자") -> Sector.COMPONENT
        else                                             -> null
    }

    /**
     * 매크로 민감도 대분류. 환율·금리·구리 등 매크로 반응은 거친 단위에서 비슷하므로(반도체끼리 동일)
     * SENSITIVITY는 이 대분류 기준으로만 정의한다. 표시·추천은 아래 세부 Sector를 쓴다.
     */
    enum class MacroGroup {
        SEMICONDUCTOR, TECH_GROWTH, AUTOMOBILE, SHIPBUILDING, DEFENSE, POWER_EQUIP, ELECTRONICS
    }

    /**
     * 표시·추천용 세부 섹터(회사가 실제로 뭘 하는지). 각 섹터는 매크로 민감도 대분류(group) 하나에 매핑된다.
     * 한투 업종명이 거칠어(예: '전기·전자'에 반도체+가전 혼재) inferSectors가 뉴스까지 보고 이 세부로 분류한다.
     * 세부를 늘려도 SENSITIVITY(대분류 기준)는 건드릴 필요가 없다.
     */
    enum class Sector(val label: String, val description: String, val group: MacroGroup) {
        // 반도체
        MEMORY("메모리반도체",   "D램·낸드 메모리",                     MacroGroup.SEMICONDUCTOR),
        FOUNDRY("파운드리·장비", "위탁생산·반도체 장비·소재",            MacroGroup.SEMICONDUCTOR),
        AI_CHIP("AI반도체",      "HBM·AI가속기·고대역폭메모리",          MacroGroup.SEMICONDUCTOR),
        // 테크 성장(소프트·플랫폼·로봇·자율주행 — 나스닥/금리 민감)
        AI_CLOUD("AI·클라우드",  "생성형AI·클라우드·데이터센터 소프트웨어", MacroGroup.TECH_GROWTH),
        IT_SERVICE("IT서비스·SI","시스템통합·IT아웃소싱·엔터프라이즈 SW",  MacroGroup.TECH_GROWTH),
        INTERNET("인터넷플랫폼", "포털·커머스·핀테크·콘텐츠 플랫폼",      MacroGroup.TECH_GROWTH),
        ROBOT("로봇·자동화",     "산업용·협동로봇·스마트팩토리",          MacroGroup.TECH_GROWTH),
        AUTONOMOUS("자율주행",   "자율주행·모빌리티 소프트웨어",          MacroGroup.TECH_GROWTH),
        // 자동차
        AUTO_OEM("완성차",       "승용·상용 완성차",                    MacroGroup.AUTOMOBILE),
        AUTO_PARTS("자동차부품", "전장·구동·차체 부품",                 MacroGroup.AUTOMOBILE),
        BATTERY("2차전지",       "배터리 셀·소재·장비",                 MacroGroup.AUTOMOBILE),
        // 조선·방산
        SHIPBUILDING("조선",     "조선·해양플랜트·선박",                MacroGroup.SHIPBUILDING),
        DEFENSE("방산·항공우주", "무기체계·항공우주",                   MacroGroup.DEFENSE),
        // 전력
        POWER_EQUIP("전력기기",  "변압기·전력기기·중전기",              MacroGroup.POWER_EQUIP),
        CABLE("전선",            "전선·케이블",                        MacroGroup.POWER_EQUIP),
        RENEWABLE("신재생에너지","태양광·풍력·에너지 인프라",            MacroGroup.POWER_EQUIP),
        // 전자
        HOME_APPLIANCE("가전",   "생활가전·AV",                        MacroGroup.ELECTRONICS),
        DISPLAY("디스플레이",    "OLED·LCD·패널",                      MacroGroup.ELECTRONICS),
        COMPONENT("전자부품",    "MLCC·기판·카메라모듈 등 부품",         MacroGroup.ELECTRONICS),
    }

    /** 대분류 × 지표 민감도 1건. direction: +1 = 지표 상승이 해당 그룹에 우호, -1 = 부담, 0 = 무관. */
    private data class Sensitivity(val indicatorKey: String, val direction: Int, val note: String)

    companion object {
        // 영향 방향 계산에 쓰는 지표. fear_greed는 방향 계산 제외(맥락용).
        private val IMPACT_INDICATORS = listOf("usdkrw", "nasdaq", "crude", "copper", "rate3y")

        // 섹터 캐시 유효기간: 7일. 사업 방향은 자주 바뀌지 않으므로 충분.
        private const val SECTOR_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L

        // Claude 자동 추론이 명확히 틀린 경우에만 여기에 추가(코드 수정 불필요가 기본).
        private val MANUAL_OVERRIDES = mapOf<String, List<Sector>>()

        // 섹터별 매크로 민감도. note 는 근거 한 줄(앱·Claude facts에 그대로 노출).
        // 매크로 민감도는 대분류(MacroGroup) 기준. 세부 Sector는 group으로 여기에 연결된다.
        private val SENSITIVITY = mapOf(
            MacroGroup.SEMICONDUCTOR to listOf(
                Sensitivity("usdkrw", +1, "원화 약세 → 수출 채산성 개선"),
                Sensitivity("nasdaq", +1, "미국 빅테크·AI 반도체와 주가 동조"),
                Sensitivity("crude",  -1, "유가 상승 → 인플레·금리 우려 → 성장주 부담"),
                Sensitivity("rate3y", -1, "금리 상승 → 성장주 밸류에이션 할인율 확대"),
            ),
            MacroGroup.SHIPBUILDING to listOf(
                Sensitivity("usdkrw", +1, "수주 대금 달러 결제 → 원화 약세 수혜"),
                Sensitivity("crude",  +1, "유가 상승 → 유조선·LNG선 발주 수요 증가"),
                Sensitivity("rate3y", -1, "금리 상승 → 선박금융 조달 비용 증가, 선주 투자 부담"),
            ),
            MacroGroup.DEFENSE to listOf(
                Sensitivity("usdkrw", +1, "방산 수출 비중 → 원화 약세 우호"),
            ),
            MacroGroup.POWER_EQUIP to listOf(
                Sensitivity("usdkrw", +1, "변압기 등 수출 비중 → 원화 약세 우호"),
                Sensitivity("nasdaq", +1, "미국 데이터센터·전력 인프라 투자 테마 연동"),
                Sensitivity("crude",  +1, "유가 상승 → 에너지 전환·신재생 투자 가속화"),
                Sensitivity("copper", -1, "구리 상승 → 변압기·전선 주요 원재료 원가 부담"),
                Sensitivity("rate3y", -1, "금리 상승 → 인프라 투자 할인율 상승, 밸류에이션 부담"),
            ),
            // IT서비스(내수)+로봇·AI(성장·수출)를 묶은 그룹. 내수·수출 혼재라 환율은 중립.
            MacroGroup.TECH_GROWTH to listOf(
                Sensitivity("nasdaq", +1, "미국 빅테크·AI 테마와 동조 — 나스닥 강세 시 동반 상승"),
                Sensitivity("rate3y", -1, "성장 기대가 반영된 높은 주가 배수 → 금리 상승 시 할인율 부담 확대"),
            ),
            MacroGroup.ELECTRONICS to listOf(
                Sensitivity("usdkrw", +1, "수출 비중 높아 원화 약세 우호(수입 부품이 일부 상쇄)"),
                Sensitivity("crude",  -1, "유가 상승 → 물류·부품 운반비 원가 부담"),
                Sensitivity("copper", -1, "구리 상승 → PCB·배선 부품 원가 부담"),
            ),
            MacroGroup.AUTOMOBILE to listOf(
                Sensitivity("usdkrw", +1, "수출 비중 → 원화 약세 시 해외 매출 환산 이익 증가"),
                Sensitivity("crude",  -1, "유가 상승 → 소비자 유지비 부담 → 자동차 수요 심리 위축"),
                Sensitivity("copper", -1, "구리 상승 → 차량 배선·전장부품 원재료 원가 부담"),
                Sensitivity("rate3y", -1, "금리 상승 → 자동차 할부 이자 증가 → 구매 수요 감소"),
            ),
        )

        private val SYSTEM_PROMPT = """
            너는 한국 주식 투자 보조 앱의 매크로 영향 분석 어시스턴트다.
            독자는 주식에 관심 있는 일반인이다. 전문 용어는 괄호로 짧게 풀어준다.
            예) 원화 약세(원/달러 환율이 오를 때, 1달러에 더 많은 원화가 필요), 수급(외국인·기관·개인 중 누가 사고 파는지)

            규칙(반드시 지킬 것):
            1. 아래 user 메시지의 "사실 데이터"에 있는 값만 근거로 삼는다. 거기 없는 수치나 종목을 절대 지어내지 마라.
            2. 다음 흐름으로 자연스러운 3~4문단으로 써라:
               ① 오늘 시장 전체 흐름 — "오늘 가장 눈에 띄는 지표는 무엇이고, 그게 국내 주식 시장에 어떤 분위기를 만들었는지" 쉽게 설명.
               ② 보유 종목에 오늘 흐름이 어떤 영향인지 — 종목별로 나열하지 말고 비슷한 성격끼리 묶어서 설명.
               ③ 관심 종목도 같은 방식으로. 보유 종목과 관심 종목이 같은 방향이면 합쳐도 됨.
               ④ 오늘 이 흐름에서 주의해야 할 점 또는 확인해볼 만한 것 한 문장으로 마무리.
            3. "지금 사라/팔라"처럼 매매를 지시하지 마라.
            4. 어려운 금융 영어는 한국어로 바꾸거나 괄호 설명을 붙여라.
            5. 형식: 불릿·번호 목록과 볼드 '제목 줄'은 금지(이야기처럼 흐르는 연속 문단). 단, 핵심 종목명과 영향 방향(우호/부담) 같은 키워드는 문장 안에서 **굵게** 강조해 한눈에 들어오게 하라.
            6. 지표가 전부 보합(0%대)이면 "오늘은 매크로 영향이 크지 않은 날"이라고 담백하게 말해도 된다.
        """.trimIndent()
    }
}
