package com.stockapp.master

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

@Serializable
data class StockInfo(
    val code: String,
    val name: String,
    val market: String, // KOSPI | KOSDAQ
)

/**
 * 한투가 공개로 제공하는 종목 마스터(.mst) 파일을 받아 코드↔이름 인덱스를 만든다.
 * - 인증 불필요(공개 다운로드), 최초 호출 시 1회 로드 후 메모리 캐시.
 * - 파일은 cp949(MS949) 인코딩, 고정폭 포맷. 앞부분에 단축코드/표준코드/한글명이 있다.
 */
class StockMaster(private val http: HttpClient) {
    private val mutex = Mutex()
    @Volatile private var stocks: List<StockInfo>? = null

    suspend fun all(): List<StockInfo> {
        stocks?.let { return it }
        return mutex.withLock {
            stocks?.let { return it }
            val loaded = loadOne(KOSPI_URL, "KOSPI", tailLen = 228) +
                loadOne(KOSDAQ_URL, "KOSDAQ", tailLen = 222)
            stocks = loaded
            loaded
        }
    }

    /** 숫자면 코드 prefix, 아니면 이름 부분일치. */
    suspend fun search(q: String, limit: Int = 20): List<StockInfo> {
        val query = q.trim()
        if (query.isEmpty()) return emptyList()
        val all = all()
        return if (query.all { it.isDigit() }) {
            all.filter { it.code.startsWith(query) }.take(limit)
        } else {
            all.filter { it.name.contains(query, ignoreCase = true) }
                .sortedBy { it.name.length } // 짧은 이름(정확 매칭에 가까움) 우선
                .take(limit)
        }
    }

    private suspend fun loadOne(url: String, market: String, tailLen: Int): List<StockInfo> {
        val bytes: ByteArray = http.get(url).body()
        val mstBytes = unzipFirstEntry(bytes)
        val text = String(mstBytes, charset("MS949"))
        val result = ArrayList<StockInfo>()
        for (raw in text.split('\n')) {
            val row = raw.trimEnd('\r')
            if (row.length <= tailLen) continue
            val front = row.substring(0, row.length - tailLen)
            if (front.length < 21) continue
            val code = front.substring(0, 9).trim()
            val name = front.substring(21).trim()
            if (code.length == 6 && code.all { it.isDigit() } && name.isNotEmpty()) {
                result.add(StockInfo(code, name, market))
            }
        }
        return result
    }

    private fun unzipFirstEntry(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            zis.nextEntry ?: return ByteArray(0)
            return zis.readBytes()
        }
    }

    companion object {
        private const val KOSPI_URL = "https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip"
        private const val KOSDAQ_URL = "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip"
    }
}
