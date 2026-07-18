package com.haky.edge.dart

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 제조업(수주산업) 선행지표 — 분기 재무제표에서 추출한 잔액 추이.
 *
 * 계약부채(고객에게 미리 받은 수주 계약금)는 조선·방산·전력기기 같은 수주산업에서
 * 수주잔고의 근사 지표다: 쌓이면 일감이 늘고 있다는 신호, 소진되면 매출로 인식 중이거나
 * 신규 수주가 둔한 신호. 재고자산·매출채권은 매출 증가 속도와 대조해 수요 둔화·회수 리스크를
 * 가리는 재료. DART 표준 재무 API(fnlttSinglAcnt)에는 이 계정들이 없어 전체 계정
 * API(fnlttSinglAcntAll)에서 계정과목 매핑으로 추출한다 — 회사별 계정명 차이는
 * 표준 태그(account_id)+이름 폴백의 2단 매칭으로 흡수.
 */

/** 분기말 잔액 스냅샷 1건(단위 원). null = 해당 계정 결측(회사별 계정 체계 차이). */
data class LeadingQuarter(
    val year: Int,
    val quarter: Int,                        // 1~4 (2=반기보고서, 4=사업보고서의 기말 잔액)
    val inventories: Long? = null,           // 재고자산
    val contractLiabilities: Long? = null,   // 계약부채 유동+비유동 합(없으면 선수금 폴백)
    val contractLiabIsAdvance: Boolean = false, // true = 계약부채 계정이 없어 선수금으로 대체
    val tradeReceivables: Long? = null,      // 매출채권
    val revenueCum: Long? = null,            // 매출액(손익계산서, 연초부터 누적)
) {
    /** 재무상태표 지표가 하나라도 있어야 추세 시리즈에 쓸 수 있는 분기다. */
    val hasBalanceMetric: Boolean
        get() = inventories != null || contractLiabilities != null || tradeReceivables != null

    val label: String get() = "$year.${quarter}Q"
}

/** 수주·재고 선행지표 — 최근 분기 시퀀스(오래된 순). */
data class LeadingIndicators(val quarters: List<LeadingQuarter>)

object LeadingIndicatorMath {

    /** (분기 1~4) → DART reprt_code. 2분기=반기보고서, 4분기=사업보고서. */
    fun reprtCode(quarter: Int): String = when (quarter) {
        1 -> "11013"; 2 -> "11012"; 3 -> "11014"; else -> "11011"
    }

    /**
     * 오늘 기준 "법정 제출 기한이 지난" 최근 [count]개 분기, 오래된 순.
     * 기한: 1Q 5/15 · 반기 8/14 · 3Q 11/14 · 사업보고서 익년 3/31 — getQuarterlyIncome의
     * 월 게이트와 같은 근거. 기한 직후 미제출 회사는 해당 분기가 결측으로 빠질 뿐(에러 아님).
     */
    fun periodSequence(today: LocalDate, count: Int = 5): List<Pair<Int, Int>> {
        var (y, q) = when (today.monthValue) {
            in 1..3  -> (today.year - 1) to 3
            4        -> (today.year - 1) to 4
            in 5..7  -> today.year to 1
            in 8..10 -> today.year to 2
            else     -> today.year to 3
        }
        val seq = ArrayDeque<Pair<Int, Int>>()
        repeat(count) {
            seq.addFirst(y to q)
            q -= 1
            if (q == 0) { q = 4; y -= 1 }
        }
        return seq.toList()
    }

    /**
     * fnlttSinglAcntAll 행에서 선행지표 계정 추출.
     * 매칭 전략: IFRS 표준 태그(account_id) 또는 계정명(공백 제거) — 회사별 계정명
     * 변형("매출채권및기타채권" 등)은 contains 폴백으로 흡수하되, 재고자산은 하위
     * 항목(평가충당금 등) 오매칭을 막기 위해 정확 일치를 우선한다.
     */
    fun extract(rows: List<DartAllAcntRow>, year: Int, quarter: Int): LeadingQuarter {
        fun norm(s: String) = s.replace(" ", "")
        val bs = rows.filter { it.sjDiv == "BS" }

        val inventories = (bs.firstOrNull { norm(it.accountName) == "재고자산" }
            ?: bs.firstOrNull { it.accountId == "ifrs-full_Inventories" }
            ?: bs.firstOrNull { norm(it.accountName).contains("재고자산") })
            ?.amount()

        // 계약부채는 유동·비유동 두 줄로 나뉘는 회사가 많다 → 매칭 행 전부 합산.
        // "확정계약부채"(FirmCommitmentLiabilities)는 파생상품 헤지 회계 항목으로 수주 선수금이
        // 아니다 — HD현대중공업 실데이터에서 오매칭 확인, 이름 매칭에서 제외.
        val contractRows = bs.filter {
            (norm(it.accountName).contains("계약부채") && !norm(it.accountName).contains("확정계약")) ||
                it.accountId.contains("ContractLiabilities")
        }
        val chosenRows = contractRows.ifEmpty {
            // 구 회계기준 표기 회사는 선수금이 같은 성격("선수수익"은 미매칭 — 다른 계정).
            bs.filter { norm(it.accountName).contains("선수금") }
        }
        val contractSum = chosenRows.mapNotNull { it.amount() }.takeIf { it.isNotEmpty() }?.sum()

        val receivables = bs.firstOrNull { norm(it.accountName).contains("매출채권") }?.amount()

        val isRows = rows.filter { it.sjDiv == "IS" || it.sjDiv == "CIS" }
        val revenueNames = setOf("매출액", "수익(매출액)", "영업수익")
        val revenue = (isRows.firstOrNull { norm(it.accountName) in revenueNames }
            ?: isRows.firstOrNull { it.accountId == "ifrs-full_Revenue" })
            ?.cumulative()

        return LeadingQuarter(
            year = year, quarter = quarter,
            inventories = inventories,
            contractLiabilities = contractSum,
            contractLiabIsAdvance = contractRows.isEmpty() && contractSum != null,
            tradeReceivables = receivables,
            revenueCum = revenue,
        )
    }
}

// ── DART fnlttSinglAcntAll(단일회사 전체 재무제표) 응답 모델 ──────────────────

@Serializable
data class DartAllAcntResponse(
    val status: String = "",
    val message: String = "",
    val list: List<DartAllAcntRow>? = null,
)

@Serializable
data class DartAllAcntRow(
    @SerialName("sj_div")            val sjDiv: String = "",      // BS/IS/CIS/CF/SCE
    @SerialName("account_id")        val accountId: String = "",  // IFRS 태그 또는 "-표준계정코드 미사용-"
    @SerialName("account_nm")        val accountName: String = "",
    @SerialName("thstrm_amount")     val thisAmount: String = "",
    @SerialName("thstrm_add_amount") val thisAddAmount: String = "",
) {
    fun amount(): Long? = thisAmount.replace(",", "").trim().toLongOrNull()

    /** 손익 누적 — 분기·반기 보고서는 add 필드가 누적, 없으면 thstrm 폴백(DartFinanceRow와 같은 함정). */
    fun cumulative(): Long? = thisAddAmount.replace(",", "").trim().toLongOrNull() ?: amount()
}
