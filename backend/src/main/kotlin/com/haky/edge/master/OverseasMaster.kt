package com.haky.edge.master

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** 해외 종목 검색 결과 1건. 코드는 "US:NAS:AAPL" 형식(OverseasRoutes와 동일 규약). */
@Serializable
data class OverseasStockInfo(
    val code: String,      // "US:NAS:AAPL" (앱이 시세 조회에 그대로 사용)
    val symb: String,      // "AAPL"
    val name: String,      // 한글명 (없으면 영문명)
    val nameEn: String,    // 영문명
    val market: String,    // "NAS", "NYS", "AMS"
    val currency: String,  // "USD"
)

/**
 * 한투 공개 해외 종목마스터(.cod.zip)를 받아 심볼·이름 인덱스를 만든다.
 *
 * 파일 포맷: 탭(\t) 구분, CP949(MS949) 인코딩.
 * 필드 순서: 국가코드[0] | 국가번호[1] | 거래소코드(excd)[2] | 거래소명[3] |
 *            심볼[4] | 실시간코드[5] | 한글명[6] | 영문명[7] | 구분[8] | 통화[9] | ...
 *
 * StockMaster(국내)와 동일한 double-checked locking + 첫 호출 시 lazy 로드.
 */
class OverseasMaster(private val http: HttpClient) {
    private val mutex = Mutex()
    @Volatile private var stocks: List<OverseasStockInfo>? = null

    /** 전체 해외 종목 목록(최초 호출 때 로드, 이후 캐시). NAS+NYS+AMS 합산. */
    suspend fun all(): List<OverseasStockInfo> {
        stocks?.let { return it }
        return mutex.withLock {
            stocks?.let { return it }
            val loaded = loadOne(NAS_URL) + loadOne(NYS_URL) + loadOne(AMS_URL)
            stocks = loaded
            loaded
        }
    }

    /** 코드("US:NAS:AAPL") 정확 일치 1건. 코드→이름 변환은 search()가 아니라 이것을 쓴다. */
    suspend fun findByCode(code: String): OverseasStockInfo? =
        all().firstOrNull { it.code == code }

    /**
     * 검색. 전부 ASCII + 소문자 없음(예: AAPL, 7203, BRK.B) → 심볼 prefix.
     * 그 외(소문자·한글 등: apple, 애플) → 한글명+영문명 부분 일치.
     */
    suspend fun search(q: String, limit: Int = 20): List<OverseasStockInfo> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        val all = all()
        val likelyTicker = query.all { it.code < 128 } && query.none { it.isLowerCase() }
        return if (likelyTicker) {
            all.filter { it.symb.startsWith(query, ignoreCase = true) }
                .sortedBy { it.symb.length }
                .take(limit)
        } else {
            all.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.nameEn.contains(query, ignoreCase = true)
            }.sortedBy { it.name.length }.take(limit)
        }
    }

    private suspend fun loadOne(url: String): List<OverseasStockInfo> = try {
        val bytes: ByteArray = http.get(url).body()
        val codBytes = unzipFirstEntry(bytes)
        val text = String(codBytes, charset("MS949"))
        val result = ArrayList<OverseasStockInfo>()
        for (raw in text.split('\n')) {
            val fields = raw.trimEnd('\r').split('\t')
            if (fields.size < 8) continue
            val excd = fields[2].trim()
            val symb = fields[4].trim()
            val nameKr = fields[6].trim()
            val nameEn = fields[7].trim()
            val currency = if (fields.size > 9) fields[9].trim() else "USD"
            if (symb.isBlank() || excd.isBlank()) continue
            // 심볼은 알파벳·숫자·점·하이픈·슬래시만 허용(특수문자나 공백이 있는 비정상 행 방어)
            if (!symb.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '/' }) continue
            result.add(
                OverseasStockInfo(
                    code = "US:$excd:$symb",
                    symb = symb,
                    name = nameKr.ifEmpty { nameEn },
                    nameEn = nameEn,
                    market = excd,
                    currency = currency.ifEmpty { "USD" },
                )
            )
        }
        result
    } catch (_: Exception) {
        emptyList() // 개별 파일 로드 실패는 무시(부분 결과 허용)
    }

    private fun unzipFirstEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            zis.nextEntry ?: return ByteArray(0)
            return zis.readBytes()
        }
    }

    companion object {
        private const val NAS_URL = "https://new.real.download.dws.co.kr/common/master/nasmst.cod.zip"
        private const val NYS_URL = "https://new.real.download.dws.co.kr/common/master/nysmst.cod.zip"
        private const val AMS_URL = "https://new.real.download.dws.co.kr/common/master/amsmst.cod.zip"
    }
}
