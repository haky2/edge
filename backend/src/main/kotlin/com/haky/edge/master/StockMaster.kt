package com.haky.edge.master

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** 검색 결과 한 건(코드·이름·시장). 앱이 이 형태 그대로 받아 관심종목 추가에 쓴다. */
@Serializable
data class StockInfo(
    val code: String,
    val name: String,
    val market: String, // KOSPI | KOSDAQ
)

/**
 * 한투가 공개로 제공하는 종목 마스터(.mst) 파일을 받아 코드↔이름 인덱스를 만든다.
 *
 * 왜 이 방식? 한투엔 "이름으로 검색"하는 깔끔한 API가 없다. 대신 전체 종목 목록 파일을 공개로 내려주므로,
 * 그걸 한 번 받아 메모리에 들고 검색은 우리가 직접 한다(인증 불필요, 호출 비용 0).
 *
 * 파일 특성(주의):
 *  - zip 안에 .mst 파일 1개. 인코딩은 cp949(=MS949) 한글.
 *  - "고정폭" 포맷: 한 줄(=한 종목)에서 각 필드가 정해진 글자수 위치에 박혀 있다.
 *  - 앞부분에 단축코드/표준코드/한글명, 뒷부분(고정 길이)에 시세구분 등 메타가 붙는다.
 */
class StockMaster(private val http: HttpClient) {
    // double-checked locking + 24h TTL: 웜 인스턴스가 오래 살아도 신규 상장이 반영된다(S4).
    private val mutex = Mutex()
    @Volatile private var stocks: List<StockInfo>? = null
    @Volatile private var loadedAt = 0L

    /** 전체 종목 목록(24h TTL 캐시). */
    suspend fun all(): List<StockInfo> {
        val now = System.currentTimeMillis()
        stocks?.takeIf { now - loadedAt < 24 * 3_600_000L }?.let { return it }
        return mutex.withLock {
            val now2 = System.currentTimeMillis()
            stocks?.takeIf { now2 - loadedAt < 24 * 3_600_000L }?.let { return it }
            // KOSPI/KOSDAQ 뒷부분 고정 길이가 다르다(228 vs 222) — 아래 loadOne 의 tailLen 설명 참고.
            val loaded = try {
                loadOne(KOSPI_URL, "KOSPI", tailLen = 228) +
                    loadOne(KOSDAQ_URL, "KOSDAQ", tailLen = 222)
            } catch (e: Exception) {
                // TTL 만료 재로드 실패가 검색·findByCode 전체를 죽이면 안 된다 —
                // 기존 데이터가 있으면 유지하고 다음 재시도는 24h 뒤(첫 로드 실패만 전파).
                val prev = stocks
                if (prev != null) {
                    println("[StockMaster] 마스터 재로드 실패(${e.message}) — 기존 ${prev.size}건 유지")
                    loadedAt = System.currentTimeMillis()
                    return prev
                }
                throw e
            }
            stocks = loaded
            loadedAt = System.currentTimeMillis()
            loaded
        }
    }

    /**
     * 검색. 입력이 전부 숫자면 "코드 prefix"로, 아니면 "이름 부분일치"로 찾는다.
     * (사용자가 코드를 치는지 이름을 치는지 모드 전환 없이 자동 판별)
     */
    suspend fun search(q: String, limit: Int = 20): List<StockInfo> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        val all = all()
        return if (query.all { it.isDigit() }) {
            all.filter { it.code.startsWith(query) }.take(limit)
        } else {
            all.filter { it.name.contains(query, ignoreCase = true) }
                // 짧은 이름을 위로: "삼성전기" 검색 시 "삼성전기"가 "삼성전기우"보다 먼저 오게(정확 매칭에 가까움).
                .sortedBy { it.name.length }
                .take(limit)
        }
    }

    /**
     * 코드로 정확히 1건 조회(종목명 해석용). `search(code)`는 영문 섞인 코드(예: 0167A0)를 이름검색으로
     * 오라우팅해 못 찾으므로, 코드→이름 변환은 반드시 이 함수를 쓴다.
     */
    suspend fun findByCode(code: String): StockInfo? = all().firstOrNull { it.code == code }

    /**
     * 마스터 파일 1개(KOSPI 또는 KOSDAQ)를 받아 파싱한다.
     *
     * @param tailLen 줄 끝 고정폭 메타 영역의 길이. 이 길이만큼을 잘라내면 앞부분(코드+이름)만 남는다.
     *                한투 공식 포맷상 KOSPI=228, KOSDAQ=222 로 정해져 있다.
     *
     * 앞부분(front)의 고정 오프셋:
     *   [0:9]   단축코드 (실제 6자리 코드 + 패딩) → trim 후 6자리 숫자만 채택
     *   [9:21]  표준코드(ISIN) — 지금은 안 씀
     *   [21:]   한글 종목명
     */
    private suspend fun loadOne(url: String, market: String, tailLen: Int): List<StockInfo> {
        val bytes: ByteArray = http.get(url).body()
        val mstBytes = unzipFirstEntry(bytes)
        val text = String(mstBytes, charset("MS949")) // cp949로 디코딩해야 한글이 안 깨진다
        val result = ArrayList<StockInfo>()
        for (raw in text.split('\n')) {
            // 줄 끝 캐리지리턴(\r)만 제거. 일반 공백은 고정폭 계산에 영향 주므로 건드리지 않는다.
            val row = raw.trimEnd('\r')
            if (row.length <= tailLen) continue // 빈 줄/꼬리줄 방어
            val front = row.substring(0, row.length - tailLen) // 뒷 메타 잘라내고 앞부분만
            if (front.length < 21) continue // 이름 시작 위치(21)보다 짧으면 비정상 줄
            val code = front.substring(0, 9).trim()
            val name = front.substring(21).trim()
            // 단축코드는 6자리 영숫자. 거래소가 신규 ETF/ETN에 영문 섞인 코드(예: 0167A0 SOL AI반도체TOP2플러스)를
            // 쓰므로 숫자 전용으로 거르면 정상 상장 ETF가 통째로 누락된다 → 6자리 영숫자면 채택.
            if (code.length == 6 && code.all { it.isLetterOrDigit() } && name.isNotEmpty()) {
                result.add(StockInfo(code, name, market))
            }
        }
        return result
    }

    /** zip 바이트에서 첫 엔트리(.mst 파일 1개)의 내용을 꺼낸다. */
    private fun unzipFirstEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            zis.nextEntry ?: return ByteArray(0)
            return zis.readBytes()
        }
    }

    companion object {
        // 한투 공식 공개 다운로드 주소(인증 불필요). 형식이 바뀌면 위 파싱 오프셋도 함께 점검해야 한다.
        private const val KOSPI_URL = "https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip"
        private const val KOSDAQ_URL = "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip"
    }
}
