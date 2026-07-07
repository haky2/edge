package com.haky.edge.ai

import com.haky.edge.kis.KisClient
import com.haky.edge.master.StockMaster
import com.haky.edge.slack.SignalService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── 응답 DTO ────────────────────────────────────────────────────────────────

/** 후보 종목에 켜진 신호 1개. type은 앱 배지, detail은 사람 읽는 근거. */
@Serializable
data class DiscoverySignal(val type: String, val detail: String)

@Serializable
data class DiscoveryCandidate(
    val code: String,
    val name: String,
    val sector: String,          // peer 바스켓의 섹터 레이블
    val price: Long,
    val changeRate: Double,      // 당일 등락률 %
    val signals: List<DiscoverySignal>,
)

/** 지켜볼 후보 발굴 결과. 전부 계산(사실) — LLM 호출 없음, 점수화·가중치 없음(신호 나열만). */
@Serializable
data class DiscoveryReport(
    val date: String,
    val universeSize: Int,       // 관심종목 제외 후 스캔 대상 수
    val scannedSize: Int,        // 실제 평가 성공 수(시세 실패 종목 제외)
    val candidates: List<DiscoveryCandidate>,
    // 기본값이면 encodeDefaults=false 에서 JSON 누락되므로 생성 시 명시 전달
    val caveat: String,
)

/**
 * 지켜볼 후보 발굴(D1) — "어느 종목을 새로 지켜봐야 하나"에 답한다. 앱이 관심종목 폐쇄형이라
 * 관심 밖에서 신호가 켜진 종목을 볼 방법이 없던 공백.
 *
 * 유니버스는 전시장이 아니라 **관심 섹터의 peer 바스켓**(PeerValuationService.SECTOR_PEERS 재사용,
 * 관심종목 제외 ±20종목) — KIS 호출량·소음 관리 + "내 투자 문법 안의 후보"라는 명확한 프레임.
 *
 * 신호 4종(전부 기존 인프라·계산 재사용):
 *  1. 수급전환 — F4 SignalService.detectReversal(외인/기관, 5일 연속 순매도 후 첫 순매수). 발굴
 *     목적이라 매수 전환만 본다(매도 전환 종목을 "지켜봐라"는 어색).
 *  2. 상대모멘텀 — 종목 20일 수익률 − 코스피 20일 수익률 ≥ +5%p. 세부 섹터→KRX 업종지수 매핑이
 *     대분류 1:N 혼재라(SectorBriefingService 참고) 오분류 위험 없는 시장 대비로 판정.
 *  3. 신고가근접 — 52주 위치 ≥ 90%(돌파 관찰 후보).
 *  4. 저점반등 — 52주 위치 < 30% && 최근 5일 ≥ +5%.
 *
 * 소음 컷: 신호 **2개 이상** 겹친 종목만, 최대 5종목. 점수화·가중치 튜닝 금지(F1 교훈).
 * 신호 0~1개면 후보 없음(억지로 채우지 않음 — 앱은 섹션 숨김).
 */
