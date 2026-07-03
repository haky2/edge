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
import com.haky.edge.ai.FileCache
import com.haky.edge.ai.effectiveMarketDate
import com.haky.edge.util.KST
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
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

    // 공시 30분 캐시(인메모리). 공시는 장중 언제든 올라올 수 있어 당일 캐시는 너무 길다 → 30분 버킷.
    // key = "YYYY-MM-DD|버킷번호|days|code". 버킷번호 = epoch-ms / 1,800,000 (30분마다 자동 무효화).
    private val disclosureCache = ConcurrentHashMap<String, List<DartDisclosure>>()

    // 공시 파일 캐시(GCS 영속). 콜드 스타트 시 인메모리 캐시는 날아가지만 같은 30분 버킷이면 파일에서 재사용.
    // FileCache는 키에 오늘 날짜가 있어야 stale 판정을 통과한다 → 키 맨 앞에 YYYY-MM-DD 포함.
    // 버킷이 바뀌면 키(=파일명)가 달라져 자동으로 새로 조회된다(이전 버킷 파일은 그대로 남지만 무시됨).
    private val disclosureFileCache =
        FileCache("dart-disclosure", ListSerializer(DartDisclosure.serializer()))

    /**
     * 종목코드 기준 최근 [days]일 공시 목록. 30분 캐시 적용.
     * 동일 제목·날짜 공시(임원 개별 제출 등)는 "(N건)"으로 그룹핑해 1행으로 표시.
     * corpCode 맵이 없으면 DART에서 다운로드 후 캐시한다.
     * 공시가 없으면 빈 리스트 반환(에러 아님).
     */
    suspend fun getDisclosures(stockCode: String, days: Int = 7): List<DartDisclosure> {
        if (apiKey.isBlank()) throw DartException("DART_API_KEY가 설정되지 않았습니다 (.env 확인)")

        val cacheKey = "${effectiveMarketDate()}|${System.currentTimeMillis() / 1_800_000}|$days|$stockCode"
        disclosureCache[cacheKey]?.let { return it }
        // 콜드 스타트 직후: 같은 30분 버킷이면 GCS 파일에서 재사용(인메모리에도 올림).
        disclosureFileCache.get(cacheKey)?.let { disclosureCache[cacheKey] = it; return it }

        ensureCorpCodeMap()
        val corpCode = corpCodeMap?.get(stockCode)
            ?: return emptyList() // 비상장·ETF 등 매핑 없는 종목

        val bgn = LocalDate.now(KST).minusDays(days.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE)
        val end = LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE)

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

        // 동일 제목·날짜 공시(임원 개별 소유상황 보고 등)를 1행으로 그룹핑. 2건 이상이면 "(N건)" 접미.
        val disclosures = (resp.list ?: emptyList())
            .groupBy { "${it.reportName}|${it.rceptDt}" }
            .map { (_, items) ->
                val first = items.first()
                DartDisclosure(
                    corpName   = first.corpName,
                    reportName = if (items.size > 1) "${first.reportName} (${items.size}건)" else first.reportName,
                    date       = first.rceptDt,
                    url        = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${first.rceptNo}",
                )
            }
        disclosureCache[cacheKey] = disclosures
        disclosureFileCache.put(cacheKey, disclosures) // 콜드 스타트 재사용용 GCS 영속
        return disclosures
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
        val today = LocalDate.now(KST).toString()
        val cacheKey = "$today|$stockCode"
        earningsCache[cacheKey]?.let { return it }

        ensureCorpCodeMap()
        val corpCode = corpCodeMap?.get(stockCode) ?: return null

        val bgn = LocalDate.now(KST).minusMonths(18).format(DateTimeFormatter.BASIC_ISO_DATE)
        val end = LocalDate.now(KST).format(DateTimeFormatter.BASIC_ISO_DATE)

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
        val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(KST), nextDue).toInt()

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

    // 재무 요약 캐시. 연간 재무는 거의 안 바뀌므로 날짜 단위로 캐싱.
    private val financialsCache = ConcurrentHashMap<String, FinancialSummary>() // "date|code" → summary

    /**
     * 종목의 최근 연간 재무 요약(매출·영업이익·순이익 + 전년 동기)을 반환.
     * DART fnlttSinglAcnt.json(사업보고서 reprt_code=11011)으로 당기/전기 금액을 읽는다.
     * 연결(CFS) 우선, 없으면 별도(OFS). 최근 연도부터 역순으로 시도(연간보고서는 다음해 3월 제출).
     * 매핑 없는 종목·API 실패·계정 없음 시 null(분석은 재무 없이 진행).
     */
    suspend fun getFinancials(stockCode: String): FinancialSummary? {
        if (apiKey.isBlank()) return null
        val today = LocalDate.now(KST).toString()
        financialsCache["$today|$stockCode"]?.let { return it }

        ensureCorpCodeMap()
        val corpCode = corpCodeMap?.get(stockCode) ?: return null

        val thisYear = LocalDate.now(KST).year
        for (year in (thisYear - 1) downTo (thisYear - 3)) {
            val resp = runCatching {
                http.get("https://opendart.fss.or.kr/api/fnlttSinglAcnt.json") {
                    parameter("crtfc_key", apiKey)
                    parameter("corp_code", corpCode)
                    parameter("bsns_year", year.toString())
                    parameter("reprt_code", "11011")  // 사업보고서(연간)
                }.body<DartFinanceResponse>()
            }.getOrNull() ?: continue
            if (resp.status != "000") continue
            val rows = resp.list ?: continue
            val summary = buildFinancialSummary(rows, year) ?: continue
            financialsCache["$today|$stockCode"] = summary
            return summary
        }
        return null
    }

    /** fnlttSinglAcnt 행에서 연결(없으면 별도) 매출·영업이익·순이익·자본총계의 당기/전기를 추출. */
    private fun buildFinancialSummary(rows: List<DartFinanceRow>, year: Int): FinancialSummary? {
        // 연결(CFS) 우선, 없으면 별도(OFS).
        val consolidated = rows.any { it.fsDiv == "CFS" }
        val scoped = rows.filter { it.fsDiv == (if (consolidated) "CFS" else "OFS") }

        fun find(vararg names: String): DartFinanceRow? = scoped.firstOrNull { r ->
            names.any { r.accountName.replace(" ", "").contains(it) }
        }
        val rev = find("매출액", "수익(매출액)", "영업수익")
        val op  = find("영업이익")
        val net = find("당기순이익", "분기순이익", "반기순이익")
        // 매출·영업이익 둘 다 못 찾으면 의미 없는 데이터로 보고 건너뜀.
        if (rev == null && op == null) return null

        // 자본총계: 재무상태표(BS) 행에서 추출.
        // 정확 매치(공백 제거 후 완전 일치) 우선 → 없으면 마지막 부분 매치.
        // "지배기업소유주에게귀속되는자본총계" 등 하위 항목보다 "자본총계" 그랜드 토탈을 선택해야 PBR이 정확함.
        val bsScoped = rows.filter { it.fsDiv == (if (consolidated) "CFS" else "OFS") && it.sjDiv == "BS" }
        val equityExactTargets = setOf("자본총계", "자기자본총계", "자본합계")
        val equityRow = bsScoped.firstOrNull { r ->
            equityExactTargets.contains(r.accountName.replace(" ", ""))
        } ?: bsScoped.lastOrNull { r ->
            equityExactTargets.any { r.accountName.replace(" ", "").contains(it) }
        }

        return FinancialSummary(
            fiscalYear = year,
            consolidated = consolidated,
            revenue = rev?.thisAmount(), revenuePrev = rev?.prevAmount(),
            operatingProfit = op?.thisAmount(), operatingProfitPrev = op?.prevAmount(),
            netIncome = net?.thisAmount(), netIncomePrev = net?.prevAmount(),
            equity = equityRow?.thisAmount(),
        )
    }

    // 연도별 재무 캐시. key="date|code|year". ValuationBandService에서 복수 연도 병렬 조회 시 활용.
    private val financialsByYearCache = ConcurrentHashMap<String, FinancialSummary>()

    /**
     * 지정 연도(사업보고서 기준)의 연간 재무 요약을 반환.
     * 매핑 없는 종목·API 실패 시 null(밴드 계산은 가능한 연도만 사용).
     */
    suspend fun getFinancialsForYear(stockCode: String, year: Int): FinancialSummary? {
        if (apiKey.isBlank()) return null
        val today = LocalDate.now(KST).toString()
        val cacheKey = "$today|$stockCode|$year"
        financialsByYearCache[cacheKey]?.let { return it }

        ensureCorpCodeMap()
        val corpCode = corpCodeMap?.get(stockCode) ?: return null

        val resp = runCatching {
            http.get("https://opendart.fss.or.kr/api/fnlttSinglAcnt.json") {
                parameter("crtfc_key", apiKey)
                parameter("corp_code", corpCode)
                parameter("bsns_year", year.toString())
                parameter("reprt_code", "11011")  // 사업보고서(연간)
            }.body<DartFinanceResponse>()
        }.getOrNull() ?: return null
        if (resp.status != "000") return null
        val rows = resp.list ?: return null
        val summary = buildFinancialSummary(rows, year) ?: return null
        financialsByYearCache[cacheKey] = summary
        return summary
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

    // 분기 실적 캐시. key="date|code|q".
    private val quarterlyIncomeCache = ConcurrentHashMap<String, Optional<QuarterlyIncome>>()

    /**
     * 가장 최근 분기/반기보고서에서 당기순이익 + 전년 동기 YoY를 반환.
     * reprt_code 우선순위: 현재연도 1Q(11013) → 전년 3Q(11014) → 전년 반기(11012) → 전년 1Q(11013)
     * 해당 보고서가 없거나(미제출·비상장·API 실패) → null.
     */
    suspend fun getQuarterlyIncome(stockCode: String): QuarterlyIncome? {
        if (apiKey.isBlank()) return null
        val today = LocalDate.now(KST).toString()
        val cacheKey = "$today|$stockCode|q"
        quarterlyIncomeCache[cacheKey]?.let { return it.value }

        ensureCorpCodeMap()
        val corpCode = corpCodeMap?.get(stockCode)
        if (corpCode == null) { quarterlyIncomeCache[cacheKey] = Optional(null); return null }

        val now = LocalDate.now(KST)
        val year = now.year
        val month = now.monthValue

        data class Candidate(val year: Int, val reprtCode: String, val label: String)
        val candidates = buildList {
            if (month >= 5)  add(Candidate(year,     "11013", "${year}년 1분기"))
            if (month >= 8)  add(Candidate(year,     "11012", "${year}년 반기"))
            if (month >= 11) add(Candidate(year,     "11014", "${year}년 3분기"))
            add(Candidate(year - 1, "11014", "${year - 1}년 3분기"))
            add(Candidate(year - 1, "11012", "${year - 1}년 반기"))
            add(Candidate(year - 1, "11013", "${year - 1}년 1분기"))
        }

        for (c in candidates) {
            val resp = runCatching {
                http.get("https://opendart.fss.or.kr/api/fnlttSinglAcnt.json") {
                    parameter("crtfc_key", apiKey)
                    parameter("corp_code", corpCode)
                    parameter("bsns_year", c.year.toString())
                    parameter("reprt_code", c.reprtCode)
                }.body<DartFinanceResponse>()
            }.getOrNull() ?: continue
            if (resp.status != "000") continue
            val rows = resp.list ?: continue

            val consolidated = rows.any { it.fsDiv == "CFS" }
            val scoped = rows.filter { it.fsDiv == (if (consolidated) "CFS" else "OFS") }
            val net = scoped.firstOrNull { r ->
                listOf("당기순이익", "분기순이익", "반기순이익").any { r.accountName.replace(" ", "").contains(it) }
            } ?: continue

            // 누적 기준(thstrm_add_amount)으로 읽는다 — label("반기"/"3분기")과 forwardPerLine 의
            // 연환산 배수(반기×2·3분기×4/3)가 전부 "누적" 전제라, 3개월치(thstrm_amount)를 쓰면
            // 포워드 PER이 반기 2배·3분기 3배 부풀려진다. YoY도 누적 vs 전년 동기 누적으로 정합.
            val ni = net.thisCumulative()
            val niPrev = net.prevCumulative()
            val yoy = if (ni != null && niPrev != null && niPrev != 0L)
                (ni - niPrev).toDouble() / kotlin.math.abs(niPrev) * 100
            else null

            val result = QuarterlyIncome(label = c.label, netIncome = ni, netIncomePrev = niPrev, yoyPct = yoy)
            quarterlyIncomeCache[cacheKey] = Optional(result)
            return result
        }

        quarterlyIncomeCache[cacheKey] = Optional(null)
        return null
    }

    // ConcurrentHashMap은 null value를 넣을 수 없어 Optional로 감싼다.
    private data class Optional<T>(val value: T?)

    /** 서버 시작 시 미리 호출해 첫 번째 /dart 요청의 ZIP 다운로드 지연을 없앤다. */
    suspend fun warmup() { ensureCorpCodeMap() }

    // corpCode 맵 파일 캐시. 3.5MB ZIP 다운로드 + 30MB XML SAX 파싱은 콜드 스타트마다 ~2-4초가 든다.
    // 파싱 결과(stock_code→corp_code, ~90KB JSON)를 GCS 마운트(CACHE_DIR)에 저장해 콜드 스타트에 재사용한다.
    // corp_code는 기존 상장사면 거의 안 바뀌고 신규 상장만 추가됨 → 7일마다만 갱신(아래 mtime 체크).
    private val corpCodeFile =
        File("${System.getenv("CACHE_DIR") ?: ".cache"}/corpcode/map.json").also { it.parentFile?.mkdirs() }
    private val corpCodeMapJson = Json { ignoreUnknownKeys = true }
    private val CORP_CODE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7일

    // corpCode 맵을 최초 1회만 로드한다(Mutex로 중복 작업 방지).
    // 우선순위: 인메모리 → 파일(7일 이내) → DART 다운로드·파싱(후 파일 저장).
    private suspend fun ensureCorpCodeMap() {
        if (corpCodeMap != null) return
        mapMutex.withLock {
            if (corpCodeMap != null) return
            loadCorpCodeFromFile()?.let { corpCodeMap = it; return }
            corpCodeMap = downloadAndParseCorpCodeMap().also { saveCorpCodeToFile(it) }
        }
    }

    /** 파일 캐시가 7일 이내면 맵을 반환, 아니면 null(다운로드 유도). 파싱 실패도 null. */
    private fun loadCorpCodeFromFile(): Map<String, String>? = try {
        if (!corpCodeFile.exists()) null
        else if (System.currentTimeMillis() - corpCodeFile.lastModified() > CORP_CODE_TTL_MS) null // 오래됨 → 재다운로드
        else corpCodeMapJson.decodeFromString<Map<String, String>>(corpCodeFile.readText())
            .takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null // 손상 등은 무시하고 새로 다운로드
    }

    /** 파싱한 맵을 파일에 저장(다음 콜드 스타트 재사용). 실패해도 동작엔 지장 없음. */
    private fun saveCorpCodeToFile(map: Map<String, String>) {
        try {
            corpCodeFile.writeText(corpCodeMapJson.encodeToString(map))
        } catch (_: Exception) {
            // 저장 실패 시 다음 콜드 스타트에 재다운로드될 뿐
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

// ── 재무 요약(분석 facts용, 내부 사용) ──────────────────────────────────────

/**
 * 최근 연간 재무 요약. 금액 단위는 원(DART 원본). null = 해당 계정 없음.
 * prev = 직전 사업연도 동일 계정(전년比 계산용).
 * equity = 자본총계(재무상태표 BS 항목, 밸류에이션 밴드 BPS 계산용).
 */
data class FinancialSummary(
    val fiscalYear: Int,
    val consolidated: Boolean,        // true=연결(CFS), false=별도(OFS)
    val revenue: Long?, val revenuePrev: Long?,
    val operatingProfit: Long?, val operatingProfitPrev: Long?,
    val netIncome: Long?, val netIncomePrev: Long?,
    val equity: Long? = null,         // 자본총계(BS, ValuationBand용)
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

@Serializable
private data class DartFinanceResponse(
    val status: String = "",
    val message: String = "",
    val list: List<DartFinanceRow>? = null,
)

@Serializable
internal data class DartFinanceRow(
    @SerialName("fs_div")            val fsDiv: String = "",        // CFS=연결, OFS=별도
    @SerialName("sj_div")            val sjDiv: String = "",        // BS/IS/CIS/CF
    @SerialName("account_nm")        val accountName: String = "",  // "매출액","영업이익","당기순이익"
    @SerialName("thstrm_amount")     val thisAmount: String = "",   // 당기금액
    @SerialName("frmtrm_amount")     val prevAmount: String = "",   // 전기금액
    // ⚠️ 반기·3분기 보고서의 손익 계정에서 thstrm_amount 는 해당 3개월치이고, 누적은 이 add 필드에 온다
    // (예: 삼성전자 2025 반기 — thstrm 5.1조=2Q 3개월, add 13.3조=상반기 누적. 2026-07 감사 H2).
    // 1분기는 두 값이 같고, 연간(11011)·BS 계정엔 add 필드가 없다(빈값 → 아래 폴백).
    @SerialName("thstrm_add_amount") val thisAddAmount: String = "", // 당기 누적금액(분기/반기 IS 전용)
    @SerialName("frmtrm_add_amount") val prevAddAmount: String = "", // 전년 동기 누적금액
) {
    // DART 금액은 콤마 포함 문자열("1,234,567"), 음수·빈값 가능 → 안전 파싱.
    fun thisAmount(): Long? = thisAmount.parseAmount()
    fun prevAmount(): Long? = prevAmount.parseAmount()

    /** 당기 누적. add 필드가 없으면(연간 보고서 등) thstrm_amount 폴백. */
    fun thisCumulative(): Long? = thisAddAmount.parseAmount() ?: thisAmount()

    /** 전년 동기 누적. 폴백 동일. */
    fun prevCumulative(): Long? = prevAddAmount.parseAmount() ?: prevAmount()

    private fun String.parseAmount(): Long? = replace(",", "").trim().toLongOrNull()
}

// ── 분기 실적 요약(분석 facts용, 내부 사용) ────────────────────────────────────

/**
 * 가장 최근 분기/반기보고서의 당기순이익 + 전년 동기 비교.
 * netIncome/netIncomePrev 단위는 원(DART 원본). yoyPct=null이면 전년 동기 없음.
 */
data class QuarterlyIncome(
    val label: String,          // "2026년 1분기", "2025년 3분기" 등
    val netIncome: Long?,       // 당기 순이익(누적)
    val netIncomePrev: Long?,   // 전년 동기 순이익(누적)
    val yoyPct: Double?,        // YoY % (null = 비교 불가)
)

class DartException(message: String) : RuntimeException(message)
