package com.haky.edge.dart

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * DART(전자공시시스템) OpenAPI 클라이언트.
 *
 * DART는 종목코드(6자리)가 아닌 corp_code(8자리 고유번호)로 공시를 조회한다.
 * 매핑 방법: DART의 corpCode.xml ZIP(전체 기업 목록)을 한 번 다운로드해
 * stock_code → corp_code 인메모리 맵을 구성하고 이후 재사용한다.
 * 파일은 ~2MB, 앱 재시작 전까지 유효하다.
 */
class DartClient(private val apiKey: String) {

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    // stock_code(6자리) → corp_code(8자리) 전체 맵. 최초 요청 시 1회 로드.
    private var corpCodeMap: Map<String, String>? = null
    private val mapMutex = Mutex()

    /**
     * 종목코드 기준 최근 [days]일 공시 목록.
     * corpCode 맵이 없으면 DART에서 다운로드 후 캐시한다.
     * 공시가 없으면 빈 리스트 반환(에러 아님).
     */
    suspend fun getDisclosures(stockCode: String, days: Int = 7): List<DartDisclosure> {
        if (apiKey.isBlank()) throw DartException("DART_API_KEY가 설정되지 않았습니다 (.env 확인)")

        ensureCorpCodeMap()
        val corpCode = corpCodeMap?.get(stockCode)
            ?: return emptyList() // 비상장·ETF 등 매핑 없는 종목

        val bgn = LocalDate.now().minusDays(days.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE)
        val end = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

        val resp: DartListResponse = http.get("https://opendart.fss.or.kr/api/list.json") {
            parameter("crtfc_key", apiKey)
            parameter("corp_code", corpCode)
            parameter("bgn_de", bgn)
            parameter("end_de", end)
        }.body()

        // status "013" = 조회 결과 없음 (에러 아님)
        if (resp.status != "000" && resp.status != "013") {
            throw DartException("DART list API 오류: ${resp.message} (status=${resp.status})")
        }

        return (resp.list ?: emptyList()).map { item ->
            DartDisclosure(
                corpName   = item.corpName,
                reportName = item.reportName,
                date       = item.rceptDt,
                url        = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${item.rceptNo}",
            )
        }
    }

    // 당일 실적 일정 캐시. 정기공시 제출 기한은 하루 안에 바뀌지 않으므로 날짜 단위로 캐싱.
    private val earningsCache = ConcurrentHashMap<String, EarningsEntry>() // "date|code" → entry
    private var earningsCacheDate = ""

