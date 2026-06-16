package com.haky.edge.ai

import com.haky.edge.kis.KisClient
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
    val clusterLabel: String,  // 방산/조선/IT서비스 …
    val peerCount: Int,        // 비교에 실제로 쓴 유효 peer 수(PER·PBR 중 큰 쪽)
    val per: PeerMetric? = null,
    val pbr: PeerMetric? = null,
)

/**
 * 사업 단위 peer 클러스터 안에서 PER/PBR을 동종 중앙값과 비교한다.
 * 역사 밴드(자기 과거 대비)·목표가 추세에 이어 "동종 대비 싼가/비싼가"라는 상대 축을 추가
 * ([[edge-valuation-slices]] 밸류-C). 리레이팅·이익 점프 국면에서 역사 밴드가 깨질 때 특히 유효.
 *
 * 설계:
 *  - peer는 universe에 섹터 태그가 없어 **수동 클러스터**로 정의(아래 CLUSTERS). 보수적으로, 틀린 비교보다 null.
 *  - peer per/pbr은 KIS inquire-price(getPrice) 단일 호출 — DART 팬아웃 없음. 같은 소스라 상대 위치는 일관.
 *  - 적자/이상치 제외 후 유효 peer가 [MIN_PEERS] 미만이면 해당 지표는 null(메모리반도체처럼 thin한 군은 비교 안 함).
 *  - median 사용(소표본에서 한 종목 이상치에 강건).
 */
class PeerValuationService(private val kis: KisClient) {
    private val fileCache = FileCache("peer-valuation", PeerValuation.serializer())

    suspend fun getPeerValuation(code: String): PeerValuation? {
        val cluster = CLUSTERS.firstOrNull { code in it.codes } ?: return null

        val today = effectiveMarketDate()
        val cacheKey = "$code:$today"
        fileCache.get(cacheKey)?.let { return it }

        val target = runCatching { kis.getPrice(code) }.getOrNull() ?: return null
        val peerCodes = cluster.codes.filter { it != code }

        val peerQuotes = coroutineScope {
            peerCodes.map { p -> async { runCatching { kis.getPrice(p) }.getOrNull() } }.awaitAll()
        }
        val peerPers = peerQuotes.mapNotNull { it?.per }.filter { it in 0.5..200.0 }
        val peerPbrs = peerQuotes.mapNotNull { it?.pbr }.filter { it in 0.1..50.0 }

        val per = buildMetric(target.per, peerPers)
        val pbr = buildMetric(target.pbr, peerPbrs)
        if (per == null && pbr == null) return null

        val result = PeerValuation(
            code = code,
            clusterLabel = cluster.label,
            peerCount = maxOf(peerPers.size, peerPbrs.size),
            per = per,
            pbr = pbr,
        )
        fileCache.put(cacheKey, result)
        return result
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

    private data class Cluster(val label: String, val codes: Set<String>)

    companion object {
        private const val MIN_PEERS = 3       // 유효 peer 최소 수(미만이면 비교 생략)
        private const val DIFF_THRESHOLD = 15.0 // 중앙값 대비 ±15% 안은 "비슷"

        // 사업 단위 수동 peer 클러스터. 코드는 /search로 검증(2026-06-16). 관심종목 + 보유종목 커버.
        // 메모리반도체(삼성전자·SK하이닉스)는 국내 peer 2개뿐이라 의도적으로 제외(thin → null).
        private val CLUSTERS = listOf(
            Cluster("방산", setOf("012450", "047810", "079550", "064350", "272210")),
            Cluster("조선", setOf("329180", "010140", "042660", "009540")),
            Cluster("전력·전선", setOf("267260", "010120", "298040", "062040", "001440", "000500", "006260")),
            Cluster("IT서비스", setOf("018260", "307950", "022100", "286940", "064400")),
            Cluster("인터넷·플랫폼", setOf("035420", "035720", "259960", "036570", "251270")),
            Cluster("자동차", setOf("005380", "000270", "012330", "161390", "204320", "011210")),
        )
    }
}
