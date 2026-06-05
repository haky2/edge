package com.haky.edge.macro

import com.haky.edge.ai.ClaudeClient
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.MacroIndicator
import com.haky.edge.master.StockMaster
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

        val result = MacroImpact(
            date = today,
            comment = comment,
            indicators = indicators,
            holdings = holdingImpacts,
            watchlist = watchImpacts,
        )
        cache[cacheKey] = result
        return result
    }

    /** 종목 1개의 섹터를 결정하고 지표별 방향 신호를 계산한다. */
    private suspend fun buildStockImpact(code: String, indicators: List<MacroIndicator>): StockImpact {
        val name = master.search(code).firstOrNull { it.code == code }?.name ?: code
        // 1순위: 수동 오버라이드(정확). 없으면 KIS 업종명으로 자동 추론(best-effort).
        val sector = SECTOR_OVERRIDE[code]
            ?: runCatching { kis.getPrice(code).sectorName }.getOrNull()?.let { autoSector(it) }
        val sectorLabel = sector?.label ?: "기타"

        if (sector == null) {
            // 업종명으로도 추론 안 되는 종목 → 신호 없음("-"). 새 종목은 SECTOR_OVERRIDE에 추가.
            return StockImpact(code, name, sectorLabel, net = "-", signals = emptyList())
        }

        val sensitivities = SENSITIVITY[sector].orEmpty()
        val signals = sensitivities.mapNotNull { sens ->
            val ind = indicators.firstOrNull { it.key == sens.indicatorKey } ?: return@mapNotNull null
            val rate = ind.changeRate
            // 종목 영향 방향 = 민감도 부호 × 지표 등락 부호. 민감도 0(무관)이거나 지표 보합이면 0.
            val direction = when {
                sens.direction == 0 -> 0
                rate > 0.0 -> sens.direction
                rate < 0.0 -> -sens.direction
                else -> 0
            }
            MacroSignal(
                indicator = ind.label,
                changeRate = rate,
                direction = direction,
                note = sens.note,
            )
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

    // ── 도메인 매핑(여기를 고치면 영향 규칙이 바뀐다) ───────────────────────────

    /**
     * KIS 업종명(bstp_kor_isnm)으로 섹터를 추론한다. SECTOR_OVERRIDE 미매핑 종목 폴백용.
     * 업종명이 여러 섹터에 걸치는 경우(예: "전기·전자"에 반도체+가전 혼재)는 가장 넓은 섹터로 보수 매핑.
     */
    private fun autoSector(kisName: String): Sector? = when {
        kisName.contains("서비스")                         -> Sector.IT_SERVICE
        kisName.contains("전기가스") || kisName.contains("전력") -> Sector.POWER_EQUIP
        kisName.contains("철강") || kisName.contains("금속") || kisName.contains("전선") -> Sector.POWER_EQUIP
        kisName.contains("기계") || kisName.contains("조선") || kisName.contains("중공업") -> Sector.SHIPBUILDING
        // "운수장비"/"운송장비"는 자동차가 주력. 방산/조선 주요 종목은 SECTOR_OVERRIDE에 직접 매핑돼 여기 안 온다.
        kisName.contains("운수장비") || kisName.contains("운송장비") || kisName.contains("자동차") -> Sector.AUTOMOBILE
        kisName.contains("항공")                          -> Sector.DEFENSE
        kisName.contains("반도체")                         -> Sector.SEMICONDUCTOR
        kisName.contains("전기·전자") || kisName.contains("전자") -> Sector.ELECTRONICS
        else                                             -> null
    }

    /** 우리 분류 섹터. 한투 업종명이 거칠어(예: '전기·전자'에 반도체+가전 혼재) 매크로 민감도용으로 따로 둔다. */
    enum class Sector(val label: String) {
        SEMICONDUCTOR("반도체"),
        SHIPBUILDING("조선"),
        DEFENSE("방산"),
        POWER_EQUIP("전력기기"),
        IT_SERVICE("IT서비스"),
        ELECTRONICS("전자/가전"),
        AUTOMOBILE("자동차"),
    }

    /** 섹터 × 지표 민감도 1건. direction: +1 = 지표 상승이 해당 섹터에 우호, -1 = 부담, 0 = 무관. */
    private data class Sensitivity(val indicatorKey: String, val direction: Int, val note: String)

    companion object {
        // 영향 방향 계산에 쓰는 지표. fear_greed는 방향 계산 제외(맥락용).
        private val IMPACT_INDICATORS = listOf("usdkrw", "nasdaq", "crude", "copper", "rate3y")

        // 종목코드 → 우리 섹터(관심종목 11개 기준 오버라이드). 새 종목은 여기에 추가.
        private val SECTOR_OVERRIDE = mapOf(
            "000660" to Sector.SEMICONDUCTOR, // SK하이닉스
            "005930" to Sector.SEMICONDUCTOR, // 삼성전자
            "329180" to Sector.SHIPBUILDING,  // HD현대중공업
            "047810" to Sector.DEFENSE,       // 한국항공우주
            "012450" to Sector.DEFENSE,       // 한화에어로스페이스
            "267260" to Sector.POWER_EQUIP,   // HD현대일렉트릭
            "001440" to Sector.POWER_EQUIP,   // 대한전선
            "062040" to Sector.POWER_EQUIP,   // 산일전기
            "018260" to Sector.IT_SERVICE,    // 삼성에스디에스
            "307950" to Sector.IT_SERVICE,    // 현대오토에버
            "066570" to Sector.ELECTRONICS,   // LG전자
            "005380" to Sector.AUTOMOBILE,    // 현대차
            "000270" to Sector.AUTOMOBILE,    // 기아
        )

        // 섹터별 매크로 민감도. note 는 근거 한 줄(앱·Claude facts에 그대로 노출).
        private val SENSITIVITY = mapOf(
            Sector.SEMICONDUCTOR to listOf(
                Sensitivity("usdkrw", +1, "원화 약세 → 수출 채산성 개선"),
                Sensitivity("nasdaq", +1, "미국 빅테크·AI 반도체와 주가 동조"),
                Sensitivity("crude",  -1, "유가 상승 → 인플레·금리 우려 → 성장주 부담"),
                Sensitivity("rate3y", -1, "금리 상승 → 성장주 밸류에이션 할인율 확대"),
            ),
            Sector.SHIPBUILDING to listOf(
                Sensitivity("usdkrw", +1, "수주 대금 달러 결제 → 원화 약세 수혜"),
                Sensitivity("crude",  +1, "유가 상승 → 유조선·LNG선 발주 수요 증가"),
                Sensitivity("rate3y", -1, "금리 상승 → 선박금융 조달 비용 증가, 선주 투자 부담"),
            ),
            Sector.DEFENSE to listOf(
                Sensitivity("usdkrw", +1, "방산 수출 비중 → 원화 약세 우호"),
            ),
            Sector.POWER_EQUIP to listOf(
                Sensitivity("usdkrw", +1, "변압기 등 수출 비중 → 원화 약세 우호"),
                Sensitivity("nasdaq", +1, "미국 데이터센터·전력 인프라 투자 테마 연동"),
                Sensitivity("crude",  +1, "유가 상승 → 에너지 전환·신재생 투자 가속화"),
                Sensitivity("copper", -1, "구리 상승 → 변압기·전선 주요 원재료 원가 부담"),
                Sensitivity("rate3y", -1, "금리 상승 → 인프라 투자 할인율 상승, 밸류에이션 부담"),
            ),
            Sector.IT_SERVICE to listOf(
                Sensitivity("usdkrw", 0, "내수 매출 중심 → 환율 영향 제한적"),
                Sensitivity("rate3y", -1, "금리 상승 → 성장주 밸류에이션 부담"),
            ),
            Sector.ELECTRONICS to listOf(
                Sensitivity("usdkrw", +1, "수출 비중 높아 원화 약세 우호(수입 부품이 일부 상쇄)"),
                Sensitivity("crude",  -1, "유가 상승 → 물류·부품 운반비 원가 부담"),
                Sensitivity("copper", -1, "구리 상승 → PCB·배선 부품 원가 부담"),
            ),
            Sector.AUTOMOBILE to listOf(
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
            5. 형식: 불릿·번호 목록 금지. 마크다운 볼드 헤더(**제목**) 금지. 이야기처럼 흐르는 연속 문단으로.
            6. 지표가 전부 보합(0%대)이면 "오늘은 매크로 영향이 크지 않은 날"이라고 담백하게 말해도 된다.
        """.trimIndent()
    }
}