class DiscoveryService(
    private val kis: KisClient,
    private val master: StockMaster,
    watchCodes: List<String>,
) {
    private val watchSet = watchCodes.toSet()
    private val fileCache = FileCache("discovery", DiscoveryReport.serializer())

    suspend fun discover(force: Boolean = false): DiscoveryReport {
        val today = effectiveMarketDate()
        if (!force) fileCache.get(today)?.let { return it }

        val universe = PeerValuationService.peerUniverse().filterKeys { it !in watchSet }

        // 벤치마크(코스피 20일 수익률) — 실패해도 전체를 죽이지 않고 상대모멘텀 신호만 생략.
        val benchRet20 = runCatching { kospiRet20() }.getOrNull()

        val evaluated = coroutineScope {
            universe.map { (code, sector) ->
                async {
                    runCatching {
                        val quote = kis.getPrice(code)
                        val closes = kis.getDailyChart(code, bars = 30).map { it.close.toDouble() }
                        // 수급은 없어도 나머지 신호는 평가(신규 상장 등 이력 부족 방어)
                        val flows = runCatching { kis.getInvestorFlow(code, days = 10) }.getOrDefault(emptyList())
                        val signals = evaluateSignals(
                            pos52w = pos52w(quote.price, quote.high52w, quote.low52w),
                            ret20 = retPct(closes, 20),
                            ret5 = retPct(closes, 5),
                            benchRet20 = benchRet20,
                            foreignNet = flows.map { it.foreign },
                            instNet = flows.map { it.institution },
                        )
                        DiscoveryCandidate(
                            code = code,
                            name = master.findByCode(code)?.name ?: code,
                            sector = sector.label,
                            price = quote.price,
                            changeRate = quote.changeRate,
                            signals = signals,
                        )
                    }.getOrNull() // 종목 단위 실패(거래정지 등)는 스캔에서 제외
                }
            }.awaitAll()
        }.filterNotNull()

        val report = DiscoveryReport(
            date = today,
            universeSize = universe.size,
            scannedSize = evaluated.size,
            candidates = selectCandidates(evaluated),
            caveat = CAVEAT,
        )
        fileCache.put(today, report)
        return report
    }

    /** 코스피 지수(0001)의 20거래일 수익률. 45일 창이면 거래일 21개는 확보된다. */
    private suspend fun kospiRet20(): Double? {
        val today = LocalDate.now(SEOUL)
        val points = kis.getSectorIndexChartRange(
            iscd = "0001",
            startYmd = today.minusDays(45).format(YMD),
            endYmd = today.format(YMD),
        )
        return retPct(points.map { it.close }, 20)
    }

    companion object {
        const val CAVEAT = "관심 섹터 peer 바스켓 내 스캔 결과입니다(전시장 아님). 신호 2개 이상 겹친 종목만 표시하며, 추천이 아니라 관찰 후보입니다 — 관심 등록 전 상세 분석을 확인하세요."

        internal const val REL_MOMENTUM_PP = 5.0   // 코스피 대비 20일 초과 수익 임계(%p)
        internal const val HIGH_POS_PCT = 90.0     // 신고가 근접 = 52주 위치 90% 이상
        internal const val LOW_POS_PCT = 30.0      // 저점권 = 52주 위치 30% 미만
        internal const val REBOUND_RET5_PCT = 5.0  // 저점권 반등 = 5일 +5% 이상
        internal const val MIN_SIGNALS = 2         // 소음 컷: 신호 교집합 최소 수
        internal const val MAX_CANDIDATES = 5

        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
        private fun fmt1(v: Double) = "%.1f".format(v)

        /** 52주 위치(%). 고저가 무의미(고≤저)하거나 가격이 0이면 null. */
        internal fun pos52w(price: Long, high52w: Long, low52w: Long): Double? {
            if (high52w <= low52w || price <= 0) return null
            return (price - low52w).toDouble() / (high52w - low52w) * 100
        }

        /** 최신이 앞인 종가 리스트에서 days 거래일 수익률(%). 이력 부족·기준가 0이면 null. */
        internal fun retPct(closesNewestFirst: List<Double>, days: Int): Double? {
            if (closesNewestFirst.size <= days) return null
            val base = closesNewestFirst[days]
            if (base <= 0) return null
            return (closesNewestFirst[0] / base - 1) * 100
        }

        /**
         * 신호 4종 평가(순수 함수). null 입력은 해당 신호만 생략(부분 평가) —
         * 업종 매핑 없는 종목도 다른 신호는 평가한다는 스펙 원칙.
         */
        internal fun evaluateSignals(
            pos52w: Double?,
            ret20: Double?,
            ret5: Double?,
            benchRet20: Double?,
            foreignNet: List<Long>,
            instNet: List<Long>,
        ): List<DiscoverySignal> = buildList {
            SignalService.detectReversal(foreignNet)?.takeIf { it.toBuy }?.let {
                add(DiscoverySignal("수급전환", "외국인 ${it.prevStreak}일 연속 순매도 후 순매수 전환"))
            }
            SignalService.detectReversal(instNet)?.takeIf { it.toBuy }?.let {
                add(DiscoverySignal("수급전환", "기관 ${it.prevStreak}일 연속 순매도 후 순매수 전환"))
            }
            if (ret20 != null && benchRet20 != null && ret20 - benchRet20 >= REL_MOMENTUM_PP) {
                add(DiscoverySignal("상대모멘텀", "20일 ${fmt1(ret20)}% (코스피 대비 +${fmt1(ret20 - benchRet20)}%p)"))
            }
            if (pos52w != null && pos52w >= HIGH_POS_PCT) {
                add(DiscoverySignal("신고가근접", "52주 위치 ${fmt1(pos52w)}% — 신고가 돌파 관찰"))
            }
            if (pos52w != null && ret5 != null && pos52w < LOW_POS_PCT && ret5 >= REBOUND_RET5_PCT) {
                add(DiscoverySignal("저점반등", "52주 저점권(위치 ${fmt1(pos52w)}%)에서 5일 +${fmt1(ret5)}%"))
            }
        }

        /** 소음 컷 — 신호 2개 이상만, 신호 수 → 당일 등락률 순, 최대 5종목. */
        internal fun selectCandidates(all: List<DiscoveryCandidate>): List<DiscoveryCandidate> =
            all.filter { it.signals.size >= MIN_SIGNALS }
                .sortedWith(compareByDescending<DiscoveryCandidate> { it.signals.size }.thenByDescending { it.changeRate })
                .take(MAX_CANDIDATES)
    }
}
