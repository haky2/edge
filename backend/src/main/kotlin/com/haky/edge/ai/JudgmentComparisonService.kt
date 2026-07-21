package com.haky.edge.ai

import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.IndexPoint
import com.haky.edge.kis.KisClient
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── 입력(앱이 POST로 보내는 행동 로그) ────────────────────────────────────

/** 행동 1건 — 앱 action_log 전체 이력(buy/sell/interest). date = YYYY-MM-DD(KST). */
@Serializable
data class JudgmentTrade(
    val code: String,
    val action: String,   // "buy" | "sell" | "interest"
    val date: String,
)

// ── 출력 DTO ──────────────────────────────────────────────────────────────

/** 집계 버킷 1개 — 방향 적중률(초과수익 기준) + 평균 초과수익. */
@Serializable
data class ComparisonBucket(
    val label: String,
    val n: Int,
    val wins: Int,
    val winRatePct: Double,
    val avgExcessPct: Double,   // 20거래일 평균 초과수익(종목-코스피, %)
    val avgRawPct: Double,      // 평균 원수익률(%) — 참고 병기
)

/** 관심 후 미매수 기회비용 관찰(채점 아님 — 안 산 결정의 사후 기록). */
@Serializable
data class MissedInterestStats(
    val n: Int,                  // 관심만 남기고 매수 안 한 판단 수(20거래일 경과분)
    val roseN: Int,              // 그중 20거래일 초과수익 양수
    val avgExcessPct: Double,
    val aiPositiveN: Int,        // 그중 관심 시점 AI 스탠스가 긍정이었던 것
    val aiPositiveRoseN: Int,    // 긍정 매칭분 중 실제 오른 것
)

/**
 * POST /judgment-comparison — "AI 말 들었으면?" 반사실 성적 대조.
 * 내 매매·AI 스탠스를 같은 잣대(20거래일 초과수익)로 채점해 나란히 놓는다.
 */
@Serializable
data class JudgmentComparison(
    val date: String,
    val horizonDays: Int,
    val myBuy: ComparisonBucket? = null,        // 내 매수 전체
    val mySell: ComparisonBucket? = null,       // 내 매도 전체
    val aiPositive: ComparisonBucket? = null,   // AI 긍정 스탠스(같은 잣대 재채점)
    val aiNegative: ComparisonBucket? = null,   // AI 부정 스탠스
    val buyMatrix: List<ComparisonBucket> = emptyList(),   // 매수를 스탠스 매칭별로: 동의/역행/중립/무참조
    val sellMatrix: List<ComparisonBucket> = emptyList(),  // 매도 동일
    val missedInterest: MissedInterestStats? = null,
    val pendingTrades: Int = 0,   // 20거래일 미경과(채점 대기)
    val caveat: String = "",
)

/**
 * 판단 대조 서비스 — 반사실 성적 비교("AI 말 들었으면?"). 신규 데이터 소스 0:
 * 앱 행동 로그(POST 바디) × stance_log × 일봉(F1 캐시) × 코스피(0001)만 교차한다.
 *
 * 방법론(전부 기존 실증 트랙에서 확립된 규약 재사용):
 * - horizon 20거래일, 기준봉 = 행동일 이전(당일 포함) 마지막 거래일 종가 → +20거래일 종가
 *   (CatalystValidationService.forwardPair 규약. 수정주가 봉 대 봉이라 분할·감자 안전).
 * - 채점은 **초과수익(종목−코스피)** — 상승장에선 아무 매수나 맞아 보이는 기저율 함정 통제
 *   (catalyst-validation·MoodLog 기저율 교훈). 원수익률은 참고 병기.
 * - 매수 적중 = 초과수익 > 0, 매도 적중 = 초과수익 < 0.
 * - AI 스탠스도 **같은 잣대로 재채점**해서 비교(X4부터 StanceStats도 동일한 초과수익 채점 —
 *   집계 범위만 다름). 중립 스탠스는 방향이 없어 대조에서 제외.
 * - 스탠스 매칭: 행동일 D 기준 [D-7일, D] 안 같은 종목 마지막 유효 스탠스(미상 제외, 모드 무관).
 *   스탠스 로그가 2026-07 시작이라 그 전 매매는 전부 "무참조" 버킷 — 결측이지 불일치가 아니다.
 * - 같은 (code, date, action)은 1건으로 접는다(분할 매수는 판단 1회).
 *
 * LLM 0 — 순수 계산. 해석은 후속(주간 회고 facts 주입)에서.
 * 캐시: 입력이 개인 데이터라 (영업일 + 행동로그 해시) 개인별.
 */
