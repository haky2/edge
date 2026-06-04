package com.haky.edge.analysis

import com.haky.edge.model.InvestorFlow
import com.haky.edge.model.Quote

/** 52주 범위 기준 현재가 위치(계산 기반, LLM 없음). */
data class PriceContext(
    val pctInRange52w: Double,  // 0=52주 저점, 100=52주 고점
    val pctFromHigh52w: Double, // 52주 고점 대비 %(보통 음수)
    val pctFromLow52w: Double,  // 52주 저점 대비 %(보통 양수)
)

/** 한 투자자의 최근 연속 순매수/순매도 추세 요약. */
data class FlowStreak(
    val investor: String, // "외국인" | "기관"
    val days: Int,        // 같은 방향 연속 일수
    val net: Long,        // 그 연속 구간 누적 순매수 수량(부호 포함)
    val buying: Boolean,  // true=순매수 연속, false=순매도 연속
)

/**
 * 종목 상세 "지표 해석 ① 계산 기반". 이미 받은 Quote/수급으로 즉시 계산한다(외부 호출·LLM 없음).
 * 여기선 **사실·위치만** 계산한다 — "그래서 사라/팔라"는 판단은 ②(Claude) 몫이다(경계 유지).
 */
object StockAnalysis {

    /** 52주 범위 내 위치·고저 대비. 데이터가 비정상(고저 0 또는 범위 0)이면 null. */
    fun priceContext(q: Quote): PriceContext? {
        val range = q.high52w - q.low52w
        if (q.high52w <= 0 || range <= 0) return null
        return PriceContext(
            pctInRange52w = (q.price - q.low52w).toDouble() / range * 100,
            pctFromHigh52w = (q.price - q.high52w).toDouble() / q.high52w * 100,
            pctFromLow52w = if (q.low52w > 0) (q.price - q.low52w).toDouble() / q.low52w * 100 else 0.0,
        )
    }

    /**
     * 외국인·기관·개인의 최근 연속 추세(최신일 기준). flows 는 최신일이 앞.
     * 개인은 보통 외인+기관의 거울상이라 신호 가치는 낮지만, "개인 연속 순매수=과열" 맥락도 있어 함께 둔다.
     */
    fun flowStreaks(flows: List<InvestorFlow>): List<FlowStreak> = listOfNotNull(
        streak("외국인", flows.map { it.foreign }),
        streak("기관", flows.map { it.institution }),
        streak("개인", flows.map { it.individual }),
    )

    /**
     * 최신일의 방향(순매수/순매도)으로 시작해, 같은 방향이 이어지는 연속 일수와 누적량을 센다.
     * 0(중립)이나 방향이 바뀌면 거기서 끊는다. 최신일이 0이면 추세 없음(null).
     */
    private fun streak(name: String, values: List<Long>): FlowStreak? {
        val first = values.firstOrNull() ?: return null
        if (first == 0L) return null
        val buying = first > 0
        var days = 0
        var net = 0L
        for (v in values) {
            if (v == 0L || (v > 0) != buying) break
            days++
            net += v
        }
        return FlowStreak(name, days, net, buying)
    }
}
