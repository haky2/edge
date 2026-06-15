package com.haky.edge.slack

import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.KisClient
import com.haky.edge.master.StockMaster
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 신호 알림 푸시(S3) — 관심종목을 순회하며 의미 있는 수급 신호가 *새로* 발생했을 때만 Slack 채널에 알린다.
 * 무료 푸시 대체의 메인 가치([[edge-slack-slices]] S3). 앱은 pull, 이건 백엔드가 능동 push.
 *
 * S3a 범위: 신호 1종 — **외국인/기관 N일 연속 순매수**. 기존 `/investor` 데이터만 사용(새 API 0).
 *   나머지 조건(밸류밴드 저평가·신규 공시·목표가 도달)은 같은 토대 위에 S3b에서 추가.
 *
 * 도배 방지(핵심): 종목+신호종류별 **마지막 발화 시점의 "streak 시작일"**을 영속 저장한다.
 *   - 외인 3일 연속(시작일 D1)에 발화 → 다음날 4일 연속(여전히 시작일 D1) → 같은 시작일 → skip.
 *   - 연속이 끊겼다가 다시 시작(다른 시작일) → 발화. 한 streak당 정확히 1번만 알린다.
 *   - 저장 위치: {DATA_DIR}/signal_state.json — `.cache/`(날짜 자동 stale)와 달리 영속(삭제 대상 아님).
 *
 * 발송: 발생한 신호를 한 메시지로 묶어 한 번만 postMessage(종목마다 따로 보내 도배하지 않는다).
 *   채널/토큰 미설정(로컬)이면 SlackClient가 no-op — 평가 로직은 그대로 돌아 라우트 응답으로 검증 가능.
 */
class SignalService(
    private val slack: SlackClient,
    private val kis: KisClient,
    private val master: StockMaster,
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

    /** 발화한 단일 신호. */
    private data class Signal(
        val code: String,
        val name: String,
        val type: SignalType,
        val streak: Int,
        val streakStartDate: String,   // 디듀프 키 — 이 streak가 시작된 거래일
        val cumulative: Long,          // streak 동안 누적 순매수 수량
    )

    private enum class SignalType(val label: String) { FOREIGN("외국인"), INSTITUTION("기관") }

    /** 관심종목 순회 → 연속 순매수 평가 → 디듀프 통과분만 한 메시지로 발송. */
    suspend fun scan(): ScanResult {
        val state = loadState()
        val fired = mutableListOf<Signal>()

        for (code in codes) {
            // 최신이 앞. 연속 판정엔 넉넉히. 미확정(전부 0)일은 getInvestorFlow가 이미 제외함.
            val flows = runCatching { kis.getInvestorFlow(code, days = 10) }.getOrNull()
            if (flows.isNullOrEmpty()) continue
            val name = runCatching { master.search(code).firstOrNull { it.code == code }?.name }.getOrNull() ?: code

            for (type in SignalType.entries) {
                val sig = evalStreak(code, name, type, flows) ?: continue
                val key = "${sig.code}:${sig.type.name}"
                // 같은 streak(같은 시작일)로 이미 발화했으면 skip. 다른 시작일이면 새 신호 → 발화.
                if (state[key] == sig.streakStartDate) continue
                state[key] = sig.streakStartDate
                fired += sig
            }
        }

        var posted = false
        if (fired.isNotEmpty()) {
            posted = slack.postMessage(signalChannel, formatMessage(fired))
            saveState(state)  // 발송 시도 후에만 영속(no-op이어도 디듀프는 진행 — 같은 신호 반복 평가 방지)
        }
        return ScanResult(scanned = codes.size, fired = fired.map { describe(it) }, posted = posted)
    }

    /** 한 종목·한 신호종류의 연속 순매수 streak를 평가. THRESHOLD 미만이면 null. */
    private fun evalStreak(code: String, name: String, type: SignalType, flows: List<InvestorFlow>): Signal? {
        val value: (InvestorFlow) -> Long = when (type) {
            SignalType.FOREIGN -> { f -> f.foreign }
            SignalType.INSTITUTION -> { f -> f.institution }
        }
        var streak = 0
        var cumulative = 0L
        for (f in flows) {              // 최신부터
            if (value(f) > 0) { streak++; cumulative += value(f) } else break
        }
        if (streak < THRESHOLD) return null
        val streakStartDate = flows[streak - 1].date   // 연속의 가장 오래된 거래일
        return Signal(code, name, type, streak, streakStartDate, cumulative)
    }

    private fun describe(s: Signal): String =
        "${s.name}(${s.code}) ${s.type.label} ${s.streak}일 연속 순매수(누적 +${"%,d".format(s.cumulative)}주)"

    /** 여러 신호를 한 메시지로. 헤더 + 종목별 한 블록. (mrkdwn 정규화는 SlackClient가 일괄 처리) */
    private fun formatMessage(signals: List<Signal>): String = buildString {
        appendLine("🔔 *수급 신호* — 관심종목 연속 순매수")
        appendLine()
        signals.forEach { s ->
            appendLine("📈 *${s.name}* (${s.code})")
            appendLine("${s.type.label} *${s.streak}일 연속* 순매수 · 누적 +${"%,d".format(s.cumulative)}주")
            appendLine()
        }
        append("_장 마감 후 확정 수급 기준 · 참고용_")
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
        private const val THRESHOLD = 3   // N일 연속 순매수 = 신호
    }
}
