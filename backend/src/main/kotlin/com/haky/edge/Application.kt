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
import com.haky.edge.routes.analogValidationRoutes
import com.haky.edge.routes.moodWeightValidationRoutes
import com.haky.edge.routes.analysisRoutes
import com.haky.edge.routes.stanceStatsRoutes
import com.haky.edge.routes.askRoutes
import com.haky.edge.routes.portfolioReviewRoutes
import com.haky.edge.routes.backtestRoutes
import com.haky.edge.routes.dividendRoutes
import com.haky.edge.routes.catalystImpactRoutes
import com.haky.edge.routes.catalystRoutes
import com.haky.edge.routes.comparisonRoutes
import com.haky.edge.routes.eventRoutes
import com.haky.edge.routes.chartRoutes
import com.haky.edge.routes.dartRoutes
import com.haky.edge.routes.earningsPreviewRoutes
import com.haky.edge.routes.earningsRoutes
import com.haky.edge.routes.premortemRoutes
import com.haky.edge.routes.tradeReviewRoutes
import com.haky.edge.routes.deepResearchRoutes
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
import com.haky.edge.routes.sensitivityValidationRoutes
import com.haky.edge.routes.factsAuditRoutes
import com.haky.edge.routes.catalystValidationRoutes
import com.haky.edge.routes.morningBriefRoutes
import com.haky.edge.routes.personalWeeklyReviewRoutes
import com.haky.edge.routes.judgmentComparisonRoutes
import com.haky.edge.routes.portfolioRiskRoutes
import com.haky.edge.routes.portfolioStressRoutes
import com.haky.edge.routes.positionSizingRoutes
import com.haky.edge.routes.signalLabRoutes
import com.haky.edge.routes.weeklyReviewRoutes
import com.haky.edge.routes.eventReminderRoutes
import com.haky.edge.routes.costSummaryRoutes
import com.haky.edge.routes.signalFiredRoutes
import com.haky.edge.routes.usageEventRoutes
import com.haky.edge.routes.commentSmokeRoutes
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
    com.haky.edge.ai.FileCache.opsAlerter = opsAlerter

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
    // 키 미설정이면 warmup/호출이 조용히 건너뜀. 라우트 등록은 키 존재 시에만(아래 rateLimit 블록).
    val tossClientId = System.getenv("TOSS_CLIENT_ID").orEmpty()
    val toss = TossClient(
        clientId = tossClientId,
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
    // 트리거별 모델 라우팅: 기본은 해석 코멘트 전부 Opus, 재료 JSON 분류(catalyst)만 Sonnet.
    // 롤백/재튜닝은 env OPUS_TRIGGERS(콤마 목록)로 코드 수정 없이. (ModelRouter 주석 참고)
    val modelRouter = ModelRouter(
        sonnetModel = System.getenv("CLAUDE_MODEL") ?: "claude-sonnet-4-6",
        opusModel = System.getenv("CLAUDE_OPUS_MODEL") ?: "claude-opus-4-8",
        opusTriggers = ModelRouter.parseTriggers(System.getenv("OPUS_TRIGGERS")),
    )
    val dart = DartClient(apiKey = System.getenv("DART_API_KEY").orEmpty())
    val naverTargetPrice = NaverTargetPriceClient(
        // 구조 변경 감지(값→null 전환 3종목째) → #ops 채널 1회 경고(O3)
        onStructureAlert = { msg -> opsAlerter.alertCustom("naver-target-structure", msg) },
    )
    val targetPriceLog = TargetPriceLogService()
    val fearGreed = FearGreedClient()
    val copper = CopperClient()
    val ecos = EcosClient(apiKey = System.getenv("ECOS_API_KEY").orEmpty())
    val yahoo = YahooMacroClient()
    val eventSync = EventSyncService(claude)
    val macroImpact = MacroImpactService(kis, master, claude, fearGreed, copper, ecos, naver, yahoo, eventSync, modelRouter)
    val krxShortSelling = KrxShortSellingClient()
    val valuationBand = ValuationBandService(kis, dart)
    val peerValuation = PeerValuationService(kis, master, macroImpact)
    val backtest = BacktestService(kis)
    // F6: 스탠스 로그는 기록(AnalysisService)과 채점(StanceStatsService)이 같은 파일을 봐야 해서 공유.
    val stanceLog = com.haky.edge.ai.StanceLog()
    // N3-a 신호 발화 로그 — AnalysisService(deltaLines 재료)와 SignalService가 공유.
    val signalFiredLog = com.haky.edge.slack.SignalFiredLog()
    // M1 카드 사용량 로그(단일 사용자 전제) — 30일 후 펼침 0 카드로 K2~K4 제거·강등 결정.
    val usageEventLog = com.haky.edge.usage.UsageEventLog()
    val analysis = AnalysisService(kis, toss, naver, master, claude, dart, naverTargetPrice, targetPriceLog, macroImpact, krxShortSelling, valuationBand, peerValuation, backtest, eventSync, modelRouter, slack, aiCommentChannel, this,
        // Q&A 일일 상한 — 자유 질문은 캐시가 없어 호출당 풀 LLM 비용. env로 재조정 가능.
        askDailyLimit = System.getenv("ASK_DAILY_LIMIT")?.toIntOrNull() ?: 200,
        stanceLog = stanceLog,
        signalFiredLog = signalFiredLog)
    // O4 해외 간단 코멘트 — 시세(15분 지연)+뉴스만 근거. (code,날짜) 당일 공유 캐시, 기본 Opus.
    val overseasAnalysis = com.haky.edge.ai.OverseasAnalysisService(kis, naver, overseasMaster, claude, modelRouter)
    val comparison = ComparisonService(kis, naver, master, claude, dart, naverTargetPrice, valuationBand, modelRouter)
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
    val sectorBriefing = SectorBriefingService(kis, master, claude, macroImpact, modelRouter)
    val catalystEventLog = com.haky.edge.ai.CatalystEventLog()
    val catalyst = CatalystService(kis, naver, master, claude, dart, valuationBand, macroImpact, modelRouter, catalystEventLog)
    // F1 유사 국면 통계 — 장기 일봉 이력(페이지네이션+파일 캐시) 위 기저율 계산. LLM 0.
    val dailyHistory = com.haky.edge.ai.DailyHistoryService(kis)
    // F2 수주 공시 임팩트 통계 — 백필(2-1) + forward return 통계(2-2). LLM 0.
    val catalystImpact = com.haky.edge.ai.CatalystImpactService(dart, dailyHistory, master, catalystEventLog)
    val analog = com.haky.edge.ai.AnalogService(dailyHistory, master)
    // F6 채점(X4 개정) — 스탠스 로그 × 일봉 이력(F1 캐시) × 코스피, 20거래일 초과수익 대조(판단대조와 동일 잣대).
    val stanceStats = com.haky.edge.ai.StanceStatsService(stanceLog, dailyHistory, kis)
    // F3 실적 프리뷰 — run-rate YoY + 과거 발표 반응 통계(DART 접수일 × F1 일봉 캐시).
    val earningsPreview = com.haky.edge.ai.EarningsPreviewService(dart, dailyHistory, master)
    // F5 프리모템 — 매수 사유 → 무효화 조건 구조화(Claude 1회/기록). 감시는 signals-scan이 담당.
    val premortem = com.haky.edge.ai.PremortemService(analysis, master, claude, modelRouter)
    // 매매 복기 — 프리모템의 대칭. 완결 매매의 가격 경로(계산) + 과정/결과 분리 해석(Claude).
    val tradeReview = com.haky.edge.ai.TradeReviewService(dailyHistory, master, claude, modelRouter)
    // C 딥리서치 — 웹검색(과금) 결합 2단계 리포트. 일일 상한 + (code,날짜) 캐시 + force 불허.
    val deepResearch = com.haky.edge.ai.DeepResearchService(analysis, master, claude, modelRouter,
        dailyLimit = System.getenv("DEEP_RESEARCH_DAILY_LIMIT")?.toIntOrNull() ?: 5)
    // N2 실적 가이던스 — 실적 리뷰 발화 시 "회사가 말한 것" 웹 수집→구조화. (code,rceptNo) 캐시+일일 한도.
    val guidanceService = com.haky.edge.ai.GuidanceService(master, claude, modelRouter,
        dailyLimit = System.getenv("GUIDANCE_DAILY_LIMIT")?.toIntOrNull() ?: 5)
    // R4 코멘트 금지 패턴 스모크 — 당일 캐시 코멘트 정규식 검사(LLM 0). 발견 시에만 운영오류 채널 발송.
    val commentSmoke = com.haky.edge.ai.CommentSmokeService(slack, opsChannel)
    // D1 SENSITIVITY 실증 — 1회성 검증 라우트(운영 기능 아님). 지표 이력 × 바스켓 수익률 실측.
    val sensitivityValidation = com.haky.edge.macro.SensitivityValidationService(
        dailyHistory, ecosApiKey = System.getenv("ECOS_API_KEY").orEmpty())
    // ②-1 catalyst 판정 실증 — LLM 판정(호재/악재·강도·선반영) × 사후 초과수익률(vs 코스피) 채점.
    // K5(이관 판정): 이벤트(공시일) 기반 신호라 signal-lab의 종목 가격 SignalDef로 표현 불가 → 유지(10월 재실측 도구).
    val catalystValidation = com.haky.edge.ai.CatalystValidationService(catalystEventLog, dailyHistory, kis, master)
    // ②-2a Analog 캘리브레이션 실증 — 유사 국면 카드 forward 분포를 walk-forward replay로 채점.
    // K5(이관 판정): 유사 국면 매칭(분포 예측)은 signal-lab 단순 신호 리플레이와 형태가 달라 이관 불가 → 유지(9월 재실측 도구).
    val analogValidation = com.haky.edge.ai.AnalogValidationService(dailyHistory, master, signalCodes)
    val yahooHistory = com.haky.edge.macro.YahooHistoryClient()
    // ③ MoodLog 가중치 실측 — Yahoo 2년 8지표 × 코스피 3분류, 홀드아웃 검증.
    // K5(이관 판정): 거시지표→코스피 방향 예측이라 종목 단위 signal-lab과 형태가 달라 이관 불가 → 유지(선물 재론 도구).
    val moodWeightValidation = com.haky.edge.macro.MoodWeightValidationService(yahooHistory)
    // K5: anchor·discovery 검증은 signal-lab 수트(suite=anchor|discovery)로 이관 완료 — 라우트 등록 해제.
    //   discovery는 실측 재현 완전 일치(2026-07-21 대조), anchor는 5/20 초과수익 기준(원 ②-3은 5/10 원수익 — 방법론 상이).
    //   서비스 파일(AnchorValidationService·DiscoveryValidationService)은 원 리포트 재현·단위테스트용으로 동결 보존.
    val morningBrief = MorningBriefService(slack, briefingChannel, marketMood, moodLog, eventSync)
    // B 주간 회고 — 토요일 아침, 한 주 서버 기록(방향예측·스탠스·목표가·주간 등락) 회고 → #아침브리핑.
    val weeklyReview = com.haky.edge.slack.WeeklyReviewService(
        slack, briefingChannel, kis, master, signalCodes, stanceLog, stanceStats, moodLog, targetPriceLog, eventSync, claude, modelRouter)
    // 판단 대조 — 내 매매 vs AI 스탠스 반사실 성적(20거래일 초과수익 동일 잣대, LLM 0).
    val judgmentComparison = com.haky.edge.ai.JudgmentComparisonService(kis, stanceLog, dailyHistory)
    // B2 개인 주간 회고 — 앱이 포지션·매매·논지를 POST, 서버가 주간 등락·스탠스·목표가·이벤트를 합쳐 Opus 해석.
    // L1: 판단대조·규율·프리모템 발동까지 facts에 주입해 행동 처방 1개를 회고에 포함.
    val personalWeeklyReview = com.haky.edge.ai.PersonalWeeklyReviewService(
        kis, master, stanceLog, stanceStats, targetPriceLog, eventSync, claude, modelRouter,
        judgmentComparison, signalFiredLog)
    // 포트폴리오 리스크 엔진 — 실측 상관·변동성·리스크 기여도·클러스터(LLM 0).
    val portfolioRisk = com.haky.edge.ai.PortfolioRiskService(kis, master, dailyHistory)
    // N4 시나리오 스트레스(축소판) — 코스피 충격 × 실측 베타만(무근거 매크로 샥 제외). risk 재사용.
    val portfolioStress = com.haky.edge.ai.PortfolioStressService(portfolioRisk)
    // 포지션 사이징 보조 — 리스크 기여 상한 역산(LLM 0, PortfolioRisk 수식 재사용).
    val positionSizing = com.haky.edge.ai.PositionSizingService(master, dailyHistory)
    // 전략 실험실 — 선언적 신호 수트 → 유니버스 리플레이 + 대조군 + 초과수익 채점(LLM 0).
    // R3 대조 유니버스 — 시총 상위 근사 무작위 표본(anchor 결론의 관심종목 편향 검증용).
    val controlUniverse = com.haky.edge.lab.ControlUniverseService(kis, master, signalCodes)
    val signalLab = com.haky.edge.lab.SignalLabService(dailyHistory, yahooHistory, signalCodes, controlUniverse, kis)
    val eventReminder = EventReminderService(slack, eventChannel, eventSync)
    val costSummary = CostSummaryService(slack, costChannel, usageTracker)
    // S3a/b+F4+F3+F5+R2 신호 알림: 연속 순매수·신규 공시·밸류밴드 저평가·수급 전환점·실적 리뷰·프리모템 발동·비중 점검 → #알림-신호 채널.
    // 수급 아카이브 — signals-scan이 매일 확정 일별 수급을 jsonl로 영속(F4 사후 검증의 데이터 기반).
    val investorHistory = com.haky.edge.kis.InvestorHistoryLog()
    val signalService = com.haky.edge.slack.SignalService(slack, kis, master, dart, valuationBand, signalChannel, signalCodes, backtest, earningsPreview, premortem, rebalance, investorHistory, signalFiredLog, guidanceService)
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
        if (tossClientId.isNotBlank()) runCatching { toss.warmup() }  // 토스 접근토큰 선발급
        runCatching { dart.warmup() }  // DART corpCode.xml 다운로드·파싱
    }

    routing {
        // 헬스체크는 인증·레이트리밋 밖에 둔다(Cloud Run 프로브가 토큰 없이·무제한으로 칠 수 있게).
        get("/health") { call.respondText("OK") }
        // 나머지 데이터 라우트는 전부 IP별 레이트리밋 적용(토큰 인증은 configureSecurity 인터셉트에서 전역 처리).
        rateLimit(ApiRateLimit) {
            quoteRoutes(kis)
            overseasRoutes(kis, overseasMaster, overseasAnalysis)
            if (tossClientId.isNotBlank()) {
                warningsRoutes(toss)
                marketCalendarRoutes(toss)
            }
            chartRoutes(kis)
            investorRoutes(kis, investorHistory)
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
            earningsPreviewRoutes(earningsPreview, guidanceService)
            premortemRoutes(premortem)
            tradeReviewRoutes(tradeReview)
            deepResearchRoutes(deepResearch)
            sectorRoutes(kis)
            sectorRotationRoutes(sectorRotation)
            sectorBriefingRoutes(sectorBriefing)
            shortSellingRoutes(krxShortSelling)
            targetPriceRoutes(naverTargetPrice)
            valuationBandRoutes(valuationBand)
            peerValuationRoutes(peerValuation)
            backtestRoutes(backtest)
            dividendRoutes(dart, kis)
            comparisonRoutes(comparison)
            portfolioReviewRoutes(portfolioReview, rebalance)
            rebalanceRoutes(rebalance)
            discoveryRoutes(discovery)
            eventRoutes(eventSync)
            sensitivityValidationRoutes(sensitivityValidation)
            catalystValidationRoutes(catalystValidation)
            analogValidationRoutes(analogValidation)
            moodWeightValidationRoutes(moodWeightValidation)
            // K5: anchor·discovery는 signal-lab 수트(GET /signal-lab?suite=anchor|discovery)로 이관 — 라우트 해제.
            factsAuditRoutes(analysis, signalCodes)
            prewarmRoutes(kis, dart)
            slackTestRoutes(slack, opsChannel)
            morningBriefRoutes(morningBrief)
            weeklyReviewRoutes(weeklyReview)
            personalWeeklyReviewRoutes(personalWeeklyReview)
            judgmentComparisonRoutes(judgmentComparison)
            portfolioRiskRoutes(portfolioRisk)
            portfolioStressRoutes(portfolioStress)
            positionSizingRoutes(positionSizing)
            signalLabRoutes(signalLab)
            eventReminderRoutes(eventReminder)
            costSummaryRoutes(costSummary)
            signalRoutes(signalService)
            signalFiredRoutes(signalFiredLog)
            usageEventRoutes(usageEventLog)
            commentSmokeRoutes(commentSmoke)
            slackCommandRoutes(slackVerifier, slackCommand, cloudTasks)
        }
    }
}
