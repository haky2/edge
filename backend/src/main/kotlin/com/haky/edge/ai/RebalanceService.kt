package com.haky.edge.ai

import com.haky.edge.kis.KisClient
import com.haky.edge.util.writeTextAtomic
import com.haky.edge.macro.HoldingPosition
import com.haky.edge.master.StockMaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

// ── 영속 모델 ({DATA_DIR}/portfolio_snapshot.json) ──────────────────────────

/** 스냅샷/기준점에 저장하는 포지션 1건. HoldingPosition은 @Serializable이 아니라 별도 모델. */
@Serializable
data class SnapshotPosition(val avgPrice: Double, val qty: Long)

/** 앱이 마지막으로 알려준 보유 포지션. /portfolio-review 호출 시마다 갱신(단일 사용자 전제 — premortem과 동일). */
@Serializable
data class PortfolioSnapshot(
    val positions: Map<String, SnapshotPosition>,
    val updatedAt: String,               // YYYY-MM-DD (KST) — 신선도 판정은 거래일 단위라 날짜면 충분
)

/**
 * 드리프트 비교 기준점. weights는 **설정 시점의 평가 비중** — 비중은 가격 의존이라
 * 나중에 재계산하면 기준 시점이 흘러가 버린다(그래서 설정 순간의 값을 고정 저장).
 */
@Serializable
data class RebalanceBaseline(
    val positions: Map<String, SnapshotPosition>,
    val weights: Map<String, Double>,    // code → 평가 비중(%)
    val setAt: String,                   // YYYY-MM-DD (KST)
)

@Serializable
private data class RebalanceStore(
    val current: PortfolioSnapshot? = null,
    val baseline: RebalanceBaseline? = null,
)

// ── 응답 DTO ────────────────────────────────────────────────────────────────

/** 종목 1개의 기준 대비 비중 변화. 발동 여부와 무관하게 전 종목 내려줌(앱 표가 그대로 렌더). */
@Serializable
data class DriftEntry(
    val code: String,
    val name: String,
    val baselinePct: Double,
    val currentPct: Double,              // 전량 매도면 0
    val deltaPp: Double,                 // currentPct - baselinePct (%p)
    val fired: Boolean,
)

/** 리밸런싱 점검 결과. 전부 계산(사실) — LLM 호출 없음. signals는 Slack 문구로 그대로 재사용(R2). */
@Serializable
data class RebalanceCheck(
    val date: String,
    val evaluated: Boolean,
    val reason: String? = null,          // 미평가 사유(스냅샷 없음/낡음)
    val snapshotUpdatedAt: String? = null,
    val baselineSetAt: String? = null,
    val drifts: List<DriftEntry> = emptyList(),
    val topBandWeightPct: Double? = null,       // 역사적 상단권 종목 비중 합
    val topBandStocks: List<String> = emptyList(),
    val topBandFired: Boolean = false,
    val signals: List<String> = emptyList(),
    // 기본값이면 encodeDefaults=false 에서 JSON 누락되므로 생성 시 명시 전달
    val caveat: String,
)

/**
 * 리밸런싱 트리거(R1) — 포트폴리오 진단(B)이 "지금 구조가 어떤가"라면, 이건 **"점검할 때가 됐다"를
 * 백엔드가 먼저 아는** 장치. 매매 지시가 아니라 점검 신호(caveat 고정).
 *
 * 룰(전부 계산, LLM 0, 임계값 env 조정):
 *  1. 비중 드리프트 — 현재 평가 비중 vs 기준점 비중 |Δ| ≥ REBALANCE_DRIFT_PP(기본 7%p).
 *     가격 변동만으로도 발생하는 게 정상(밴드 리밸런싱의 정의). 의도한 매매 후엔 기준점 재설정.
 *  2. 상단권 쏠림 — 밸류 밴드 "역사적 상단권" 종목의 비중 합 ≥ REBALANCE_TOP_BAND_PCT(기본 40%).
 *     고평가 단정이 아니라 차익 실현 점검 신호(P4 프레임과 동일).
 *
 * 백엔드는 무상태(포지션이 쿼리 파라미터)라 스케줄 잡이 평가할 포지션이 없다 →
 * /portfolio-review 호출 시 스냅샷을 파일로 영속(recordSnapshot). 앱이 내 자산 탭을 열 때마다
 * 자동 최신화되고, updatedAt이 낡으면(기본 3거래일 초과) 평가를 건너뛴다(낡은 포지션 오발동 방지).
 */
