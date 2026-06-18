package com.haky.edge.ai

import com.haky.edge.kis.KisClient
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.MacroImpactService.Sector
import com.haky.edge.master.StockMaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/** 한 지표(PER 또는 PBR)의 동종 상대 위치. */
@Serializable
data class PeerMetric(
    val current: Double,
    val peerMedian: Double,
    val peerMin: Double,
    val peerMax: Double,
    val diffPct: Double,   // (current - median) / median * 100
    val label: String,     // 동종 대비 낮음 / 동종과 비슷 / 동종 대비 높음
)

/** 동종(peer) 상대 밸류에이션. GET /peer-valuation/{code} 응답. */
@Serializable
data class PeerValuation(
    val code: String,
    val clusterLabel: String,  // 매칭된 섹터 레이블(방산·항공우주/조선/IT서비스·SI …)
    val peerCount: Int,        // 비교에 실제로 쓴 유효 peer 수(PER·PBR 중 큰 쪽)
    val per: PeerMetric? = null,
    val pbr: PeerMetric? = null,
)

/**
 * 같은 사업(섹터) peer의 PER/PBR 중앙값과 비교해 "동종 대비 싼가/비싼가"를 계산한다
 * ([[edge-valuation-slices]] 밸류-C). 역사 밴드(자기 과거)·목표가 추세와 다른 상대 축.
 *
 * 자동 분류(밸류-C2): 종목 섹터는 [MacroImpactService.resolveStockSectors](Claude 분류, 7일 캐시)로
 * 자동 결정 → 그 섹터의 peer 바스켓과 비교. 종목별 수동맵이 아니라 **섹터별 바스켓만 유지**하므로
 * 검색으로 추가된 임의 종목도 자동 커버된다. 멀티 섹터면 유효 비교가 나오는 첫 섹터를 쓴다.
 *
 * 설계 원칙:
 *  - peer per/pbr은 KIS inquire-price 단일 호출(DART 팬아웃 없음). 같은 소스라 상대 위치 일관.
 *  - 적자/이상치 제외 후 유효 peer가 [MIN_PEERS] 미만이면 해당 지표 null.
 *  - 바스켓이 없는 섹터(메모리반도체=2개·로봇/AI=적자·초기성장)는 자연히 null — 밸류 비교가 성립하는
 *    섹터만 바스켓을 둔다. 로봇·AI는 "테마"라 섹터 브리핑/매크로 영향이 다루고, 밸류 바는 비워둔다.
 */
class PeerValuationService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val macroImpact: MacroImpactService,
) {
    private val fileCache = FileCache("peer-valuation", PeerValuation.serializer())

    suspend fun getPeerValuation(code: String): PeerValuation? {
        val today = effectiveMarketDate()
        val cacheKey = "$code:$today"
        fileCache.get(cacheKey)?.let { return it }

        val target = runCatching { kis.getPrice(code) }.getOrNull() ?: return null
        val name = runCatching { master.findByCode(code)?.name }.getOrNull() ?: code
        val sectors = runCatching { macroImpact.resolveStockSectors(code, name, target.sectorName) }.getOrElse { emptyList() }

        // 분류된 섹터 순서대로(주력 우선) 바스켓을 찾아, 유효 비교가 나오는 첫 섹터를 채택.
        for (sector in sectors) {
            val basket = SECTOR_PEERS[sector] ?: continue
            val peerCodes = basket.filter { it != code }
            if (peerCodes.isEmpty()) continue

            val peerQuotes = coroutineScope {
                peerCodes.map { p -> async { runCatching { kis.getPrice(p) }.getOrNull() } }.awaitAll()
            }
            val peerPers = peerQuotes.mapNotNull { it?.per }.filter { it in PER_RANGE }
            val peerPbrs = peerQuotes.mapNotNull { it?.pbr }.filter { it in PBR_RANGE }

            // target 자신도 같은 유효범위 밖이면(적자·이익≈0 → PER 음수/수백배) 해당 지표는 비교 불가.
            val per = target.per.takeIf { it in PER_RANGE }?.let { buildMetric(it, peerPers) }
            val pbr = target.pbr.takeIf { it in PBR_RANGE }?.let { buildMetric(it, peerPbrs) }
            if (per == null && pbr == null) continue

            val result = PeerValuation(
                code = code,
                clusterLabel = sector.label,
                peerCount = maxOf(peerPers.size, peerPbrs.size),
                per = per,
                pbr = pbr,
            )
            fileCache.put(cacheKey, result)
            return result
        }
        return null
    }

    private fun buildMetric(current: Double, peers: List<Double>): PeerMetric? {
        if (current <= 0 || peers.size < MIN_PEERS) return null
        val sorted = peers.sorted()
        val median = sorted[sorted.size / 2]
        if (median <= 0) return null
        val diff = (current - median) / median * 100
        val label = when {
            diff <= -DIFF_THRESHOLD -> "동종 대비 낮음"
            diff >= DIFF_THRESHOLD  -> "동종 대비 높음"
            else                    -> "동종과 비슷"
        }
        return PeerMetric(current, median, sorted.first(), sorted.last(), diff, label)
    }

    companion object {
        private const val MIN_PEERS = 3       // 유효 peer 최소 수(미만이면 비교 생략)
        private const val DIFF_THRESHOLD = 15.0 // 중앙값 대비 ±15% 안은 "비슷"
        private val PER_RANGE = 0.5..200.0    // 이 밖(적자·이익≈0)은 PER 비교 제외
        private val PBR_RANGE = 0.1..50.0

        // 사업 단위 peer 바스켓. 코드는 /search로 검증(2026-06-16). 관심종목 + 보유종목 + 원전 커버.
        // 전선은 전력기기와, 자동차부품은 완성차와 같은 바스켓 공유(밸류 비교군이 사실상 동일).
        private val POWER_BASKET = setOf("267260", "010120", "298040", "062040", "001440", "000500", "006260")
        private val AUTO_BASKET = setOf("005380", "000270", "012330", "161390", "204320", "011210")

        // 바스켓이 없는 섹터(MEMORY=국내 2개뿐·ROBOT/AI=적자·초기성장 → 밸류 비교 무의미)는 의도적으로 비움 → null.
        private val SECTOR_PEERS: Map<Sector, Set<String>> = mapOf(
            Sector.DEFENSE to setOf("012450", "047810", "079550", "064350", "272210"),
            Sector.SHIPBUILDING to setOf("329180", "010140", "042660", "009540"),
            Sector.POWER_EQUIP to POWER_BASKET,
            Sector.CABLE to POWER_BASKET,
            Sector.IT_SERVICE to setOf("018260", "307950", "022100", "286940", "064400"),
            Sector.INTERNET to setOf("035420", "035720", "259960", "036570", "251270"),
            Sector.AUTO_OEM to AUTO_BASKET,
            Sector.AUTO_PARTS to AUTO_BASKET,
            Sector.NUCLEAR to setOf("034020", "052690", "051600", "083650", "105840"),
        )
    }
}
