package com.haky.edge.slack

import com.haky.edge.ai.ValuationBandService
import com.haky.edge.dart.DartClient
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.master.StockMaster
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 신호 알림 푸시(S3) — 관심종목을 순회하며 의미 있는 신호가 *새로* 발생했을 때만 Slack 채널에 알린다.
 * 무료 푸시 대체의 메인 가치([[edge-slack-slices]] S3). 앱은 pull, 이건 백엔드가 능동 push.
 *
 * 신호 3종(전부 기존 데이터 재사용, 새 API 0):
 *  1. 연속 순매수 — 외국인/기관 N일 연속(/investor).
 *  2. 신규 공시 — 중요 유형(수주·증자·자사주·합병·실적 등) DART 공시(/dart).
 *  3. 밸류밴드 저평가 진입 — 역사적 PER 하위 구간 진입(valuation-band).
 *
 * 도배 방지(핵심) — 신호마다 디듀프 패턴이 다르고, 상태를 {DATA_DIR}/signal_state.json에 영속한다
 *   (`.cache/`(날짜 자동 stale)와 달리 삭제 대상 아님. GCS edge-app-data 볼륨이라 콜드 스타트에도 유지):
 *   - 순매수: key="code:FOREIGN" → 마지막 발화 streak 시작일. 같은 streak는 1번, 끊겼다 재시작하면 재발화.
 *   - 공시:   key="DISC:code"    → 이미 알린 rceptNo 목록(최근 N개). 새 접수번호만 발화.
 *   - 밸류:   key="VALUE:code"   → 마지막 본 perLabel. "저평가 아님→저평가" 전이일 때만 발화(상태는 매번 갱신).
 *
 * 발송: 발생 신호를 종류별 섹션으로 묶어 한 메시지로 한 번만 발송(종목마다 따로 보내 도배하지 않는다).
 *   채널/토큰 미설정(로컬)이면 SlackClient가 no-op — 평가 로직은 그대로 돌아 라우트 응답으로 검증 가능.
 */