class RebalanceService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val valuationBand: ValuationBandService,
    private val driftPp: Double = System.getenv("REBALANCE_DRIFT_PP")?.toDoubleOrNull() ?: 7.0,
    private val topBandPct: Double = System.getenv("REBALANCE_TOP_BAND_PCT")?.toDoubleOrNull() ?: 40.0,
    private val staleBusinessDays: Int = System.getenv("REBALANCE_STALE_BDAYS")?.toIntOrNull() ?: 3,
) {
    private val dataDir = File(System.getenv("DATA_DIR") ?: ".data").also { it.mkdirs() }
    private val file = File(dataDir, "portfolio_snapshot.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 앱이 알려준 최신 포지션을 저장. 기준점이 아직 없으면 최초 1회 자동 설정(그 순간 비중 고정).
     * 스냅샷 저장을 먼저 하고 기준점을 시도 — 가격 조회가 실패해도 스냅샷은 남는다.
     */
    suspend fun recordSnapshot(positions: Map<String, HoldingPosition>) {
        val snap = PortfolioSnapshot(
            positions = positions.mapValues { SnapshotPosition(it.value.avgPrice, it.value.qty) },
            updatedAt = todayKst(),
        )
        mutate { it.copy(current = snap) }
        if (load().baseline == null) {
            runCatching { setBaseline() }
                .onFailure { println("[Rebalance] 최초 기준점 자동 설정 실패(다음 호출에 재시도): ${it.message}") }
        }
    }

    /** 현재 스냅샷을 기준점으로 고정(POST /rebalance/baseline). 스냅샷이 없으면 실패. */
    suspend fun setBaseline(): RebalanceBaseline {
        val snap = load().current
            ?: error("포지션 스냅샷이 없습니다 — 앱에서 포트폴리오 진단을 먼저 열어주세요")
        val weights = weightsOf(fetchValues(snap.positions))
        val baseline = RebalanceBaseline(snap.positions, weights, todayKst())
        mutate { it.copy(baseline = baseline) }
        return baseline
    }

    /** 룰 평가(R2 잡·GET /rebalance-check 공용). 시세 실패는 전파(부분 비중은 왜곡이라 평가 안 함). */
    suspend fun check(): RebalanceCheck {
        val today = todayKst()
        val store = load()
        val snap = store.current ?: return RebalanceCheck(
            date = today, evaluated = false,
            reason = "포지션 스냅샷이 없습니다 — 앱에서 포트폴리오 진단을 한 번 열면 생성됩니다",
            caveat = CAVEAT,
        )
        val ageBiz = businessDaysBetween(LocalDate.parse(snap.updatedAt), LocalDate.parse(today))
        if (ageBiz > staleBusinessDays) return RebalanceCheck(
            date = today, evaluated = false,
            reason = "스냅샷이 낡았습니다(${snap.updatedAt}, ${ageBiz}거래일 경과 > ${staleBusinessDays}) — 앱에서 포트폴리오 진단을 열면 갱신됩니다",
            snapshotUpdatedAt = snap.updatedAt,
            caveat = CAVEAT,
        )

        val weights = weightsOf(fetchValues(snap.positions))

        // 룰 1 — 기준점이 없으면(자동 설정 실패가 이어진 경우) 지금 비중으로 설정하고 이번엔 비교 생략.
        var baseline = store.baseline
        val drifts: List<DriftEntry>
        if (baseline == null) {
            baseline = RebalanceBaseline(snap.positions, weights, today)
            mutate { it.copy(baseline = baseline) }
            drifts = emptyList()
        } else {
            val names = (baseline.weights.keys + weights.keys).associateWith { code ->
                master.findByCode(code)?.name ?: code
            }
            drifts = driftEntries(baseline.weights, weights, driftPp, names)
        }

        // 룰 2 — 밴드 라벨은 종목별 실패 허용(라벨 없으면 상단권 합산에서 빠질 뿐).
        val bandLabels: Map<String, String> = coroutineScope {
            weights.keys.map { code ->
                async {
                    val label = runCatching { valuationBand.getValuationBand(code) }.getOrNull()
                        ?.takeIf { it.yearsUsed > 0 }?.perLabel
                    code to label
                }
            }.awaitAll()
        }.mapNotNull { (code, label) -> label?.let { code to it } }.toMap()
        val (topPct, topCodes) = topBandWeight(weights, bandLabels)
        val topFired = topCodes.isNotEmpty() && topPct >= topBandPct

        val signals = buildList {
            drifts.filter { it.fired }.forEach {
                add(
                    "비중 드리프트: ${it.name}(${it.code}) ${fmt1(it.baselinePct)}% → ${fmt1(it.currentPct)}% " +
                        "(기준 대비 ${signedPp(it.deltaPp)}, 임계 ±${fmt1(driftPp)}%p)"
                )
            }
            if (topFired) {
                val names = topCodes.map { master.findByCode(it)?.name ?: it }
                add("상단권 쏠림: 역사적 상단권 종목 비중 합 ${fmt1(topPct)}% ≥ ${fmt1(topBandPct)}% (${names.joinToString("·")})")
            }
        }

        return RebalanceCheck(
            date = today, evaluated = true,
            snapshotUpdatedAt = snap.updatedAt,
            baselineSetAt = baseline.setAt,
            drifts = drifts,
            topBandWeightPct = topPct,
            topBandStocks = topCodes.map { master.findByCode(it)?.name ?: it },
            topBandFired = topFired,
            signals = signals,
            caveat = CAVEAT,
        )
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    /** 현재가 × 수량 = 종목별 평가금액. 시세는 KIS 프록시 단기 캐시가 흡수. */
    private suspend fun fetchValues(positions: Map<String, SnapshotPosition>): Map<String, Long> =
        coroutineScope {
            positions.map { (code, pos) ->
                async { code to kis.getPrice(code).price * pos.qty }
            }.awaitAll().toMap()
        }

    @Synchronized
    private fun load(): RebalanceStore {
        if (!file.exists()) return RebalanceStore()
        return runCatching { json.decodeFromString(RebalanceStore.serializer(), file.readText()) }
            .getOrElse { RebalanceStore() } // 손상 파일은 초기화(스냅샷은 다음 호출에 재생성)
    }

    @Synchronized
    private fun mutate(transform: (RebalanceStore) -> RebalanceStore) {
        val next = transform(load())
        file.writeTextAtomic(json.encodeToString(RebalanceStore.serializer(), next))
    }

    companion object {
        const val CAVEAT = "매매 지시가 아닌 포트폴리오 점검 신호입니다. 의도한 매매 후에는 기준점을 재설정하세요."

        private val SEOUL = ZoneId.of("Asia/Seoul")
        private fun todayKst(): String = LocalDate.now(SEOUL).toString()
        private fun fmt1(v: Double) = "%.1f".format(v)
        private fun signedPp(v: Double) = (if (v >= 0) "+" else "") + fmt1(v) + "%p"

        /** 평가금액 → 비중(%). 합계 0이면 빈 맵(비중 정의 불가). */
        internal fun weightsOf(values: Map<String, Long>): Map<String, Double> {
            val total = values.values.sum()
            if (total <= 0) return emptyMap()
            return values.mapValues { it.value.toDouble() / total * 100 }
        }

        /**
         * 기준 대비 드리프트. 합집합 기준 — 기준점에만 있으면 전량 매도(현재 0%),
         * 현재에만 있으면 신규 편입(기준 0%)으로 취급해 둘 다 드리프트에 잡힌다.
         * |Δ| 내림차순. 1종목뿐이면 양쪽 다 100%라 Δ=0 → 자연히 발동 없음.
         */
        internal fun driftEntries(
            baseline: Map<String, Double>,
            current: Map<String, Double>,
            thresholdPp: Double,
            names: Map<String, String> = emptyMap(),
        ): List<DriftEntry> =
            (baseline.keys + current.keys).map { code ->
                val base = baseline[code] ?: 0.0
                val cur = current[code] ?: 0.0
                val delta = cur - base
                DriftEntry(
                    code = code,
                    name = names[code] ?: code,
                    baselinePct = base,
                    currentPct = cur,
                    deltaPp = delta,
                    fired = kotlin.math.abs(delta) >= thresholdPp,
                )
            }.sortedByDescending { kotlin.math.abs(it.deltaPp) }

        /** "역사적 상단권" 라벨 종목의 비중 합과 그 코드들(비중 내림차순). 라벨 없는 종목은 제외. */
        internal fun topBandWeight(
            weights: Map<String, Double>,
            bandLabels: Map<String, String>,
        ): Pair<Double, List<String>> {
            val top = weights.filterKeys { bandLabels[it] == "역사적 상단권" }
            return top.values.sum() to top.entries.sortedByDescending { it.value }.map { it.key }
        }

        /**
         * (from, to] 사이의 평일(월~금) 수. 공휴일은 무시(근사) — 공휴일이 끼면 실제보다 크게 세서
         * 신선도 판정이 약간 보수적으로(더 빨리 낡음 처리) 동작할 뿐, 오발동 방향은 아니다.
         */
        internal fun businessDaysBetween(from: LocalDate, to: LocalDate): Int {
            if (!to.isAfter(from)) return 0
            var d = from
            var count = 0
            while (d.isBefore(to)) {
                d = d.plusDays(1)
                if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) count++
            }
            return count
        }
    }
}
