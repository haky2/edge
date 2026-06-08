package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.ClaudeClient
import com.haky.edge.ai.ClaudeException
import com.haky.edge.dart.DartClient
import com.haky.edge.dart.DartException
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.KisException
import com.haky.edge.macro.CopperClient
import com.haky.edge.macro.EcosClient
import com.haky.edge.macro.FearGreedClient
import com.haky.edge.macro.KrxShortSellingClient
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.MarketMoodService
import com.haky.edge.macro.SectorBriefingService
import com.haky.edge.macro.YahooMacroClient
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NaverTargetPriceClient
import com.haky.edge.news.NewsException
import com.haky.edge.routes.analysisRoutes
import com.haky.edge.routes.chartRoutes
import com.haky.edge.routes.dartRoutes
import com.haky.edge.routes.earningsRoutes
import com.haky.edge.routes.investorRoutes
import com.haky.edge.routes.macroImpactRoutes
import com.haky.edge.routes.macroRoutes
import com.haky.edge.routes.marketMoodRoutes
import com.haky.edge.routes.newsRoutes
import com.haky.edge.routes.quoteRoutes
import com.haky.edge.routes.searchRoutes
import com.haky.edge.routes.sectorBriefingRoutes
import com.haky.edge.routes.sectorRoutes
import com.haky.edge.routes.shortSellingRoutes
import com.haky.edge.routes.targetPriceRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() {
    // PORT는 환경변수에서 읽는다. Cloud Run은 컨테이너에 PORT를 주입하므로 거기에 맞춰야 하고,
    // 로컬에선 .env 의 PORT(기본 8080)를 쓴다.
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // host="0.0.0.0" : 모든 네트워크 인터페이스에서 수신.
    // 127.0.0.1 로 묶으면 안드로이드 에뮬레이터(10.0.2.2)나 실기기에서 접근이 막힌다.
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

/** 에러를 항상 같은 JSON 모양({"error": "..."})으로 내려주기 위한 응답 DTO. */
@Serializable
data class ErrorResponse(val error: String)

fun Application.module() {
    install(ContentNegotiation) {
        // ignoreUnknownKeys=true : 한투 응답엔 우리가 안 쓰는 필드가 수십 개라, 모르는 키는 무시해야
        //                          역직렬화가 깨지지 않는다. prettyPrint 는 개발 중 가독성용.
        json(Json { ignoreUnknownKeys = true; prettyPrint = true })
    }
    install(StatusPages) {
        // 예외를 한 곳에서 잡아 일관된 에러 JSON으로 변환한다(각 라우트에서 try/catch 반복 안 함).
        exception<Throwable> { call, cause ->
            // KisException = 외부(한투) 호출이 잘못된 경우 → 502 Bad Gateway(우리 서버가 아닌 상류 문제).
            // 그 외 = 우리 코드의 버그 → 500. 앱이 이 구분으로 "재시도 vs 버그제보"를 판단할 수 있다.
            val status = when (cause) {
                is KisException, is NewsException, is ClaudeException,
                is DartException -> HttpStatusCode.BadGateway
                else -> HttpStatusCode.InternalServerError
            }
            call.respond(status, ErrorResponse(cause.message ?: cause.toString()))
        }
    }

    // 키는 코드/깃에 절대 두지 않고 환경변수로만 주입한다(run.sh 가 .env 를 읽어 넣어줌).
    // 값이 없으면 빈 문자열 → 실제 호출 시점에 KisClient 가 친절한 에러를 던진다.
    val kis = KisClient(
        appKey = System.getenv("KIS_APP_KEY").orEmpty(),
        appSecret = System.getenv("KIS_APP_SECRET").orEmpty(),
        // 실전/모의 서버 전환은 URL만 바꾸면 된다(모의는 :29443). 기본은 실전.
        baseUrl = System.getenv("KIS_BASE_URL") ?: "https://openapi.koreainvestment.com:9443",
    )
    // 종목 마스터는 인증이 필요 없는 공개 다운로드라 별도의 평범한 HttpClient 를 쓴다(KisClient 와 분리).
    val master = StockMaster(HttpClient(CIO))
    val naver = NaverNewsClient(
        clientId = System.getenv("NAVER_CLIENT_ID").orEmpty(),
        clientSecret = System.getenv("NAVER_CLIENT_SECRET").orEmpty(),
    )
    // Claude 분석. 모델은 CLAUDE_MODEL 로 덮어쓸 수 있고 기본은 Sonnet 4.6(비용/속도).
    val claude = ClaudeClient(
        apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty(),
        model = System.getenv("CLAUDE_MODEL") ?: "claude-sonnet-4-6",
    )
    val dart = DartClient(apiKey = System.getenv("DART_API_KEY").orEmpty())
    val naverTargetPrice = NaverTargetPriceClient()
    val fearGreed = FearGreedClient()
    val copper = CopperClient()
    val ecos = EcosClient(apiKey = System.getenv("ECOS_API_KEY").orEmpty())
    val yahoo = YahooMacroClient()
    val macroImpact = MacroImpactService(kis, master, claude, fearGreed, copper, ecos, naver, yahoo)
    val krxShortSelling = KrxShortSellingClient()
    val analysis = AnalysisService(kis, naver, master, claude, dart, naverTargetPrice, macroImpact, krxShortSelling)
    val marketMood = MarketMoodService(kis, claude, fearGreed, copper, ecos, yahoo)
    val sectorBriefing = SectorBriefingService(kis, master, claude, macroImpact)

    routing {
        get("/health") { call.respondText("OK") } // 배포/모니터링용 헬스체크
        quoteRoutes(kis)
        chartRoutes(kis)
        investorRoutes(kis)
        macroRoutes(kis, fearGreed, copper, ecos, yahoo)
        macroImpactRoutes(macroImpact)
        marketMoodRoutes(marketMood)
        newsRoutes(naver)
        searchRoutes(master)
        analysisRoutes(analysis)
        dartRoutes(dart)
        earningsRoutes(dart)
        sectorRoutes(kis)
        sectorBriefingRoutes(sectorBriefing)
        shortSellingRoutes(krxShortSelling)
        targetPriceRoutes(naverTargetPrice)
    }
}
