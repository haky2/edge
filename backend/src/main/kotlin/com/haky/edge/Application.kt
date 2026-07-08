package com.haky.edge

import com.haky.edge.ai.AnalysisService
import com.haky.edge.ai.BacktestService
import com.haky.edge.ai.CatalystService
import com.haky.edge.ai.ClaudeClient
import com.haky.edge.ai.ClaudeException
import com.haky.edge.ai.ClaudeUsageTracker
import com.haky.edge.ai.ComparisonService
import com.haky.edge.ai.ModelRouter
import com.haky.edge.ai.PeerValuationService
import com.haky.edge.ai.ValuationBandService
import com.haky.edge.dart.DartClient
import com.haky.edge.dart.DartException
import com.haky.edge.kis.KisClient
import com.haky.edge.kis.KisException
import com.haky.edge.toss.TossClient
import com.haky.edge.macro.CopperClient
import com.haky.edge.macro.EcosClient
import com.haky.edge.macro.FearGreedClient
import com.haky.edge.macro.KrxShortSellingClient
import com.haky.edge.macro.MacroImpactService
import com.haky.edge.macro.MarketMoodLogService
import com.haky.edge.macro.MarketMoodService
import com.haky.edge.macro.EventSyncService
import com.haky.edge.macro.SectorBriefingService
import com.haky.edge.macro.YahooMacroClient
import com.haky.edge.master.OverseasMaster
import com.haky.edge.master.StockMaster
import com.haky.edge.news.NaverNewsClient
import com.haky.edge.news.NaverTargetPriceClient
import com.haky.edge.news.TargetPriceLogService
import com.haky.edge.news.NewsException
import com.haky.edge.routes.analogRoutes
import com.haky.edge.routes.analysisRoutes
import com.haky.edge.routes.stanceStatsRoutes
import com.haky.edge.routes.askRoutes
import com.haky.edge.routes.portfolioReviewRoutes
import com.haky.edge.routes.backtestRoutes
import com.haky.edge.routes.catalystImpactRoutes
import com.haky.edge.routes.catalystRoutes
import com.haky.edge.routes.comparisonRoutes
import com.haky.edge.routes.eventRoutes
import com.haky.edge.routes.chartRoutes
import com.haky.edge.routes.dartRoutes
import com.haky.edge.routes.earningsPreviewRoutes
import com.haky.edge.routes.earningsRoutes
import com.haky.edge.routes.premortemRoutes
import com.haky.edge.routes.rebalanceRoutes
import com.haky.edge.routes.discoveryRoutes
import com.haky.edge.routes.investorRoutes
import com.haky.edge.routes.macroImpactRoutes
import com.haky.edge.routes.marketCalendarRoutes
import com.haky.edge.routes.sectorClassifyRoutes
import com.haky.edge.routes.macroRoutes
import com.haky.edge.routes.marketMoodLogRoutes
import com.haky.edge.routes.marketMoodRoutes
import com.haky.edge.routes.newsRoutes
import com.haky.edge.routes.quoteRoutes
import com.haky.edge.routes.searchRoutes
import com.haky.edge.routes.sectorBriefingRoutes
import com.haky.edge.routes.sectorRoutes
import com.haky.edge.routes.sectorRotationRoutes
import com.haky.edge.routes.shortSellingRoutes
import com.haky.edge.routes.peerValuationRoutes
import com.haky.edge.routes.targetPriceRoutes
import com.haky.edge.routes.valuationBandRoutes
import com.haky.edge.routes.warningsRoutes
import com.haky.edge.routes.webSearchTestRoutes
import com.haky.edge.routes.morningBriefRoutes
import com.haky.edge.routes.eventReminderRoutes
import com.haky.edge.routes.costSummaryRoutes
import com.haky.edge.routes.signalRoutes
import com.haky.edge.routes.overseasRoutes
import com.haky.edge.routes.prewarmRoutes
import com.haky.edge.routes.slackCommandRoutes
import com.haky.edge.routes.slackTestRoutes
import com.haky.edge.slack.CostSummaryService
import com.haky.edge.slack.EventReminderService
import com.haky.edge.slack.MorningBriefService
import com.haky.edge.slack.OpsAlerter
import com.haky.edge.slack.SlackClient
import com.haky.edge.slack.SlackCommandService
import com.haky.edge.slack.SlackSignatureVerifier
import com.haky.edge.tasks.CloudTasksClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.launch
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
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
    // 배포 보안 게이트(1.0c-a): 공유 토큰 인증 + IP별 레이트리밋. 로컬은 토큰 비면 인증 생략.
    configureSecurity()

    // Slack 운영 알림(S1): 백엔드 오류를 #ops-오류 채널로 흘린다. 토큰/채널 비면 no-op(로컬 무해).
    // StatusPages 핸들러에서 쓰므로 install 전에 생성한다. fire-and-forget 발송에 Application 스코프를 넘긴다.
    val slack = SlackClient(botToken = System.getenv("SLACK_BOT_TOKEN").orEmpty())
    val opsChannel = System.getenv("SLACK_OPS_CHANNEL").orEmpty()
    val briefingChannel = System.getenv("SLACK_BRIEFING_CHANNEL").orEmpty()
    val signalChannel = System.getenv("SLACK_SIGNAL_CHANNEL").orEmpty()
    val aiCommentChannel = System.getenv("SLACK_AI_COMMENT_CHANNEL").orEmpty()
    val eventChannel = System.getenv("SLACK_EVENT_CHANNEL").orEmpty()
    val deployChannel = System.getenv("SLACK_DEPLOY_CHANNEL").orEmpty()
    val costChannel = System.getenv("SLACK_COST_CHANNEL").orEmpty()
    // 신호 평가 대상 종목 — prewarm과 같은 공통 관심종목(SIGNAL_CODES env, 없으면 CLAUDE.md 11종목 폴백).
    // 사용자별 워치리스트 서버 등록은 후속(S3 메모리) — 그 전까진 공통 목록으로 동작.
    val signalCodes = (System.getenv("SIGNAL_CODES")
        ?: "018260,329180,066570,307950,000660,005930,267260,001440,062040,047810,012450")
        .split(",").map { it.trim() }.filter { it.isNotBlank() }
    val opsAlerter = OpsAlerter(
        slack = slack,
        opsChannel = opsChannel,
        scope = this,
    )

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
            // 조용히 실패하던 오류(KIS 세션 공백 등)를 Slack으로 가시화. 쿨다운으로 도배 방지, 응답 지연 없음.
            opsAlerter.alert(
                method = call.request.httpMethod.value,
                path = call.request.path(),
                status = status.value,
                errorClass = cause::class.simpleName ?: "Throwable",
                message = cause.message ?: cause.toString(),
            )
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
    // 토스 보강 소스(한투에 없는 투자유의·개장캘린더·상하한가). 슬라이스0=인증/연결 확인.
    // 키 미설정이면 warmup/호출이 조용히 건너뛰므로 로컬에선 .env 없이도 서버가 뜬다.
    val toss = TossClient(
        clientId = System.getenv("TOSS_CLIENT_ID").orEmpty(),
        clientSecret = System.getenv("TOSS_CLIENT_SECRET").orEmpty(),
        baseUrl = System.getenv("TOSS_BASE_URL") ?: "https://openapi.tossinvest.com",
    )
    val master = StockMaster(HttpClient(CIO))
    val overseasMaster = OverseasMaster(HttpClient(CIO))
    val naver = NaverNewsClient(
        clientId = System.getenv("NAVER_CLIENT_ID").orEmpty(),
        clientSecret = System.getenv("NAVER_CLIENT_SECRET").orEmpty(),
    )
    // Claude 분석. 모델은 CLAUDE_MODEL 로 덮어쓸 수 있고 기본은 Sonnet 4.6(비용/속도).
    val usageTracker = ClaudeUsageTracker(System.getenv("DATA_DIR") ?: ".data")
    val claude = ClaudeClient(
        apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty(),
        model = System.getenv("CLAUDE_MODEL") ?: "claude-sonnet-4-6",
        usageTracker = usageTracker,
    )
    // 트리거별 모델 라우팅: 기본은 브리핑·종목최초·급변재생성=Opus, 수동새로고침=Sonnet.
    // 롤백/재튜닝은 env OPUS_TRIGGERS(콤마 목록)로 코드 수정 없이. (ModelRouter 주석 참고)
    val modelRouter = ModelRouter(
        sonnetModel = System.getenv("CLAUDE_MODEL") ?: "claude-sonnet-4-6",
        opusModel = System.getenv("CLAUDE_OPUS_MODEL") ?: "claude-opus-4-8",
        opusTriggers = ModelRouter.parseTriggers(System.getenv("OPUS_TRIGGERS")),
    )
    val dart = DartClient(apiKey = System.getenv("DART_API_KEY").orEmpty())
    val naverTargetPrice = NaverTargetPriceClient()
    val targetPriceLog = TargetPriceLogService()
    val fearGreed = FearGreedClient()
    val copper = CopperClient()
    val ecos = EcosClient(apiKey = System.getenv("ECOS_API_KEY").orEmpty())
    val yahoo = YahooMacroClient()
    val eventSync = EventSyncService(claude)
    val macroImpact = MacroImpactService(kis, master, claude, fearGreed, copper, ecos, naver, yahoo, eventSync)
    val krxShortSelling = KrxShortSellingClient()
    val valuationBand = ValuationBandService(kis, dart)
    val peerValuation = PeerValuationService(kis, master, macroImpact)
    val backtest = BacktestService(kis)
    // F6: 스탠스 로그는 기록(AnalysisService)과 채점(StanceStatsService)이 같은 파일을 봐야 해서 공유.
    val stanceLog = com.haky.edge.ai.StanceLog()
    val analysis = AnalysisService(kis, toss, naver, master, claude, dart, naverTargetPrice, targetPriceLog, macroImpact, krxShortSelling, valuationBand, peerValuation, backtest, eventSync, modelRouter, slack, aiCommentChannel, this,
        // Q&A 일일 상한 — 자유 질문은 캐시가 없어 호출당 풀 LLM 비용. env로 재조정 가능.
        askDailyLimit = System.getenv("ASK_DAILY_LIMIT")?.toIntOrNull() ?: 200,
        stanceLog = stanceLog)
    val comparison = ComparisonService(kis, naver, master, claude, dart, naverTargetPrice, valuationBand)
    // 포트폴리오 종합 진단(B) — 집중도·매크로 노출·밸류 분포는 계산, Claude는 구조 해석만.
    val portfolioReview = com.haky.edge.ai.PortfolioReviewService(kis, master, macroImpact, valuationBand, claude, modelRouter)
    // 리밸런싱 트리거(R1) — /portfolio-review가 남긴 포지션 스냅샷을 룰로 평가(비중 드리프트·상단권 쏠림).
    val rebalance = com.haky.edge.ai.RebalanceService(kis, master, valuationBand)
    // 지켜볼 후보 발굴(D1) — peer 바스켓 유니버스(관심종목 제외) 신호 스캔.
    val discovery = com.haky.edge.ai.DiscoveryService(kis, master, signalCodes)
    val moodLog = MarketMoodLogService()
    // C 섹터 자금 순환 — 업종지수 5/20일 상대강도. 시장 분위기 facts에 순환 문단 주입(신호 있을 때만).
    val sectorRotation = com.haky.edge.macro.SectorRotationService(kis)
    val marketMood = MarketMoodService(kis, claude, fearGreed, copper, ecos, yahoo, modelRouter, moodLog, eventSync, sectorRotation)
    val sectorBriefing = SectorBriefingService(kis, master, claude, macroImpact)
    val catalystEventLog = com.haky.edge.ai.CatalystEventLog()
    val catalyst = CatalystService(kis, naver, master, claude, dart, valuationBand, macroImpact, modelRouter, catalystEventLog)
    // F1 유사 국면 통계 — 장기 일봉 이력(페이지네이션+파일 캐시) 위 기저율 계산. LLM 0.
    val dailyHistory = com.haky.edge.ai.DailyHistoryService(kis)
    // F2 수주 공시 임팩트 통계 — 백필(2-1) + forward return 통계(2-2). LLM 0.
    val catalystImpact = com.haky.edge.ai.CatalystImpactService(dart, dailyHistory, master, catalystEventLog)
    val analog = com.haky.edge.ai.AnalogService(dailyHistory, master)
    // F6 채점 — 스탠스 로그 × 일봉 이력(F1 캐시 재사용) 20거래일 후 수익률 대조.
    val stanceStats = com.haky.edge.ai.StanceStatsService(stanceLog, dailyHistory)
    // F3 실적 프리뷰 — run-rate YoY + 과거 발표 반응 통계(DART 접수일 × F1 일봉 캐시).
    val earningsPreview = com.haky.edge.ai.EarningsPreviewService(dart, dailyHistory, master)
    // F5 프리모템 — 매수 사유 → 무효화 조건 구조화(Claude 1회/기록). 감시는 signals-scan이 담당.
    val premortem = com.haky.edge.ai.PremortemService(analysis, master, claude, modelRouter)
    val morningBrief = MorningBriefService(slack, briefingChannel, marketMood, moodLog, eventSync)
    val eventReminder = EventReminderService(slack, eventChannel, eventSync)
    val costSummary = CostSummaryService(slack, costChannel, usageTracker)
    // S3a/b+F4+F3+F5+R2 신호 알림: 연속 순매수·신규 공시·밸류밴드 저평가·수급 전환점·실적 리뷰·프리모템 발동·비중 점검 → #알림-신호 채널.
    val signalService = com.haky.edge.slack.SignalService(slack, kis, master, dart, valuationBand, signalChannel, signalCodes, backtest, earningsPreview, premortem, rebalance)
    // S7·S8 슬래시 명령 + 라운지 명령어. 서명검증 + 멀티 커맨드 라우팅.
    val slackVerifier = SlackSignatureVerifier(System.getenv("SLACK_SIGNING_SECRET").orEmpty())
    val slackCommand = SlackCommandService(analysis, master, slack, kis, marketMood, eventSync, comparison)
    // Slack 분석은 Cloud Tasks 워커(POST /slack/analyze-task)로 돌려 Cloud Run CPU 스로틀링을 피한다.
    // 큐 미설정(로컬)이면 enabled=false → 라우트가 인프로세스 폴백으로 동작.
    val cloudTasks = CloudTasksClient(
        projectId = System.getenv("GCP_PROJECT_ID").orEmpty(),
        location = System.getenv("TASKS_LOCATION") ?: "asia-northeast3",
        queue = System.getenv("TASKS_QUEUE").orEmpty(),
    )

    // 서버 시작 직후 백그라운드로 KIS 토큰 + DART corpCode 맵을 미리 로드한다.
    // 첫 번째 실제 요청이 올 때 이 두 초기화 작업(각 수 초)을 기다리지 않아도 되게 함.
    launch {
        runCatching { kis.warmup() }   // KIS 접근토큰 선발급
        runCatching { toss.warmup() }  // 토스 접근토큰 선발급(키 미설정이면 내부에서 건너뜀)
        runCatching { dart.warmup() }  // DART corpCode.xml 다운로드·파싱
    }

    routing {
        // 헬스체크는 인증·레이트리밋 밖에 둔다(Cloud Run 프로브가 토큰 없이·무제한으로 칠 수 있게).
        get("/health") { call.respondText("OK") }
        // 나머지 데이터 라우트는 전부 IP별 레이트리밋 적용(토큰 인증은 configureSecurity 인터셉트에서 전역 처리).
        rateLimit(ApiRateLimit) {
            quoteRoutes(kis)
            overseasRoutes(kis, overseasMaster)
            warningsRoutes(toss)
            marketCalendarRoutes(toss)
            chartRoutes(kis)
            investorRoutes(kis)
            macroRoutes(kis, fearGreed, copper, ecos, yahoo)
            macroImpactRoutes(macroImpact)
            sectorClassifyRoutes(macroImpact)
            marketMoodRoutes(marketMood)
            marketMoodLogRoutes(moodLog, marketMood)
            newsRoutes(naver)
            searchRoutes(master)
            analysisRoutes(analysis)
            askRoutes(analysis)
            catalystRoutes(catalyst)
            catalystImpactRoutes(catalystImpact)
            analogRoutes(analog)
            stanceStatsRoutes(stanceStats)
            dartRoutes(dart)
            earningsRoutes(dart)
            earningsPreviewRoutes(earningsPreview)
            premortemRoutes(premortem)
            sectorRoutes(kis)
            sectorRotationRoutes(sectorRotation)
            sectorBriefingRoutes(sectorBriefing)
            shortSellingRoutes(krxShortSelling)
            targetPriceRoutes(naverTargetPrice)
            valuationBandRoutes(valuationBand)
            peerValuationRoutes(peerValuation)
            backtestRoutes(backtest)
            comparisonRoutes(comparison)
            portfolioReviewRoutes(portfolioReview, rebalance)
            rebalanceRoutes(rebalance)
            discoveryRoutes(discovery)
            eventRoutes(eventSync)
            webSearchTestRoutes(claude)
            prewarmRoutes(kis, dart)
            slackTestRoutes(slack, opsChannel)
            morningBriefRoutes(morningBrief)
            eventReminderRoutes(eventReminder)
            costSummaryRoutes(costSummary)
            signalRoutes(signalService)
            slackCommandRoutes(slackVerifier, slackCommand, cloudTasks)
        }
    }
}
