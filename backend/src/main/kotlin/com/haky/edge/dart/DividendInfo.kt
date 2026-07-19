package com.haky.edge.dart

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 배당 정보 — DART 배당 사항(alotMatter)에서 추출한 보통주 주당 현금배당금 추이·시가배당률·배당성향.
 *
 * 배당락일(배당기준일)은 alotMatter에 없고, 2023년 상법 개정 이후 "배당액 결정 후 기준일"로
 * 바꾼 회사가 늘어 날짜가 불규칙해졌다 → 확정 날짜는 다루지 않고 결산월(stlm_dt)만 사실로 제공한다.
 * 예상 수익률은 최신 주당배당금 ÷ 현재가로 FactsText에서 계산한다(당시 시가배당률과 별개).
 *
 * DART 특성: alotMatter를 bsns_year=Y로 조회하면 thstrm=Y·frmtrm=Y-1·lwfr=Y-2이지만,
 * 사업보고서 제출 직후엔 당해(thstrm) 배당이 아직 구조화 데이터에 안 실릴 수 있다(회사별 시차).
 * → 최신 연도부터 프로브해 보통주 주당배당금 실값이 있는 첫 연도를 채택한다(DartClient).
 */
data class DividendInfo(
    val fiscalYear: Int,             // 확정 사업연도(stlm_dt 기준, 예 2025)
    val dpsThis: Long,               // 주당 현금배당금(보통주) — 확정 사업연도
    val dpsPrev: Long? = null,       // 전년
    val dpsPrev2: Long? = null,      // 전전년
    val yieldPctAtRecord: Double? = null, // 당시 현금배당수익률(%) — 배당 시점 종가 기준(과거값)
    val payoutPct: Double? = null,   // 현금배당성향(%)
    val epsThis: Long? = null,       // 주당순이익(연결, 원) — 참고
    val settleDate: String? = null,  // 결산기준일(stlm_dt, 예 2025-12-31)
) {
    /** 최신·전년 주당배당금이 모두 있으면 증감률(%). 삭감/증액 방향 판단용. */
    val dpsYoyPct: Double?
        get() = dpsPrev?.takeIf { it > 0L }?.let { (dpsThis - it).toDouble() / it * 100 }
}

object DividendMath {

    /**
     * 오늘 기준 사업보고서가 제출됐을 최신 사업연도. 사업보고서 법정 마감 = 익년 3/31.
     * 4월 이후면 전년도(보고서 제출됨), 그 전이면 전전년도가 최신 확정분.
     */
    fun latestFiledYear(today: LocalDate): Int =
        if (today.monthValue >= 4) today.year - 1 else today.year - 2

    private fun norm(s: String?) = (s ?: "").replace(" ", "")
    private fun amount(s: String?): Long? =
        norm(s).replace(",", "").takeIf { it.isNotEmpty() && it != "-" }?.toLongOrNull()
    private fun pct(s: String?): Double? =
        norm(s).replace(",", "").takeIf { it.isNotEmpty() && it != "-" }?.toDoubleOrNull()

    /**
     * alotMatter 응답 행에서 보통주 배당 정보 추출. 보통주 주당배당금이 없거나 0이면 null
     * (무배당 회사 자동 배제 — 우선주만 있는 행과 섞이지 않도록 보통주만 취한다).
     */
    fun extract(rows: List<DartAlotRow>): DividendInfo? {
        fun common(labelExact: String) = rows.firstOrNull {
            norm(it.se) == labelExact && norm(it.stockKind).contains("보통")
        }
        // 연결 우선(없으면 별도)로 라벨 앞 접두사를 무시하고 매칭.
        fun consolidatedFirst(labelContains: String) =
            rows.firstOrNull { norm(it.se).contains(labelContains) && norm(it.se).contains("연결") }
                ?: rows.firstOrNull { norm(it.se).contains(labelContains) }

        val dpsRow = common("주당현금배당금(원)") ?: return null
        val dpsThis = amount(dpsRow.thisTerm)?.takeIf { it > 0L } ?: return null

        val fiscalYear = dpsRow.settleDate?.take(4)?.toIntOrNull()
            ?: LocalDate.now().year

        val yieldRow = rows.firstOrNull {
            norm(it.se).contains("현금배당수익률") && norm(it.stockKind).contains("보통")
        }

        return DividendInfo(
            fiscalYear = fiscalYear,
            dpsThis = dpsThis,
            dpsPrev = amount(dpsRow.prevTerm),
            dpsPrev2 = amount(dpsRow.prev2Term),
            yieldPctAtRecord = pct(yieldRow?.thisTerm),
            payoutPct = pct(consolidatedFirst("현금배당성향")?.thisTerm),
            epsThis = amount(consolidatedFirst("주당순이익")?.thisTerm),
            settleDate = dpsRow.settleDate,
        )
    }
}

// ── DART alotMatter(배당에 관한 사항) 응답 모델 ────────────────────────────────

@Serializable
data class DartAlotResponse(
    val status: String = "",
    val message: String = "",
    val list: List<DartAlotRow>? = null,
)

@Serializable
data class DartAlotRow(
    @SerialName("se")        val se: String = "",         // 항목명(예: 주당 현금배당금(원))
    @SerialName("stock_knd") val stockKind: String = "",  // 보통주/우선주(앞 공백 붙는 경우 있음)
    @SerialName("thstrm")    val thisTerm: String = "",    // 당기
    @SerialName("frmtrm")    val prevTerm: String = "",    // 전기
    @SerialName("lwfr")      val prev2Term: String = "",   // 전전기
    @SerialName("stlm_dt")   val settleDate: String? = null, // 결산기준일(YYYY-MM-DD)
)

/** GET /dividend/{code} 응답 DTO. SharedLogic model/DividendCard.kt와 필드명·타입 동일. */
@Serializable
data class DividendCard(
    val code: String,
    val fiscalYear: Int,
    val dpsThis: Long,
    val dpsPrev: Long? = null,
    val dpsPrev2: Long? = null,
    val dpsYoyPct: Double? = null,
    val yieldPctAtRecord: Double? = null,
    val payoutPct: Double? = null,
    val settleMonth: Int? = null,
    val expectedYieldPct: Double? = null,
)