class JudgmentComparisonService(
    private val kis: KisClient,
    private val stanceLog: StanceLog,
    private val history: DailyHistoryService,
) {
    private val fileCache = FileCache("judgment-comparison", JudgmentComparison.serializer())

    suspend fun compare(trades: List<JudgmentTrade>): JudgmentComparison {
        val today = effectiveMarketDate()
        val key = "$today|${AnalysisService.shortHash(cacheFingerprint(trades))}"
        fileCache.get(key)?.let { return it }

        val stances = stanceLog.readAll()
        val codes = (trades.map { it.code } + stances.map { it.code }).distinct()
        val barsByCode: Map<String, List<DailyBar>> = codes.associateWith { code ->
            runCatching { history.getHistory(code) }.getOrElse { emptyList() }.asReversed() // asc
        }
        val minDate = (trades.map { it.date.replace("-", "") } + stances.map { it.date.replace("-", "") })
            .minOrNull() ?: LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE)
        val kospiAsc = fetchKospiAsc(minDate)

        val result = score(trades, stances, barsByCode, kospiAsc, today)
        fileCache.put(key, result)
        return result
    }

    private suspend fun fetchKospiAsc(minYmd: String): List<IndexPoint> = fetchKospiCloseAsc(kis, minYmd)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        const val HORIZON_DAYS = 20
        const val STANCE_MATCH_WINDOW_DAYS = 7L

        internal fun cacheFingerprint(trades: List<JudgmentTrade>): String =
            trades.sortedWith(compareBy({ it.date }, { it.code }, { it.action }))
                .joinToString(",") { "${it.date}:${it.code}:${it.action}" }

        /** 순수 채점 함수 — 테스트 가능하도록 IO 없이 분리(코드베이스 관례). barsByCode는 오래된 순. */
        internal fun score(
            tradesRaw: List<JudgmentTrade>,
            stances: List<StanceEntry>,
            barsByCode: Map<String, List<DailyBar>>,
            kospiAsc: List<IndexPoint>,
            today: String,
        ): JudgmentComparison {
            // 같은 (code, date, action) 1건으로 — 분할 매수·중복 기록은 판단 1회.
            val trades = tradesRaw
                .filter { it.action in setOf("buy", "sell", "interest") }
                .associateBy { "${it.code}|${it.date}|${it.action}" }.values.toList()

            // 유효 스탠스만, 날짜 오름차순(매칭 시 lastOrNull이 "가장 최근"이 되도록).
            val validStances = stances
                .filter { it.stance in setOf("긍정", "중립", "부정") }
                .sortedWith(compareBy({ it.date }, { it.generatedAt }))

            fun matchStance(code: String, dateIso: String): String? {
                val d = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return null
                val from = d.minusDays(STANCE_MATCH_WINDOW_DAYS).toString()
                return validStances.lastOrNull { it.code == code && it.date >= from && it.date <= dateIso }?.stance
            }

            data class ScoredAction(val trade: JudgmentTrade, val raw: Double, val excess: Double, val matched: String?)

            var pending = 0
            val scoredBuys = mutableListOf<ScoredAction>()
            val scoredSells = mutableListOf<ScoredAction>()
            val interests = mutableListOf<JudgmentTrade>()

            for (t in trades) {
                if (t.action == "interest") { interests += t; continue }
                val pair = CatalystValidationService.forwardPair(
                    t.date.replace("-", ""), barsByCode[t.code].orEmpty(), kospiAsc, HORIZON_DAYS)
                if (pair == null) { pending++; continue }
                val scored = ScoredAction(t, pair.first, pair.second, matchStance(t.code, t.date))
                if (t.action == "buy") scoredBuys += scored else scoredSells += scored
            }

            // 버킷 집계 — 매수는 excess>0 적중, 매도는 excess<0 적중.
            fun bucket(label: String, list: List<ScoredAction>, winIf: (Double) -> Boolean): ComparisonBucket? {
                if (list.isEmpty()) return null
                val wins = list.count { winIf(it.excess) }
                return ComparisonBucket(
                    label = label, n = list.size, wins = wins,
                    winRatePct = round1(wins * 100.0 / list.size),
                    avgExcessPct = round2(list.map { it.excess }.average()),
                    avgRawPct = round2(list.map { it.raw }.average()),
                )
            }
            val buyWin: (Double) -> Boolean = { it > 0 }
            val sellWin: (Double) -> Boolean = { it < 0 }

            // 매수 매트릭스: AI 동의(긍정)/역행(부정)/중립 참조/무참조. 매도는 동의=부정.
            fun matrix(list: List<ScoredAction>, agree: String, oppose: String, winIf: (Double) -> Boolean) =
                listOfNotNull(
                    bucket("AI 동의($agree)", list.filter { it.matched == agree }, winIf),
                    bucket("AI 역행($oppose)", list.filter { it.matched == oppose }, winIf),
                    bucket("AI 중립", list.filter { it.matched == "중립" }, winIf),
                    bucket("무참조", list.filter { it.matched == null }, winIf),
                )

            // AI 스탠스 재채점(같은 잣대) — 스탠스 날짜 기준 forward 20거래일 초과수익.
            // 같은 (code,date,mode) 중복은 마지막 것(StanceStats 규약).
            val dedupedStances = validStances.associateBy { "${it.code}|${it.date}|${it.mode}" }.values
            data class ScoredStance(val stance: String, val raw: Double, val excess: Double)
            val scoredStances = dedupedStances.mapNotNull { e ->
                if (e.stance == "중립") return@mapNotNull null   // 방향 없음 — 대조 제외
                val pair = CatalystValidationService.forwardPair(
                    e.date.replace("-", ""), barsByCode[e.code].orEmpty(), kospiAsc, HORIZON_DAYS)
                    ?: return@mapNotNull null
                ScoredStance(e.stance, pair.first, pair.second)
            }
            fun stanceBucket(label: String, s: String, winIf: (Double) -> Boolean): ComparisonBucket? {
                val list = scoredStances.filter { it.stance == s }
                if (list.isEmpty()) return null
                val wins = list.count { winIf(it.excess) }
                return ComparisonBucket(label, list.size, wins,
                    round1(wins * 100.0 / list.size),
                    round2(list.map { it.excess }.average()),
                    round2(list.map { it.raw }.average()))
            }

            // 관심 후 미매수: interest 이후(같은 종목, 이후 날짜) buy 없음 → 기회비용 관찰.
            val buyDatesByCode = trades.filter { it.action == "buy" }.groupBy({ it.code }, { it.date })
            var missedN = 0; var roseN = 0; var aiPosN = 0; var aiPosRoseN = 0
            val missedExcess = mutableListOf<Double>()
            for (i in interests) {
                val boughtLater = buyDatesByCode[i.code].orEmpty().any { it >= i.date }
                if (boughtLater) continue
                val pair = CatalystValidationService.forwardPair(
                    i.date.replace("-", ""), barsByCode[i.code].orEmpty(), kospiAsc, HORIZON_DAYS) ?: continue
                missedN++
                missedExcess += pair.second
                val rose = pair.second > 0
                if (rose) roseN++
                if (matchStance(i.code, i.date) == "긍정") {
                    aiPosN++
                    if (rose) aiPosRoseN++
                }
            }

            return JudgmentComparison(
                date = today,
                horizonDays = HORIZON_DAYS,
                myBuy = bucket("내 매수", scoredBuys, buyWin),
                mySell = bucket("내 매도", scoredSells, sellWin),
                aiPositive = stanceBucket("AI 긍정", "긍정", buyWin),
                aiNegative = stanceBucket("AI 부정", "부정", sellWin),
                buyMatrix = matrix(scoredBuys, agree = "긍정", oppose = "부정", winIf = buyWin),
                sellMatrix = matrix(scoredSells, agree = "부정", oppose = "긍정", winIf = sellWin),
                missedInterest = if (missedN > 0) MissedInterestStats(
                    n = missedN, roseN = roseN,
                    avgExcessPct = round2(missedExcess.average()),
                    aiPositiveN = aiPosN, aiPositiveRoseN = aiPosRoseN,
                ) else null,
                pendingTrades = pending,
                caveat = "20거래일 초과수익(종목−코스피) 기준 — 스탠스 통계 화면과 동일 잣대(X4 통일, " +
                    "여기는 방향 대조라 중립 스탠스만 제외). n<15 버킷은 참고 수준 — " +
                    "매매·스탠스 모두 관심종목 유니버스라 상대 비교만 유효. " +
                    "스탠스 기록 시작(2026-07) 이전 매매는 무참조로 분류(결측이지 불일치 아님).",
            )
        }

        internal fun round1(v: Double) = kotlin.math.round(v * 10) / 10.0
        internal fun round2(v: Double) = kotlin.math.round(v * 100) / 100.0
    }
}

/**
 * 코스피(0001) 일별 종가(오래된 순) — 90일 청크 병합. minYmd 30일 전부터 오늘까지.
 * JudgmentComparison·StanceStats가 공유(초과수익 채점의 대조군 소스 단일화).
 */
internal suspend fun fetchKospiCloseAsc(kis: KisClient, minYmd: String): List<IndexPoint> {
    val kst = ZoneId.of("Asia/Seoul")
    val fmt = DateTimeFormatter.BASIC_ISO_DATE
    var start = runCatching { LocalDate.parse(minYmd, fmt) }.getOrElse { LocalDate.now(kst) }.minusDays(30)
    val end = LocalDate.now(kst)
    val merged = mutableMapOf<String, IndexPoint>()
    while (start <= end) {
        val chunkEnd = minOf(start.plusDays(89), end)
        runCatching {
            kis.getSectorIndexChartRange("0001", start.format(fmt), chunkEnd.format(fmt))
        }.getOrElse { emptyList() }.forEach { merged[it.date] = it }
        start = chunkEnd.plusDays(1)
    }
    return merged.values.sortedBy { it.date }
}
