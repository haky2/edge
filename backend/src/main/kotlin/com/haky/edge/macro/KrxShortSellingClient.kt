package com.haky.edge.macro

import com.haky.edge.util.KST
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

// ── 앱에 내려주는 공매도 요약 DTO ─────────────────────────────────────────────

@Serializable
data class ShortSellingSummary(
    val code: String,
    val recentVolume: Long,         // 최근 거래일 공매도 거래량 (주)
    val recentVolumeDate: String,   // 해당 거래일 ("2026/06/05")
    val balance: Long?,             // 최신 공매도 잔고 (주), T+2 delay — null 이면 집계 중
    val balanceDate: String?,       // 잔고 기준일
    val balanceChangePct: Double?,  // 전 확정일 대비 잔고 변화율 (%)
)

// ── 내부 파싱용 ────────────────────────────────────────────────────────────────

private data class ShortSellingEntry(
    val date: String,
    val volume: Long,
    val balance: Long?,
)

/**
 * KRX 공매도 종합 포탈(data.krx.co.kr)에서 종목별 일별 공매도 거래량·잔고를 수집한다.
 *
 * 데이터 흐름(3단계):
 *  1. GET srtLoader iframe → JSESSIONID 쿠키 확보 (세션 당 1회)
 *  2. POST get_srtisu → 6자리 코드 → KRX ISIN (종목별 1회, 인메모리 캐시)
 *  3. POST MDCSTAT30001_OUT → 일별 공매도 거래량·잔고 (당일 캐시)
 *
 * 참고: 공매도 잔고는 T+2 영업일 지연 확정이므로 최근 2거래일 데이터는 없을 수 있다.
 */
class KrxShortSellingClient {
    private val http = HttpClient(CIO)

    @Volatile private var jsessionId: String? = null
    private val isinCache = ConcurrentHashMap<String, String>()          // code → KRX ISIN
    private val dataCache = ConcurrentHashMap<String, ShortSellingSummary>() // "$code:$date" → summary
    private val dtf: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    companion object {
        private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val KRX = "https://data.krx.co.kr"
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    suspend fun getShortSelling(code: String): ShortSellingSummary? {
        val today = LocalDate.now(KST).toString()
        dataCache["$code:$today"]?.let { return it }

        ensureSession(code)
        val isin = resolveIsin(code) ?: return null
        val entries = fetchEntries(isin, code).takeIf { it.isNotEmpty() } ?: return null

        val summary = buildSummary(code, entries)
        dataCache["$code:$today"] = summary
        return summary
    }

    // 처음 한 번만 KRX srtLoader 페이지를 방문해 JSESSIONID 쿠키를 확보한다.
    private suspend fun ensureSession(code: String) {
        if (jsessionId != null) return
        runCatching {
            val resp = http.get("$KRX/comm/srt/srtLoader/index.cmd?screenId=MDCSTAT300&isuCd=$code") {
                header("User-Agent", UA)
                header("Referer", "https://finance.naver.com/item/short_trade.naver?code=$code")
            }
            val setCookie = resp.headers.getAll("Set-Cookie")
                ?.joinToString(";") ?: ""
            jsessionId = Regex("JSESSIONID=([^;]+)").find(setCookie)?.groupValues?.get(1) ?: "INIT"
        }
        if (jsessionId == null) jsessionId = "INIT"
    }

    // 6자리 KIS 코드 → KRX 내부 ISIN (예: 018260 → KR7018260000)
    private suspend fun resolveIsin(code: String): String? {
        isinCache[code]?.let { return it }
        val sess = jsessionId ?: return null

        val resp: String = runCatching {
            http.submitForm(
                url = "$KRX/comm/bldAttendant/getJsonData.cmd?bld=dbms/comm/finder/get_srtisu",
                formParameters = parameters { append("isuCd", code) }
            ) {
                header("User-Agent", UA)
                header("Referer", "$KRX/comm/srt/srtLoader/index.cmd?screenId=MDCSTAT300&isuCd=$code")
                header("Cookie", "JSESSIONID=$sess")
            }.body<String>()
        }.getOrElse { return null }

        val isin = runCatching {
            JSON.parseToJsonElement(resp).jsonObject["output"]
                ?.jsonArray?.getOrNull(0)?.jsonObject?.get("code")?.jsonPrimitive?.content
        }.getOrNull() ?: return null

        isinCache[code] = isin
        return isin
    }

    private suspend fun fetchEntries(isin: String, code: String): List<ShortSellingEntry> {
        val today = LocalDate.now(KST)
        val start = today.minusWeeks(3).format(dtf)
        val end = today.format(dtf)
        val sess = jsessionId ?: return emptyList()

        val resp: String = runCatching {
            http.submitForm(
                url = "$KRX/comm/bldAttendant/getJsonData.cmd",
                formParameters = parameters {
                    append("bld", "dbms/MDC_OUT/STAT/srt/MDCSTAT30001_OUT")
                    append("isuCd", isin)
                    append("strtDd", start)
                    append("endDd", end)
                    append("share", "1")
                    append("money", "1")
                    append("csvxls_isNo", "false")
                }
            ) {
                header("User-Agent", UA)
                header("Referer", "$KRX/comm/srt/srtLoader/index.cmd?screenId=MDCSTAT300&isuCd=$code")
                header("Cookie", "JSESSIONID=$sess")
            }.body<String>()
        }.getOrElse { return emptyList() }

        return runCatching {
            val block = JSON.parseToJsonElement(resp).jsonObject["OutBlock_1"]?.jsonArray
                ?: return@runCatching emptyList()
            block.map { row ->
                val r = row.jsonObject
                ShortSellingEntry(
                    date = r["TRD_DD"]?.jsonPrimitive?.content ?: "",
                    volume = r["CVSRTSELL_TRDVOL"]?.jsonPrimitive?.content
                        ?.replace(",", "")?.toLongOrNull() ?: 0L,
                    balance = r["STR_CONST_VAL1"]?.jsonPrimitive?.content
                        ?.takeIf { it != "-" }?.replace(",", "")?.toLongOrNull(),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun buildSummary(code: String, entries: List<ShortSellingEntry>): ShortSellingSummary {
        val latest = entries.first()
        val balanceEntries = entries.filter { it.balance != null }
        val latestBalance = balanceEntries.getOrNull(0)
        val prevBalance = balanceEntries.getOrNull(1)

        val changePct = if (latestBalance != null && prevBalance?.balance != null && prevBalance.balance > 0) {
            (latestBalance.balance!! - prevBalance.balance).toDouble() / prevBalance.balance * 100
        } else null

        return ShortSellingSummary(
            code = code,
            recentVolume = latest.volume,
            recentVolumeDate = latest.date,
            balance = latestBalance?.balance,
            balanceDate = latestBalance?.date,
            balanceChangePct = changePct,
        )
    }
}
