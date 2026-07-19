package com.haky.edge.lab

import com.haky.edge.kis.KisClient
import com.haky.edge.master.StockMaster
import com.haky.edge.util.writeTextAtomic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * R3 대조 유니버스 — 코스피 시총 상위 근사 TOP_N에서 관심종목을 뺀 뒤 고정 시드로 뽑은
 * SAMPLE_SIZE 종목. anchor 실측(관심 11종목 = 모멘텀 생존 편향 표본)의 결론이
 * "고르게 뽑은 대형주" 표본에서도 유지되는지 검증하기 위한 표본 틀(sampling frame).
 *
 * 시총 근사 = 현재가(getPrice) × 상장주식수(같은 응답의 부산물 캐시) — 마스터에 시총이 없어
 * 전 KOSPI 보통주를 1회 순회한다(KisClient 세마포어가 스로틀, 종목당 HTTP 1회).
 * 결과는 파일로 영속해 재실행 시 재순회하지 않는다(실험 재현성 — 리포트에 코드 목록 병기).
 */
class ControlUniverseService(
    private val kis: KisClient,
    private val master: StockMaster,
    private val watchCodes: List<String>,
) {
    private val file = File("${System.getenv("CACHE_DIR") ?: ".cache"}/lab/control_universe.json")
        .also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    data class Universe(val builtAt: String, val codes: List<String>, val names: Map<String, String>)

    suspend fun universe(): List<String> = detail().codes

    suspend fun detail(): Universe = load() ?: build()

    private fun load(): Universe? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(Universe.serializer(), file.readText()) }.getOrNull()
    }

    private suspend fun build(): Universe {
        val candidates = master.all().filter {
            it.market == "KOSPI" && it.name.isNotBlank() && isCommonStock(it.code, it.name)
        }
        println("[ControlUniverse] 시총 순회 시작 — 후보 ${candidates.size}종목 (1회성, 이후 파일 캐시)")
        // 시총 근사. 실패 종목은 제외(거래정지·상폐 직전 등) — 표본 틀이라 개별 누락 무해.
        val caps = coroutineScope {
            candidates.map { s ->
                async {
                    val q = runCatching { kis.getPrice(s.code) }.getOrNull() ?: return@async null
                    val shares = runCatching { kis.getListedShares(s.code) }.getOrNull() ?: return@async null
                    if (q.price <= 0 || shares <= 0) null else Triple(s.code, s.name, q.price * shares)
                }
            }.awaitAll().filterNotNull()
        }
        println("[ControlUniverse] 시총 확보 ${caps.size}종목 → 상위 $TOP_N 중 시드 $SEED 표본 $SAMPLE_SIZE")
        val sample = sampleTop(caps, watchCodes)
        val nameByCode = caps.associate { it.first to it.second }
        val u = Universe(
            builtAt = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString(),
            codes = sample,
            names = sample.associateWith { nameByCode[it] ?: it },
        )
        runCatching { file.writeTextAtomic(json.encodeToString(Universe.serializer(), u)) }
        return u
    }

    companion object {
        const val TOP_N = 200
        const val SAMPLE_SIZE = 30
        const val SEED = 42L

        // 보통주가 아닌 상장물 이름 패턴 — ETF 브랜드·스팩·리츠·인프라펀드·ETN 등.
        // 완벽 필터가 목적이 아니다(표본 틀) — 시총 상위에 흔한 비보통주 오염만 걷어낸다.
        private val NON_COMMON_NAME = Regex(
            "KODEX|TIGER|KBSTAR|RISE|ACE |SOL |PLUS|ARIRANG|HANARO|KOSEF|스팩|리츠|ETN|인프라|채권|TDF|커버드콜|레버리지|인버스|선물"
        )

        /** 보통주 판별: 6자리 숫자 + 끝자리 0(우선주 5/7/9/K 계열 제외) + 비보통주 이름 패턴 제외. */
        fun isCommonStock(code: String, name: String): Boolean =
            code.matches(CODE_6DIGIT) && code.endsWith("0") && !NON_COMMON_NAME.containsMatchIn(name)

        private val CODE_6DIGIT = Regex("""\d{6}""")

        /**
         * 시총 상위 TOP_N → 관심종목 제외 → 코드 오름차순 정렬 후 java.util.Random(SEED) 셔플로
         * SAMPLE_SIZE 추출(같은 멤버십이면 언제나 같은 표본 — JDK LCG는 사양 고정). 순수 함수.
         */
        fun sampleTop(caps: List<Triple<String, String, Long>>, watch: List<String>): List<String> {
            val top = caps.sortedByDescending { it.third }.take(TOP_N).map { it.first }
            val pool = top.filter { it !in watch }.sorted().toMutableList()
            java.util.Collections.shuffle(pool, java.util.Random(SEED))
            return pool.take(SAMPLE_SIZE).sorted()
        }
    }
}