class SignalService(
    private val slack: SlackClient,
    private val kis: KisClient,
    private val master: StockMaster,
    private val dart: DartClient,
    private val valuationBand: ValuationBandService,
    private val signalChannel: String,
    private val codes: List<String>,
) {
    private val dataDir = File(System.getenv("DATA_DIR") ?: ".data").also { it.mkdirs() }
    private val stateFile = File(dataDir, "signal_state.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    /** 한 번의 스캔 결과(라우트 응답·로깅용). fired = 이번에 새로 발화한 신호 설명들. */
    @Serializable
    data class ScanResult(
        val scanned: Int,
        val fired: List<String>,
        val posted: Boolean,
    )

    private data class FlowSignal(val code: String, val name: String, val type: FlowType, val streak: Int, val startDate: String, val cumulative: Long)
    private data class DisclosureSignal(val code: String, val name: String, val title: String, val url: String, val rceptNo: String)
    private data class ValuationSignal(val code: String, val name: String, val perCurrent: Double, val percentile: Int)

    private enum class FlowType(val label: String) { FOREIGN("외국인"), INSTITUTION("기관") }

    /** 관심종목 순회 → 3종 신호 평가 → 디듀프 통과분만 한 메시지로 발송. */
    suspend fun scan(): ScanResult {
        val state = loadState()
        val flowSignals = mutableListOf<FlowSignal>()
        val disclosureSignals = mutableListOf<DisclosureSignal>()
        val valuationSignals = mutableListOf<ValuationSignal>()

        for (code in codes) {
            val name = runCatching { master.search(code).firstOrNull { it.code == code }?.name }.getOrNull() ?: code

            // 1. 연속 순매수 — 미확정(전부 0)일은 getInvestorFlow가 이미 제외함.
            runCatching { kis.getInvestorFlow(code, days = 10) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { flows ->
                for (type in FlowType.entries) {
                    val sig = evalFlow(code, name, type, flows) ?: continue
                    val key = "$code:${type.name}"
                    if (state[key] == sig.startDate) continue   // 같은 streak → skip
                    state[key] = sig.startDate
                    flowSignals += sig
                }
            }

            // 2. 신규 공시 — 중요 유형만, 이미 알린 rceptNo는 skip.
            runCatching { dart.getDisclosures(code, days = 2) }.getOrNull()?.let { discs ->
                val key = "DISC:$code"
                val seen = state[key]?.split(",")?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
                val fresh = mutableListOf<DisclosureSignal>()
                for (d in discs) {
                    if (!isImportant(d.reportName)) continue
                    val rceptNo = extractRceptNo(d.url) ?: continue
                    if (rceptNo in seen) continue
                    seen += rceptNo
                    fresh += DisclosureSignal(code, name, d.reportName, d.url, rceptNo)
                }
                if (fresh.isNotEmpty()) {
                    disclosureSignals += fresh
                    // 최근 50개만 유지(무한 증가 방지). 접수번호는 시간순 증가라 큰 값이 최신.
                    state[key] = seen.sortedDescending().take(50).joinToString(",")
                }
            }

            // 3. 밸류밴드 저평가 진입 — 상태 전이(저평가 아님→저평가)일 때만. 상태는 매 스캔 갱신.
            runCatching { valuationBand.getValuationBand(code) }.getOrNull()
                ?.takeIf { it.perPercentile >= 0 }
                ?.let { vb ->
                    val key = "VALUE:$code"
                    val prev = state[key]
                    if (vb.perLabel == LOW_VALUE_LABEL && prev != LOW_VALUE_LABEL) {
                        valuationSignals += ValuationSignal(code, name, vb.perCurrent, vb.perPercentile)
                    }
                    state[key] = vb.perLabel   // 발화 여부와 무관하게 현재 상태 기록(전이 감지용)
                }
        }

        val descriptions = flowSignals.map { "${it.name}(${it.code}) ${it.type.label} ${it.streak}일 연속 순매수" } +
            disclosureSignals.map { "${it.name}(${it.code}) 공시: ${it.title}" } +
            valuationSignals.map { "${it.name}(${it.code}) 밸류 저평가(PER 하위 ${it.percentile}%)" }

        var posted = false
        if (descriptions.isNotEmpty()) {
            posted = slack.postMessage(signalChannel, formatMessage(flowSignals, disclosureSignals, valuationSignals))
        }
        saveState(state)   // 밸류밴드 상태 추적 위해 항상 저장(발화 없어도 전이 기준 갱신)
        return ScanResult(scanned = codes.size, fired = descriptions, posted = posted)
    }

    /** 한 종목·한 신호종류의 연속 순매수 streak. THRESHOLD 미만이면 null. */
    private fun evalFlow(code: String, name: String, type: FlowType, flows: List<InvestorFlow>): FlowSignal? {
        val value: (InvestorFlow) -> Long = when (type) {
            FlowType.FOREIGN -> { f -> f.foreign }
            FlowType.INSTITUTION -> { f -> f.institution }
        }
        var streak = 0
        var cumulative = 0L
        for (f in flows) {              // 최신부터
            if (value(f) > 0) { streak++; cumulative += value(f) } else break
        }
        if (streak < FLOW_THRESHOLD) return null
        return FlowSignal(code, name, type, streak, flows[streak - 1].date, cumulative)
    }

    /** 중요 공시 여부 — 주가 트리거가 되는 유형 키워드 화이트리스트. 단순 정정·경미한 공시는 제외. */
    private fun isImportant(reportName: String): Boolean =
        IMPORTANT_KEYWORDS.any { reportName.contains(it) }

    /** DART url(...rcpNo=20260101000123)에서 접수번호 추출. */
    private fun extractRceptNo(url: String): String? =
        Regex("""rcpNo=(\d+)""").find(url)?.groupValues?.get(1)

    /** 종류별 섹션으로 한 메시지 구성. (mrkdwn 정규화는 SlackClient가 일괄 처리) */
    private fun formatMessage(
        flows: List<FlowSignal>,
        discs: List<DisclosureSignal>,
        vals: List<ValuationSignal>,
    ): String = buildString {
        appendLine("🔔 *오늘의 관심종목 신호*")
        appendLine()
        if (flows.isNotEmpty()) {
            appendLine("📈 *연속 순매수*")
            flows.forEach { s ->
                appendLine("• *${s.name}* (${s.code}) — ${s.type.label} *${s.streak}일 연속* · 누적 +${"%,d".format(s.cumulative)}주")
            }
            appendLine()
        }
        if (discs.isNotEmpty()) {
            appendLine("📋 *신규 공시*")
            discs.forEach { d ->
                appendLine("• *${d.name}* (${d.code}) — ${d.title}")
                appendLine("  ${d.url}")
            }
            appendLine()
        }
        if (vals.isNotEmpty()) {
            appendLine("💰 *밸류에이션 — 역사적 하단권 진입*")
            vals.forEach { v ->
                appendLine("• *${v.name}* (${v.code}) — PER *${"%.1f".format(v.perCurrent)}배* (역사적 하위 ${v.percentile}%)")
            }
            appendLine()
        }
        append("_장 마감 후 확정 데이터 기준 · 참고용_")
    }

    private fun loadState(): MutableMap<String, String> {
        if (!stateFile.exists()) return mutableMapOf()
        return runCatching {
            json.decodeFromString(mapSerializer, stateFile.readText()).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun saveState(state: Map<String, String>) {
        runCatching { stateFile.writeText(json.encodeToString(mapSerializer, state)) }
    }

    companion object {
        private const val FLOW_THRESHOLD = 3        // N일 연속 순매수 = 신호
        private const val LOW_VALUE_LABEL = "역사적 하단권"
        // 주가 트리거가 되는 중요 공시 유형(부분 일치). 단순 정정·임원변경 등 경미한 건 제외된다.
        private val IMPORTANT_KEYWORDS = listOf(
            "공급계약", "수주", "단일판매",            // 수주·계약
            "유상증자", "무상증자", "감자",            // 증자·감자
            "자기주식", "자사주",                       // 자사주
            "합병", "회사분할", "영업양수", "영업양도",  // 지배구조
            "영업실적", "잠정실적", "매출액또는손익구조", // 실적
            "전환사채", "신주인수권부사채", "교환사채",   // 메자닌
            "최대주주변경", "경영권", "공개매수",         // 지분(소유변동신고서 같은 노이즈 제외)
            "상장폐지", "관리종목", "거래정지",          // 상장 리스크
            "주식분할", "주식병합", "현금ㆍ현물배당",     // 주주환원·자본
        )
    }
}