    /**
     * 종목의 다음 정기공시(분기/반기/사업보고서) 예정일 및 D-day를 반환.
     * DART pblntf_ty=A(정기공시)로 최근 18개월 목록 조회 → 가장 최근 보고서로부터 다음 예정 계산.
     * 매핑 없는 종목, API 실패, 패턴 불일치 시 null.
     */
    suspend fun getEarningsSchedule(stockCode: String): EarningsEntry? {
        if (apiKey.isBlank()) return null
        val today = LocalDate.now().toString()
        val cacheKey = "$today|$stockCode"
        earningsCache[cacheKey]?.let { return it }

        ensureCorpCodeMap()
        val corpCode = corpCodeMap?.get(stockCode) ?: return null

        val bgn = LocalDate.now().minusMonths(18).format(DateTimeFormatter.BASIC_ISO_DATE)
        val end = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

        val resp: DartListResponse = runCatching {
            http.get("https://opendart.fss.or.kr/api/list.json") {
                parameter("crtfc_key", apiKey)
                parameter("corp_code", corpCode)
                parameter("pblntf_ty", "A")  // 정기공시(분기/반기/사업보고서)만
                parameter("bgn_de", bgn)
                parameter("end_de", end)
                parameter("page_count", "10")
            }.body<DartListResponse>()
        }.getOrNull() ?: return null

        if (resp.status != "000" && resp.status != "013") return null
        val latest = (resp.list ?: emptyList())
            .firstOrNull { isPeriodicReport(it.reportName) } ?: return null

        val (nextName, nextDue) = nextExpected(latest.reportName) ?: return null
        val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), nextDue).toInt()

        val entry = EarningsEntry(
            code       = stockCode,
            corpName   = latest.corpName,
            reportName = nextName,
            dueDate    = nextDue.format(DateTimeFormatter.BASIC_ISO_DATE),
            daysUntil  = daysUntil,
        )
        earningsCache[cacheKey] = entry
        return entry
    }

    private fun isPeriodicReport(name: String) =
        name.contains("분기보고서") || name.contains("반기보고서") || name.contains("사업보고서")

    /**
     * 보고서명에서 다음 제출 예정 보고서와 법정 마감일을 계산.
     * 자본시장법 기준: 분기/반기보고서 45일, 사업보고서 90일(KOSPI 기업 기준).
     */
    private fun nextExpected(reportName: String): Pair<String, LocalDate>? {
        val m = Regex("""\((\d{4})\.(\d{2})\)""").find(reportName) ?: return null
        val year  = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        return when {
            reportName.contains("분기보고서")  && month == 3  -> "반기보고서 (${year}.06)"   to LocalDate.of(year,     8, 14)
            reportName.contains("반기보고서")  && month == 6  -> "분기보고서 (${year}.09)"   to LocalDate.of(year,     11, 14)
            reportName.contains("분기보고서")  && month == 9  -> "사업보고서 (${year}.12)"   to LocalDate.of(year + 1, 3,  31)
            reportName.contains("사업보고서")  && month == 12 -> "분기보고서 (${year + 1}.03)" to LocalDate.of(year + 1, 5,  15)
            else -> null
        }
    }

    // corpCode 맵을 최초 1회만 다운로드·파싱한다(Mutex로 중복 다운로드 방지).
    private suspend fun ensureCorpCodeMap() {
        if (corpCodeMap != null) return
        mapMutex.withLock {
            if (corpCodeMap != null) return
            corpCodeMap = downloadAndParseCorpCodeMap()
        }
    }

    private suspend fun downloadAndParseCorpCodeMap(): Map<String, String> {
        val bytes: ByteArray = http.get("https://opendart.fss.or.kr/api/corpCode.xml") {
            parameter("crtfc_key", apiKey)
        }.bodyAsBytes()

        // ZIP 안의 CORPCODE.xml을 SAX로 파싱 → stock_code → corp_code 맵
        val map = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "CORPCODE.xml") {
                    val xmlBytes = zip.readBytes()
                    parseCorpCodeXml(xmlBytes, map)
                    break
                }
            }
        }
        return map
    }

    private fun parseCorpCodeXml(xmlBytes: ByteArray, map: MutableMap<String, String>) {
        var corpCode = ""
        var stockCode = ""
        var currentElement = ""

        SAXParserFactory.newInstance().newSAXParser().parse(
            ByteArrayInputStream(xmlBytes),
            object : DefaultHandler() {
                override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
                    currentElement = qName
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    val text = String(ch, start, length).trim()
                    if (text.isEmpty()) return
                    when (currentElement) {
                        "corp_code"  -> corpCode  = text
                        "stock_code" -> stockCode = text
                    }
                }
                override fun endElement(uri: String, local: String, qName: String) {
                    // CORPCODE.xml의 개별 기업 항목 태그는 <list>
                    if (qName == "list") {
                        if (stockCode.isNotBlank()) map[stockCode] = corpCode
                        corpCode = ""; stockCode = ""
                    }
                    currentElement = ""
                }
            }
        )
    }
}

// ── 앱에 내려주는 실적 일정 1건 ─────────────────────────────────────────────

/** 다음 정기공시 예정 1건. daysUntil: 양수=남은 일수, 0=당일, 음수=제출 기한 지남. */
@Serializable
data class EarningsEntry(
    val code: String,
    val corpName: String,
    val reportName: String,  // "반기보고서 (2026.06)"
    val dueDate: String,     // "20260814"
    val daysUntil: Int,
)

// ── 앱에 내려주는 공시 1건 ──────────────────────────────────────────────────

@Serializable
data class DartDisclosure(
    val corpName: String,
    val reportName: String,
    val date: String,   // YYYYMMDD
    val url: String,
)

// ── DART API 응답 내부 모델 ──────────────────────────────────────────────────

@Serializable
private data class DartListResponse(
    val status: String = "",
    val message: String = "",
    val list: List<DartListItem>? = null,
)

@Serializable
private data class DartListItem(
    @SerialName("corp_name")  val corpName: String = "",
    @SerialName("report_nm")  val reportName: String = "",
    @SerialName("rcept_no")   val rceptNo: String = "",
    @SerialName("rcept_dt")   val rceptDt: String = "",
)

class DartException(message: String) : RuntimeException(message)
