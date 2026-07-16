package com.haky.edge.analysis

import kotlin.math.roundToLong

/**
 * 계좌의 세제 성격. 프리셋 이름 정확 매칭으로만 판정한다(커스텀 이름은 추론하지 않음 —
 * AccountRepository.presetHorizon과 같은 원칙). 오분류 방지는 카드가 계좌별 적용
 * 타입을 노출하는 것으로 한다.
 */
enum class AccountTaxType { GENERAL, ISA, PENSION }

/** TaxEngine 입력 1행 — holding + 계좌 타입 + 현재가를 호출부(뷰)가 조인해서 만든다. */
data class TaxablePosition(
    val code: String,            // "005930" 또는 "US:NAS:AAPL"
    val taxType: AccountTaxType,
    val avgPrice: Double,
    val qty: Double,
    val currentPrice: Double,
)

/**
 * "지금 전량 매도하면" 시나리오의 세후 손익. 모든 금액은 원 단위 반올림.
 * 연금(과세이연) 포지션은 세후 숫자에 합산하지 않고 분리 표시한다 — 인출 시점 과세라
 * "세후 확정" 숫자를 만들 수 없기 때문(스펙: 세액 창작 금지).
 */
data class AfterTaxSummary(
    val taxableGross: Long,      // 과세 대상(일반+ISA) 세전 평가손익 합
    val transactionTax: Long,    // 국내 매도 시 증권거래세(매도가액 × 0.2%) — 손실 종목도 부과
    val overseasTax: Long,       // 해외 양도세(손익통산 → 250만 공제 → 22%/27.5%)
    val netPnl: Long,            // taxableGross - transactionTax - overseasTax
    val pensionGross: Long,      // 연금 계좌 평가손익(계산 제외, 안내용)
    val hasPension: Boolean,     // 연금 포지션 존재 → "인출 시 과세" 안내 노출
    val hasIsa: Boolean,         // ISA 포지션 존재 → "매매차익 기준 일반과 동일" 안내 노출
    val hasOverseas: Boolean,    // 해외 포지션 존재(현재 앱에선 입력 불가 — 방어적 지원)
)

/**
 * 세후 실질 손익 간이 계산 (경계·면책 정본: docs/tax-model-spec.md).
 *
 * 계산하는 것: 오늘 전량 매도 가정 시 증권거래세(국내) + 해외 양도세(250만 공제·통산).
 * 계산하지 않는 것(스펙 면책 목록): 배당·이자 소득세, 대주주 양도세, 250만 공제의 연간
 * 소진분, ETF 거래세 면제, 수수료·환전, 연금 인출 세액.
 *
 * 세율 상수는 2026-07 현행 — 개정 시 이 파일과 스펙의 출처 절을 함께 갱신한다.
 */
object TaxEngine {

    /** 증권거래세 2026: 코스피 0.05%+농특세 0.15% = 코스닥 0.20%와 동률 → 시장 구분 불필요. */
    const val TRANSACTION_TAX_RATE = 0.002

    /** 해외주식 양도소득 기본공제(연 250만, 인당·연간 1회). 올해 실현분을 앱이 모르므로 전액 가용 가정. */
    const val OVERSEAS_DEDUCTION = 2_500_000.0

    /** 해외 양도세율(지방세 포함): 과세표준 3억 이하 22%, 초과분 27.5%. */
    const val OVERSEAS_RATE = 0.22
    const val OVERSEAS_RATE_HIGH = 0.275
    const val OVERSEAS_HIGH_THRESHOLD = 300_000_000.0

    fun isOverseas(code: String): Boolean = code.startsWith("US:")

    /**
     * 계좌 이름 → 세제 타입. 프리셋 정확 매칭만(커스텀은 GENERAL — 스펙의 오분류 노출 원칙).
     * 프리셋 문자열은 AccountManagementView(iOS)·Android 관리 화면과 동일해야 한다.
     */
    fun taxTypeOf(accountName: String): AccountTaxType = when (accountName) {
        "ISA" -> AccountTaxType.ISA
        "IRP개인연금", "퇴직연금" -> AccountTaxType.PENSION
        else -> AccountTaxType.GENERAL
    }

    fun compute(positions: List<TaxablePosition>): AfterTaxSummary {
        val (pension, taxable) = positions.partition { it.taxType == AccountTaxType.PENSION }

        // 연금: 과세이연 — 평가손익만 집계, 세후 숫자에서 제외.
        val pensionGross = pension.sumOf { it.pnl() }

        // 국내(일반+ISA): 차익 비과세, 거래세는 매도가액 전체 기준(손실 종목도 부과).
        val domestic = taxable.filterNot { isOverseas(it.code) }
        val transactionTax = domestic.sumOf { it.currentPrice * it.qty } * TRANSACTION_TAX_RATE

        // 해외(일반 계좌 — ISA·연금의 해외 개별주는 현실 불가, 방어적으로 ISA는 여기 합류):
        // 보유 종목 손익통산 → 250만 공제 → 3억 이하 22%·초과 27.5%. 통산 음수면 세금 0.
        val overseas = taxable.filter { isOverseas(it.code) }
        val overseasNetGain = overseas.sumOf { it.pnl() }
        val overseasTaxBase = (overseasNetGain - OVERSEAS_DEDUCTION).coerceAtLeast(0.0)
        val overseasTax = if (overseasTaxBase <= OVERSEAS_HIGH_THRESHOLD) {
            overseasTaxBase * OVERSEAS_RATE
        } else {
            OVERSEAS_HIGH_THRESHOLD * OVERSEAS_RATE +
                (overseasTaxBase - OVERSEAS_HIGH_THRESHOLD) * OVERSEAS_RATE_HIGH
        }

        val taxableGross = taxable.sumOf { it.pnl() }
        return AfterTaxSummary(
            taxableGross = taxableGross.roundToLong(),
            transactionTax = transactionTax.roundToLong(),
            overseasTax = overseasTax.roundToLong(),
            netPnl = (taxableGross - transactionTax - overseasTax).roundToLong(),
            pensionGross = pensionGross.roundToLong(),
            hasPension = pension.isNotEmpty(),
            hasIsa = taxable.any { it.taxType == AccountTaxType.ISA },
            hasOverseas = overseas.isNotEmpty(),
        )
    }

    private fun TaxablePosition.pnl(): Double = (currentPrice - avgPrice) * qty
}
