package com.haky.edge.ai

import com.haky.edge.news.TargetPriceTrend

/**
 * 리레이팅/디레이팅 국면 감지 — 전부 룰 계산(LLM 없음, 환각 불가).
 *
 * 왜: AI·반도체·로봇·우주처럼 이익이 급변하는 종목은 트레일링 실적·과거 밴드 기준으로는
 * 항상 "역사적 고평가"로 나와 코멘트가 구조적으로 부정 편향된다. "과거 문법이 안 통하는
 * 국면"인지 여부를 계산으로 판정해 facts에 레이블로 박고, Claude는 그 프레임에 맞춰
 * 해석만 바꾼다(COMMON_RULES C11). 판정 근거도 함께 넘겨 사용자가 검증 가능하게 한다.
 *
 * 양방향 대칭: 리레이팅(과거 밴드 무력화)뿐 아니라 디레이팅 경계(싸 보여도 밸류 함정)도
 * 같은 구조로 감지 — 낙관 편향으로 뒤집히는 것을 막는다.
 */
object RegimeDetector {

    data class Regime(val label: String, val signals: List<String>)

    // 임계값(휴리스틱). 너무 민감하면 일반 종목까지 리레이팅 판정 → 2개 이상 동시 충족 요구로 방어.
    private const val NEAR_TARGET_RATIO = 0.95   // 주가 ≥ 목표가×0.95 = 근접/돌파
    private const val YOY_JUMP_PCT = 50.0        // 분기 순이익 YoY +50% 이상 = 이익 점프
    private const val YOY_DROP_PCT = -30.0       // YoY -30% 이하 = 이익 급감
    private const val BAND_TOP_PERCENTILE = 90   // PER 역사 밴드 백분위 상단
    private const val BAND_BOTTOM_PERCENTILE = 10 // PER 역사 밴드 백분위 하단(디레이팅 보강, 대칭)
    private const val MIN_SIGNALS = 2            // 판정 성립 최소 **실질** 신호 수(밴드 보강은 미포함)

    /**
     * 국면 판정. 리레이팅·디레이팅 신호를 각각 세어 [MIN_SIGNALS] 이상인 쪽을 반환.
     * 양쪽 다 성립하면(모순 데이터) 판정 보류 = null. 신호 부족도 null(일반 국면 — 레이블 자체를 생략).
     */
    fun detect(
        price: Long,
        consensusTarget: Long?,
        targetTrend: TargetPriceTrend?,
        quarterlyYoyPct: Double?,
        perPercentile: Int?,
    ): Regime? {
        val up = mutableListOf<String>()
        val down = mutableListOf<String>()

        // ① 주가 vs 컨센서스 목표가 — 주가가 목표가를 쫓아가는 게 아니라 목표가가 주가를 못 쫓아오는 상태.
        if (consensusTarget != null && consensusTarget > 0 && price > 0) {
            val ratio = price.toDouble() / consensusTarget
            if (ratio >= NEAR_TARGET_RATIO) {
                val pct = (ratio - 1) * 100
                up += if (pct >= 0) "주가가 컨센서스 목표가를 ${"%.1f".format(pct)}% 초과(목표가가 주가를 못 쫓아옴)"
                      else "주가가 컨센서스 목표가에 근접(${"%.1f".format(-pct)}% 이내)"
            }
        }

        // ② 목표가 추세 — 상향 반복은 리레이팅의 가장 직접적인 정황, 하향은 반대.
        if (targetTrend != null) {
            when (targetTrend.direction) {
                "상향" -> up += "컨센서스 목표가 ${targetTrend.daySpan}일간 +${"%.1f".format(targetTrend.changePct)}% 상향 추세"
                "하향" -> down += "컨센서스 목표가 ${targetTrend.daySpan}일간 ${"%.1f".format(targetTrend.changePct)}% 하향 추세"
            }
        }

        // ③ 분기 이익 방향 — 이익 점프면 과거 밴드의 분모가 무의미해진다.
        if (quarterlyYoyPct != null) {
            if (quarterlyYoyPct >= YOY_JUMP_PCT) up += "최근 분기 순이익 YoY +${"%.0f".format(quarterlyYoyPct)}% 급증"
            if (quarterlyYoyPct <= YOY_DROP_PCT) down += "최근 분기 순이익 YoY ${"%.0f".format(quarterlyYoyPct)}% 급감"
        }

        // ④ 역사 밴드 극단 — 판정 성립 여부를 **먼저** 실질 신호(①~③)만으로 확정한 뒤,
        //    성립한 쪽의 근거 표시만 보강한다(카운트 미포함). 기존엔 up 1개 + ④ = 2개로
        //    "2개 이상 동시 충족" 방어를 부스터가 조용히 무력화했다(O1). 하단 보강은 대칭용:
        //    상단 보강만 있으면 리레이팅 방향으로만 근거가 두터워지는 비대칭이 생긴다.
        val upOk = up.size >= MIN_SIGNALS
        val downOk = down.size >= MIN_SIGNALS
        if (perPercentile != null) {
            if (upOk && perPercentile >= BAND_TOP_PERCENTILE) {
                up += "PER 역사 밴드 최상단(백분위 ${perPercentile})인데도 위 신호 지속"
            }
            if (downOk && perPercentile <= BAND_BOTTOM_PERCENTILE) {
                down += "PER 역사 밴드 최하단(백분위 ${perPercentile})인데도 하향 신호 지속(밸류 함정 정황)"
            }
        }
        return when {
            upOk && downOk -> null // 모순 신호 — 판정 보류(억지 프레임 금지)
            upOk -> Regime("리레이팅 국면(과거 밴드 기준 무력화 가능성)", up)
            downOk -> Regime("디레이팅 경계(밸류 함정 가능성)", down)
            else -> null
        }
    }
}
