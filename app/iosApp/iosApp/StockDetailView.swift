import SwiftUI
import SharedLogic
import Charts

// 관심종목 리스트에서 종목을 탭하면 들어오는 상세 화면.
// 리스트가 이미 받아둔 Quote(스냅샷)로 즉시 표시하고, 진입 시 한 번 최신가로 갱신한다.
// 1.5: 내 포지션(평단가·수량·목표·손절)을 표시하고 현재가로 수익률을 계산한다(편집은 시트).
struct StockDetailView: View {
    @State private var item: WatchItem          // 포지션 편집 결과를 반영하려 가변
    private let api: EdgeApi
    private let logRepo: ActionLogRepository
    @State private var quote: Quote?
    @State private var warnings: [StockWarning] = []  // 투자유의(시장경보·단기과열·정리매매·VI, 토스)
    @State private var priceLimits: PriceLimits?       // 상·하한가(토스)
    @State private var flows: [InvestorFlow] = []   // 일별 수급(외인/기관/개인)
    @State private var analysis: Analysis?           // AI 종합 코멘트
    @State private var catalysts: CatalystReport?    // 뉴스·공시 영향(호재/악재 판정)
    @State private var catalystImpact: CatalystImpact?  // F2 수주 공시 임팩트 통계
    @State private var catalystsLoading = false
    @State private var catalystAttempted = false     // 1회 로드 시도 후 nil이면 실패로 간주(폴백 안내)
    @State private var catalystExpanded = false      // 뉴스·공시 영향 (기본 접힘, netBias 배지는 접어도 보임)
    @State private var technicalResult: TechnicalResult?  // 이평·RSI·거래량 추세(2단계)
    @State private var targetPriceInfo: TargetPriceInfo?   // 컨센서스 목표주가
    @State private var dailyBars: [DailyBar] = []           // 일봉 (차트용)
    @State private var logEntries: [ActionLogEntry] = []  // 이 종목 행동 로그
    @State private var swipedLogId: Int64? = nil
    @State private var earningsEntry: EarningsEntry?
    @State private var shortSelling: ShortSellingSummary?
    @State private var shortSellingHelpExpanded = false
    @State private var valuationBand: ValuationBand?
    @State private var peerValuation: PeerValuation?  // 동종 상대 밸류 → U2에서 밸류에이션 카드로 통합
    @State private var backtest: Backtest?          // 신호별 익일 적중률(검증된 신호)
    @State private var flowSensitivity: FlowSensitivity?  // 수급-가격 민감도
    @State private var dividendCard: DividendCard?   // DART 배당 카드(E2)
    @State private var dividendExpanded = false
    @State private var analog: AnalogReport?        // 유사 국면 통계(F1)
    @State private var premortem: Premortem?        // 매수 프리모템(F5)
    @State private var premortemExpanded = false
    @State private var tradeReview: TradeReview?    // 매매 복기(B2)
    @State private var tradeReviewExpanded = false
    @State private var deepResearch: DeepResearch?  // 딥리서치(C2)
    @State private var deepResearchLoading = false
    @State private var deepResearchError = false     // 실패·일일 한도 안내(무피드백 방지)
    @State private var deepResearchExpanded = false
    @State private var earningsExpanded = false
    @State private var indicatorHelpExpanded = false
    @State private var valuationHelpExpanded = false
    @State private var analysisExpanded = false      // 지표 해석 (기본 접힘)
    @State private var valuationExpanded = false     // 밸류에이션 (역사밴드+동종, 기본 접힘)
    @State private var backtestExpanded = false      // 검증된 신호 (기본 접힘)
    @State private var analogExpanded = false        // 유사 국면 통계 (기본 접힘)
    @State private var technicalExpanded = false     // 기술적 지표 (기본 접힘)
    @State private var flowExpanded = false          // 수급 (기본 접힘)
    @State private var shortSellingExpanded = false  // 공매도 동향 (기본 접힘)
    @State private var chartPeriod: ChartPeriod = .m3   // 가격 차트 기간 토글
    @State private var trendLineHelpExpanded = false     // 20일 추세선 설명 토글
    @State private var commentExpanded = false   // AI 코멘트 더보기/접기
    @State private var analysisThesisChanged = false  // 논지 변경 후 코멘트 갱신 힌트(S13)
    @AppStorage(analysisModeKey) private var modeRaw = AnalysisMode.defensive.rawValue
    private var analysisMode: AnalysisMode { AnalysisMode(rawValue: modeRaw) ?? .defensive }
    @State private var analyzing = false
    @State private var loading = false
    @State private var showEdit = false
    @State private var showLogSheet = false
    @State private var showAskSheet = false
    private let initialAccountId: Int64?

    // 계좌 컨텍스트 — nil=전체(전 계좌 병합), 값=해당 계좌 포지션 기준. 진입 경로가 초기값을 정하고
    // (관심종목 탭=전체, 내 자산 계좌 탭=그 계좌), 2개 이상 계좌 보유 시 포지션 카드 배지로 전환.
    // 컨텍스트는 item의 포지션 필드를 갈아끼우므로 카드·차트 기준선·AI 코멘트가 함께 따라간다.
    @State private var accountContext: Int64?
    @State private var accountHoldings: [Holding_] = []   // 이 종목의 계좌별 holding 행
    @State private var accountNames: [Int64: String] = [:]

    init(item: WatchItem, quote: Quote?, api: EdgeApi, logRepo: ActionLogRepository = Db.actionLog, initialAccountId: Int64? = nil) {
        // 관심종목 탭 경로의 item은 watchlist 기반이라 포지션 필드가 비어 있다(G1 이후 holding이 정본)
        // → holding을 얹어서 내 포지션 카드·차트 기준선·게이지가 어느 경로로 들어와도 보이게.
        _item = State(initialValue: Db.holding.hydrate(item: item))
        self.api = api
        self.logRepo = logRepo
        self.initialAccountId = initialAccountId
        _accountContext = State(initialValue: initialAccountId)
        _quote = State(initialValue: quote) // 리스트가 받아둔 시세로 초기화(바로 보이게)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text(item.code).font(.caption).foregroundColor(.secondary)

                if let q = quote {
                    // ── 현재 상황 ──
                    zoneHeader("현재 상황")
                    priceHeader(q)
                    if !warnings.isEmpty { warningChips() }
                    priceLimitView(q)
                    priceChartCard(q)
                    if let lines = analysis?.deltaLines, !lines.isEmpty { deltaStrip(lines) }
                    positionCard(q)
                    // ── 종합 판단 ──
                    zoneHeader("종합 판단")
                    aiCommentCard()
                    // ── AI 근거 ──
                    zoneHeader("AI 근거")
                    if let tr = technicalResult { technicalCardCollapsible(tr, price: Double(q.price)) }
                    if !flows.isEmpty { flowCardCollapsible() }
                    // 뉴스·공시는 판정 카드 하나로 일원화(원문 뉴스/공시 섹션 제거). 링크는 카드 안에서 원문으로.
                    catalystCard()
                    // ── 심화 분석 (기본 접힘) ──
                    zoneHeader("심화 분석")
                    analysisCard(q)
                    // U2: 밸류에이션 히스토리 + 동종 상대 밸류를 한 카드로 통합.
                    if valuationBand != nil || peerValuation != nil { valuationCard(valuationBand, peerValuation) }
                    if let bt = backtest { backtestCard(bt) }
                    // U2: 수급-가격 민감도는 '수급' 카드 하단으로 흡수(독립 카드 제거).
                    if let ss = shortSelling { shortSellingCard(ss) }
                    if let div = dividendCard { dividendCardView(div) }
                    if let an = analog, an.n > 0 { analogCard(an) }
                } else if loading {
                    ProgressView().padding(.top, 40)
                }
                // ── 외부 환경 (기본 접힘) ──
                // U2: '지표 영향' 카드 제거 — 브리핑 '내 종목 영향'과 중복, 매크로발 변화는 델타 스트립이 커버.
                zoneHeader("외부 환경")
                earningsDueDateSection()
                // ── 내 기록 ──
                zoneHeader("내 기록")
                if let pm = premortem { premortemCard(pm) }
                if !logEntries.isEmpty { logCard() }
                if let tr = tradeReview { tradeReviewCard(tr) }
                deepResearchSection()
            }
            .padding()
        }
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // U4: 저빈도·고비용(비교·딥리서치·질문) → ⋯ 오버플로 메뉴. 고빈도(매매기록·평단·새로고침)는 아이콘 유지.
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        if !deepResearchLoading { Task { await loadDeepResearch() } }
                    } label: {
                        Label("딥리서치", systemImage: "doc.text.magnifyingglass")
                    }
                    .disabled(deepResearchLoading)
                    Button { showAskSheet = true } label: {
                        Label("질문하기", systemImage: "questionmark.bubble")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showLogSheet = true } label: { Image(systemName: "flag") }
                    .help("매매 기록")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showEdit = true } label: { Image(systemName: "pencil.and.list.clipboard") }
                    .help("평단·목표가 수정")
            }
            ToolbarItem(placement: .topBarTrailing) {
                if loading {
                    ProgressView()
                } else {
                    Button { Task { await load() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .help("시세·수급 새로고침")
                }
            }
        }
        .task { await load() }             // 진입 시 시세·수급·뉴스 갱신(빠름)
        .task { await loadAnalysis() }     // AI 코멘트는 느려서 별도로(동시 진행)
        .task { await loadCatalysts() }    // 재료 판정도 Claude 호출이라 별도(동시 진행)
        .task { await loadStoredTradeReview() }  // S14: 저장된 복기 파라미터로 재조회
        .onChange(of: modeRaw) {
            analysis = nil   // 이전 모드 코멘트 즉시 제거 → 로딩 상태 바로 표시
            Task { await loadAnalysis() }
        }
        .onAppear {
            loadAccountContext()
            loadLogs()
            Usage.shared.view("detail")
        }
        .sheet(isPresented: $showAskSheet) {
            StockAskSheetView(item: item, api: api, mode: analysisMode, horizon: contextHorizon)
                .onAppear { Usage.shared.view("ask") }
        }
        .sheet(isPresented: $showEdit) {
            PositionEditView(item: item, initialAccountId: accountContext ?? initialAccountId) { updated in
                let prevThesis = item.thesis ?? ""
                item = updated
                // 편집으로 계좌 구성이 바뀌었을 수 있음(행 추가/삭제) → 컨텍스트 재적용
                loadAccountContext()
                // S13: 논지가 바뀌었으면 코멘트 갱신 힌트 표시
                if (updated.thesis ?? "") != prevThesis { analysisThesisChanged = true }
            }
        }
        .sheet(isPresented: $showLogSheet, onDismiss: {
            loadLogs()
            Task { premortem = try? await api.getPremortem(code: item.code) }  // 방금 생성됐을 수 있음
        }) {
            ActionLogSheetView(
                code: item.code, name: item.name, logRepo: logRepo,
                currentPrice: quote?.price ?? 0, api: api, item: item,
                onSellWithReview: { review in
                    if let r = review { tradeReview = r }
                }
            )
        }
    }

    // 현재가 + 전일대비. 한국 관례: 상승=빨강, 하락=파랑.
    private func priceHeader(_ q: Quote) -> some View {
        let up = q.change >= 0
        return VStack(spacing: 6) {
            Text("\(q.price.formatted()) 원")
                .font(.system(size: 40, weight: .bold))
            Text("\(up ? "▲" : "▼") \(abs(q.change).formatted())  \(String(format: "%.2f", abs(q.changeRate)))%")
                .font(.headline)
                .foregroundColor(up ? .red : .blue)
        }
    }

    // 투자유의 칩 — 시장경보(투자주의/경고/위험)·단기과열·정리매매·VI. 토스 기반(한투 미제공).
    // 발동 항목이 있을 때만(상위 호출에서 가드) 가격 바로 아래 눈에 띄게 노출.
    private func warningChips() -> some View {
        // 위험도 높은 순으로 정렬해 가장 중요한 경보가 앞에 오게.
        let order: [String: Int] = ["danger": 0, "warn": 1, "info": 2]
        let sorted = warnings.sorted { (order[$0.severity] ?? 9) < (order[$1.severity] ?? 9) }
        return ChipFlowLayout(spacing: 6) {
            ForEach(Array(sorted.enumerated()), id: \.offset) { _, w in
                let c = chipColor(w.severity)
                Text(w.label)
                    .font(.caption.weight(.semibold))
                    .fixedSize()
                    .padding(.horizontal, 9).padding(.vertical, 4)
                    .background(c.opacity(0.15))
                    .foregroundColor(c)
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(c.opacity(0.35), lineWidth: 0.5))
            }
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }

    private func chipColor(_ severity: String) -> Color {
        switch severity {
        case "danger": return .red
        case "warn":   return .orange
        default:        return .gray
        }
    }

    // 가격 제한폭(상·하한가) — 현재가 대비 여력 %. 제한폭 도달 시 칩. 제한폭 없는 시장(미국 등)은 숨김.
    @ViewBuilder
    private func priceLimitView(_ q: Quote) -> some View {
        if let pl = priceLimits, let upper = pl.upper?.int64Value, let lower = pl.lower?.int64Value, q.price > 0 {
            let price = Double(q.price)
            let upPct = (Double(upper) - price) / price * 100
            let lowPct = (Double(lower) - price) / price * 100
            HStack(spacing: 6) {
                if q.price >= upper {
                    Text("상한가 도달").font(.caption.weight(.semibold)).foregroundColor(.red)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.red.opacity(0.15)).clipShape(Capsule())
                } else if q.price <= lower {
                    Text("하한가 도달").font(.caption.weight(.semibold)).foregroundColor(.blue)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.blue.opacity(0.15)).clipShape(Capsule())
                }
                Text("상한가 \(upper.formatted()) (\(fmtSignedPct(upPct)))  ·  하한가 \(lower.formatted()) (\(fmtSignedPct(lowPct)))")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .center)
        }
    }

    private func fmtSignedPct(_ p: Double) -> String { String(format: "%+.1f%%", p) }

    // 가격 차트 카드 = "내 기준선 차트". 종가 흐름 + 고저 밴드 + 20일 추세선 위에
    // 내 평단·목표·손절을 가로선으로 얹어 "내가 산 가격이 지금 어디"를 한눈에 본다.
    private func priceChartCard(_ q: Quote) -> some View {
        let avg    = item.avgPrice?.doubleValue
        let target = item.targetPrice?.doubleValue
        let stop   = item.stopPrice?.doubleValue
        let count  = chartPeriod.barCount

        return VStack(alignment: .leading, spacing: 10) {
            if !dailyBars.isEmpty || chartPeriod == .today {
                // 헤더: 제목
                HStack {
                    Text("가격 흐름").font(.subheadline.weight(.semibold))
                    Spacer()
                }
                .padding(.top, 4)
                // 기간 토글 — 옵션이 5개라 별도 줄
                Picker("", selection: $chartPeriod) {
                    ForEach(ChartPeriod.allCases, id: \.self) { p in
                        Text(p == .all ? allPeriodLabel : p.label).tag(p)
                    }
                }
                .pickerStyle(.segmented)

                if chartPeriod == .today {
                    todaySummaryView(q)
                } else if !dailyBars.isEmpty {
                    priceChartLegend(avg: avg, target: target, stop: stop)
                        .padding(.bottom, 4)

                    PriceLineChart(
                        bars: dailyBars, displayCount: count,
                        avg: avg, target: target, stop: stop
                    )
                    .frame(height: 190)

                    HStack {
                        Text("거래량").font(.system(size: 9)).foregroundColor(.secondary)
                        Text("빨강 = 평소 2배↑").font(.system(size: 9)).foregroundColor(.red.opacity(0.65))
                        Spacer()
                    }
                    VolumeBars(bars: dailyBars, displayCount: count)
                        .frame(height: 36)
                }

                Divider()
            }
            Grid(alignment: .leading, horizontalSpacing: 8, verticalSpacing: 6) {
                GridRow {
                    miniStat("거래량", q.volume.formatted())
                    miniStat("시가", q.open.formatted())
                }
                GridRow {
                    miniStat("고가", q.high.formatted())
                    miniStat("저가", q.low.formatted())
                }
                GridRow {
                    miniStat("52주 최고", q.high52w.formatted())
                    miniStat("52주 최저", q.low52w.formatted())
                }
            }
            .padding(.bottom, 4)
        }
        .cardStyle()
    }

    // 오늘 탭: Quote(시가/고가/저가/현재가/거래량) + 거래량×가격 방향 추론.
    // 분봉 차트 없이 당일 요약으로 "오늘 어떤 날인지" 전달.
    private func todaySummaryView(_ q: Quote) -> some View {
        let avg20Vol: Double = {
            let recent = Array(dailyBars.prefix(20))
            guard !recent.isEmpty else { return 0 }
            return recent.reduce(0.0) { $0 + Double($1.volume) } / Double(recent.count)
        }()
        let volRatio = avg20Vol > 0 ? Double(q.volume) / avg20Vol : 0
        let priceUp  = q.changeRate >= 0
        // 이 탭의 시세·거래량이 언제 것인지 — 최근 일봉 날짜(주말이면 금요일). 없으면 표시 안 함.
        let asOf: String? = {
            guard let d = dailyBars.first?.date else { return nil }
            let t = tradingDayLabel(d)
            return t.isEmpty ? nil : t
        }()

        return VStack(alignment: .leading, spacing: 12) {
            // 데이터 기준일 — 주말/장전엔 직전 거래일(예: 금요일)임을 명확히.
            if let asOf {
                HStack(spacing: 4) {
                    Image(systemName: "calendar").font(.caption2)
                    Text("\(asOf) 기준").font(.caption2)
                }
                .foregroundColor(.secondary)
            }
            // 시가·현재가
            HStack(alignment: .top) {
                ohlcStat("시가", q.open.formatted())
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(q.price.formatted()) 원").font(.callout.weight(.bold))
                    Text("\(q.changeRate >= 0 ? "+" : "")\(String(format: "%.2f%%", q.changeRate))")
                        .font(.caption2)
                        .foregroundColor(priceUp ? .red : .blue)
                }
            }
            // 고가·저가
            HStack {
                ohlcStat("고가", q.high.formatted(), valueColor: .red)
                Spacer()
                ohlcStat("저가", q.low.formatted(), valueColor: .blue)
            }
            // 장중 위치 게이지
            if q.high > q.low {
                let range = Double(q.high - q.low)
                let pos   = Double(q.price - q.low) / range
                VStack(spacing: 3) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Color.secondary.opacity(0.15)).frame(height: 5)
                            Circle()
                                .fill(priceUp ? Color.red : Color.blue)
                                .frame(width: 9, height: 9)
                                .offset(x: (geo.size.width - 9) * pos)
                        }
                    }
                    .frame(height: 9)
                    HStack {
                        Text("저 \(q.low.formatted())").font(.system(size: 9)).foregroundColor(.blue)
                        Spacer()
                        Text("현재 위치").font(.system(size: 9)).foregroundColor(.secondary)
                        Spacer()
                        Text("고 \(q.high.formatted())").font(.system(size: 9)).foregroundColor(.red)
                    }
                }
            }
            Divider()
            // 거래량 + 해석. 시가=0이면 장 전이므로 신호 표시 안 함.
            if q.open == 0 {
                Text("장 시작 전 거래 데이터가 없어요")
                    .font(.caption).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 4)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .lastTextBaseline, spacing: 4) {
                        Text("거래량").font(.caption).foregroundColor(.secondary)
                        Text("\(q.volume.formatted())주").font(.caption.weight(.semibold))
                        if avg20Vol > 0 {
                            Text("(평소의 \(String(format: "%.1f", volRatio))배)")
                                .font(.system(size: 10)).foregroundColor(.secondary)
                        }
                    }
                    let intradayPos: Double? = q.high > q.low
                        ? Double(q.price - q.low) / Double(q.high - q.low) : nil
                    let (emoji, title, desc) = volPriceSignal(priceUp: priceUp, ratio: volRatio,
                                                              intradayPos: intradayPos)
                    HStack(alignment: .top, spacing: 8) {
                        Text(emoji).font(.title3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).font(.caption.weight(.semibold))
                            Text(desc).font(.caption2).foregroundColor(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.secondary.opacity(0.07))
                    .cornerRadius(10)
                }
            }
        }
    }

    private func ohlcStat(_ label: String, _ value: String, valueColor: Color = .primary) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Text(value).font(.callout.weight(.semibold)).foregroundColor(valueColor)
        }
    }

    private func volPriceSignal(priceUp: Bool, ratio: Double, intradayPos: Double?) -> (String, String, String) {
        let r = String(format: "%.1f", ratio)
        // 거래량 4티어
        enum VolTier { case surge, high, normal, low }
        let tier: VolTier = ratio >= 2.5 ? .surge : ratio >= 1.5 ? .high : ratio >= 0.7 ? .normal : .low

        // 장중 위치 부가 설명
        let posNote: String = {
            guard let p = intradayPos else { return "" }
            if p >= 0.75 { return " 고가권에서 마감해 강세가 끝까지 유지됐어요." }
            if p <= 0.25 { return " 저가권에서 마감해 낙폭을 회복하지 못했어요." }
            return ""
        }()

        switch (priceUp, tier) {
        case (true, .surge):
            return ("🚀", "폭발적 매수세",
                "평소의 \(r)배 거래량이 터지며 올랐어요. 기관·세력의 대량 매수가 들어왔을 가능성이 높아요. 다음 날 추가 상승인지 차익실현인지가 핵심이에요.\(posNote)")
        case (true, .high):
            return ("📈", "강한 매수세",
                "거래량이 \(r)배로 실리며 가격이 올랐어요. 상승에 힘이 있는 날이에요. 거래량이 계속 동반되는지 확인해 보세요.\(posNote)")
        case (true, .normal):
            return ("↗️", "조심스러운 상승",
                "거래량 없이 올랐어요. 매수 주체가 약해 다음 날 되돌릴 수 있어요. 내일 거래량이 늘며 가격이 버텨주는지가 포인트예요.\(posNote)")
        case (true, .low):
            return ("🌤️", "거래위축 상승",
                "평소보다 거래가 적은데 올랐어요. 매도 압력이 약해 오른 것으로, 추세로 이어지려면 거래량이 동반돼야 해요.\(posNote)")
        case (false, .surge):
            return ("💥", "투매성 하락",
                "평소의 \(r)배 거래량이 터지며 내렸어요. 대량 매도가 출회된 날이에요. 악재 확인이 필요하고, 단기 반등을 노린 저가 매수가 들어올 수도 있어요.\(posNote)")
        case (false, .high):
            return ("📉", "강한 매도세",
                "거래량이 \(r)배로 실리며 가격이 내렸어요. 하락에 힘이 실린 날이에요. 지지선을 이탈했는지 확인해 보세요.\(posNote)")
        case (false, .normal):
            return ("↘️", "완만한 하락",
                "평범한 거래량에 소폭 내렸어요. 뚜렷한 악재보다는 차익실현이나 관망 분위기예요. 거래량이 터지지 않으면 추세 하락은 아닐 수 있어요.\(posNote)")
        case (false, .low):
            return ("😴", "소강 하락",
                "거래도 적고 가격도 내렸어요. 뚜렷한 매도 주체 없이 관심이 식는 신호일 수 있어요. 거래량이 줄면서 하락하는 패턴은 장기 추세 약화 시그널이에요.\(posNote)")
        }
    }

    // 가격 차트 범례.
    // 1행: 차트 요소 아이콘(고저폭·종가·추세선·기준선 아이콘)
    // 2행: 기준선 값 칩(목표/평단/손절 입력돼 있을 때만) — 차트 안 annotation 제거 대신 여기 표시
    private func priceChartLegend(avg: Double?, target: Double?, stop: Double?) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            // 1행: 선 종류 아이콘
            HStack(spacing: 8) {
                HStack(spacing: 3) {
                    Rectangle().fill(Color.primary.opacity(0.12))
                        .frame(width: 12, height: 8).cornerRadius(2)
                    Text("고저 폭").foregroundColor(.secondary)
                }
                legendLine("종가", .primary, dash: false)
                legendLine("추세선", .orange, dash: true)
                Button {
                    withAnimation(.easeInOut(duration: 0.15)) { trendLineHelpExpanded.toggle() }
                } label: {
                    Image(systemName: trendLineHelpExpanded ? "info.circle.fill" : "info.circle")
                        .font(.system(size: 10))
                        .foregroundColor(.orange.opacity(0.8))
                }
                Spacer()
            }
            .font(.caption2)


            if trendLineHelpExpanded {
                Text("추세선(주황 점선): 최근 20거래일 종가 평균. 현재가가 위면 단기 상승추세, 아래면 하락추세.\n고저 폭(회색 띠): 각 날의 하루 중 가격 변동 범위(고가~저가).")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func legendLine(_ label: String, _ color: Color, dash: Bool) -> some View {
        HStack(spacing: 3) {
            if dash {
                HStack(spacing: 2) {
                    ForEach(0..<3, id: \.self) { _ in
                        Rectangle().fill(color).frame(width: 3, height: 1.5)
                    }
                }
            } else {
                Rectangle().fill(color).frame(width: 12, height: 2)
            }
            Text(label).foregroundColor(.secondary)
        }
    }

    private func legendMark(_ label: String, _ symbol: String, _ color: Color) -> some View {
        HStack(spacing: 3) {
            Image(systemName: symbol).font(.system(size: 7)).foregroundColor(color)
            Text(label).foregroundColor(.secondary)
        }
    }

    private func miniStat(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Spacer()
            Text(value).font(.caption2.weight(.medium))
        }
    }

    // 내 포지션 카드(1.5b/c). 평단가·수량이 있으면 현재가로 수익률/평가손익 계산, 목표·손절은 거리(%)와 도달 여부.
    @ViewBuilder
    private func positionCard(_ q: Quote) -> some View {
        let price = Double(q.price)
        VStack(spacing: 0) {
            HStack {
                Text("내 포지션").font(.subheadline.weight(.semibold))
                // 계좌 컨텍스트 배지 — 2개 이상 계좌 보유 시에만(1개면 전체=그 계좌라 무의미).
                if accountHoldings.count >= 2 {
                    Menu {
                        Button { switchContext(nil) } label: {
                            if accountContext == nil { Label("전체(합산)", systemImage: "checkmark") }
                            else { Text("전체(합산)") }
                        }
                        ForEach(accountHoldings, id: \.accountId) { h in
                            Button { switchContext(h.accountId) } label: {
                                if accountContext == h.accountId {
                                    Label(accountNames[h.accountId] ?? "계좌", systemImage: "checkmark")
                                } else {
                                    Text(accountNames[h.accountId] ?? "계좌")
                                }
                            }
                        }
                    } label: {
                        HStack(spacing: 3) {
                            Text(contextLabel)
                            Image(systemName: "chevron.up.chevron.down").font(.system(size: 8))
                        }
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 7).padding(.vertical, 3)
                        .background(accountContext == nil ? Color(.systemGray5) : Color.indigo.opacity(0.12))
                        .foregroundColor(accountContext == nil ? .secondary : .indigo)
                        .clipShape(Capsule())
                    }
                }
                Spacer()
                Button { showEdit = true } label: {
                    Text(item.avgPrice == nil ? "입력" : "수정").font(.caption)
                }
            }
            .padding(.vertical, 8)

            if let avgNum = item.avgPrice, let qtyNum = item.qty {
                Divider()
                let avg = avgNum.doubleValue
                let qty = Double(qtyNum.int64Value)
                let pnl = (price - avg) * qty                  // 평가손익
                let rate = avg == 0 ? 0 : (price - avg) / avg * 100   // 수익률 %
                let up = pnl >= 0
                row("평단가", "\(Int(avg).formatted()) 원")
                row("수량", "\(qtyNum.int64Value.formatted()) 주")
                row("평가금액", "\(Int(price * qty).formatted()) 원")
                coloredRow("평가손익", "\(up ? "+" : "")\(Int(pnl).formatted()) 원", up)
                coloredRow("수익률", "\(up ? "+" : "")\(String(format: "%.2f", rate))%", up)
                // 전체(합산) 컨텍스트일 때 계좌별 소계 — "계좌별로 얼마지?"는 배지 탭 없이 여기서 해결.
                if accountContext == nil && accountHoldings.count >= 2 {
                    let priced = accountHoldings.filter { ($0.avgPrice?.doubleValue ?? 0) > 0 && ($0.qty?.int64Value ?? 0) > 0 }
                    if priced.count >= 2 {
                        Divider()
                        VStack(alignment: .leading, spacing: 3) {
                            ForEach(priced, id: \.accountId) { h in
                                Text("\(accountNames[h.accountId] ?? "계좌")  \(h.qty!.int64Value.formatted())주 @\(Int(h.avgPrice!.doubleValue).formatted())원")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 6)
                    }
                }
            } else {
                Text("평단가·수량을 입력하면 내 수익률을 보여줘요")
                    .font(.footnote).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            }

            let targetP = item.targetPrice?.doubleValue
            let stopP   = item.stopPrice?.doubleValue
            if targetP != nil || stopP != nil { Divider() }
            if let t = targetP {
                upsideGauge(currentPrice: price, targetPrice: t,
                            avgPrice: item.avgPrice?.doubleValue, stopPrice: stopP)
            } else if let s = stopP {
                let gap     = (s - price) / price * 100
                let reached = price <= s
                row("손절가", "\(Int(s).formatted()) 원  " + (reached ? "⚠️ 도달" : String(format: "(%+.1f%%)", gap)))
            }

            if let t = item.thesis, !t.isEmpty {
                Divider()
                VStack(alignment: .leading, spacing: 4) {
                    Text("투자 논지").font(.caption).foregroundColor(.secondary)
                    Text(t).font(.footnote)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 8)
            }
        }
        .cardStyle()
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .padding(.vertical, 8)
    }

    // 손익/수익률처럼 부호에 따라 색이 바뀌는 행(상승=빨강·하락=파랑).
    private func coloredRow(_ label: String, _ value: String, _ up: Bool) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.semibold).foregroundColor(up ? .red : .blue)
        }
        .padding(.vertical, 8)
    }

    // U1 존 그룹 헤더 — 상세 화면 6개 존(현재상황·종합판단·AI근거·심화분석·외부환경·내기록)을
    // 화면에 실제 위계로 드러낸다. 작은 캡션 스타일(영문은 대문자, 한글은 그대로).
    private func zoneHeader(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundColor(.secondary)
            .textCase(.uppercase)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
            .accessibilityAddTraits(.isHeader)
    }

    // R5 델타 스트립 — 전일 대비 달라진 항목 한 줄씩. U1: 제목 한 줄로 정체를 밝힘.
    private func deltaStrip(_ lines: [String]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 5) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.caption2.weight(.semibold))
                Text("어제와 달라진 점")
                    .font(.caption.weight(.semibold))
            }
            .foregroundColor(.secondary)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(lines, id: \.self) { line in
                    HStack(alignment: .top, spacing: 6) {
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 5, height: 5)
                            .padding(.top, 5)
                        Text(line).font(.caption).foregroundColor(.secondary)
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(10)
    }

    // R5 기술적 지표 — 기본 접힘 래퍼
    private func technicalCardCollapsible(_ r: TechnicalResult, price: Double) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("기술적 지표").font(.subheadline.weight(.semibold))
                Spacer()
                Image(systemName: technicalExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { technicalExpanded.toggle() }; if technicalExpanded { Usage.shared.expand("detail", "기술적 지표") } }
            if technicalExpanded {
                Divider()
                technicalCard(r, price: price)
                    .padding(.top, 4)
            }
        }
        .padding(.horizontal, 12)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(10)
    }

    // R5 수급 — 기본 접힘 래퍼
    private func flowCardCollapsible() -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("수급 · 순매수").font(.subheadline.weight(.semibold))
                Spacer()
                Image(systemName: flowExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { flowExpanded.toggle() }; if flowExpanded { Usage.shared.expand("detail", "수급 · 순매수") } }
            if flowExpanded {
                Divider()
                flowCard()
                    .padding(.top, 4)
            }
        }
        .padding(.horizontal, 12)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(10)
    }

    // 지표 해석 ① 계산 기반(LLM 없음). 이미 받은 시세·수급으로 즉시 계산한 "위치/흐름" 요약.
    // 사실만 보여주고 매수/매도 판단은 하지 않는다(그건 추후 Claude 층).
    // 결론 배지 정책: 접힘 헤더 trailing = "펼칠 가치 판단 재료" 원칙.
    // 각 카드는 접힌 상태에서도 핵심 한 줄이 보여야 한다.
    // · 지표 해석 → 52주 구간 라벨 / · 검증된 신호 → 신호 N개
    // · 유사 국면 → 국면 N건(이미) / · 뉴스·공시·프리모템·밸류에이션 → 기존 배지 유지
    @ViewBuilder
    private func analysisCard(_ q: Quote) -> some View {
        let ctx = StockAnalysis.shared.priceContext(q: q)
        let streaks = StockAnalysis.shared.flowStreaks(flows: flows)
        let hasValuation = q.per > 0 || q.pbr > 0
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("지표 해석").font(.subheadline.weight(.semibold))
                Spacer()
                if let c = ctx {
                    Text(rangeLabel(c.pctInRange52w)).font(.caption2).foregroundColor(.secondary)
                }
                Image(systemName: analysisExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { analysisExpanded.toggle() }; if analysisExpanded { Usage.shared.expand("detail", "지표 해석") } }
            if analysisExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 8) {
                    if let c = ctx {
                        rangeGauge(c.pctInRange52w)
                        insight("52주 고점 대비", String(format: "%.1f%%", c.pctFromHigh52w))
                        insight("52주 저점 대비", String(format: "+%.1f%%", c.pctFromLow52w))
                    }
                    if hasValuation {
                        if ctx != nil { Divider() }
                        if !q.sectorName.isEmpty {
                            HStack {
                                Text("업종").foregroundColor(.secondary)
                                Spacer()
                                Text(q.sectorName).fontWeight(.medium)
                            }
                            .font(.caption)
                        }
                        if q.per > 0 {
                            valuationRow("PER", String(format: "%.2f배", q.per),
                                "이 회사가 지금처럼 벌면 몇 년 치 이익이 쌓여야 지금 주가만큼 되는지예요. 낮을수록 버는 것에 비해 주가가 싼 편이고, 성장 기대가 크면 높게 매겨져요.",
                                expandable: true)
                        }
                        if q.pbr > 0 {
                            valuationRow("PBR", String(format: "%.2f배", q.pbr),
                                "회사가 가진 재산(장부가치) 대비 주가예요. 1배면 딱 장부가치 수준, 낮을수록 자산 대비 싼 편.",
                                expandable: true)
                        }
                        if q.per > 0 || q.pbr > 0 {
                            Button {
                                withAnimation(.easeInOut(duration: 0.2)) { valuationHelpExpanded.toggle() }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "info.circle")
                                    Text(valuationHelpExpanded ? "설명 접기" : "PER·PBR이 뭐죠?")
                                    Image(systemName: valuationHelpExpanded ? "chevron.up" : "chevron.down")
                                        .font(.system(size: 9))
                                }
                                .font(.caption2).foregroundColor(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if let tp = targetPriceInfo {
                        if q.per > 0 || q.pbr > 0 || !q.sectorName.isEmpty { Divider() }
                        let upside = Double(tp.price - q.price) / Double(q.price) * 100
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text("컨센서스 목표주가").foregroundColor(.secondary)
                                Spacer()
                                Text("\(tp.price.formatted())원").fontWeight(.medium)
                                Text("\(upside >= 0 ? "▲" : "▼")\(String(format: "%.1f%%", abs(upside)))")
                                    .fontWeight(.semibold)
                                    .foregroundColor(upside >= 5 ? .red : upside < -5 ? .blue : .secondary)
                            }
                            Text(tp.basis).font(.caption2).foregroundColor(.secondary)
                        }
                        .font(.caption)
                    }
                    if !streaks.isEmpty {
                        if ctx != nil || hasValuation { Divider() }
                        ForEach(streaks, id: \.investor) { s in
                            HStack(spacing: 6) {
                                Circle().fill(s.buying ? Color.red : Color.blue).frame(width: 6, height: 6)
                                Text("\(s.investor) \(s.days)일 연속 \(s.buying ? "순매수" : "순매도")")
                                Spacer()
                                Text("누적 \(flowText(s.net))")
                                    .foregroundColor(s.buying ? .red : .blue)
                                    .font(.caption.monospacedDigit())
                            }
                            .font(.caption)
                        }
                    }
                }
                .padding(.top, 8).padding(.bottom, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    // 라벨 + 값 한 줄(작은 caption). 지표 해석용.
    private func insight(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .font(.caption)
    }

    // 밸류에이션 한 줄: 위에 라벨·값, 아래에 "무슨 뜻인지" 짧은 설명(회색 caption).
    // expandable=true면 설명을 ⓘ 토글(valuationHelpExpanded)로 접어둔다.
    @ViewBuilder
    private func valuationRow(_ label: String, _ value: String, _ meaning: String, expandable: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(label).foregroundColor(.secondary)
                Spacer()
                Text(value).fontWeight(.medium)
            }
            .font(.caption)
            if !expandable || valuationHelpExpanded {
                Text(meaning).font(.caption2).foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // 52주 범위 내 위치(%)를 사람이 읽는 구간 라벨로. 판단이 아니라 위치 설명.
    private func rangeLabel(_ pct: Double) -> String {
        switch pct {
        case ..<25: return "저점권"
        case ..<50: return "중하단"
        case ..<75: return "중상단"
        default: return "고점권"
        }
    }

    // 52주 위치 게이지 바. 텍스트 위치 + 컬러 배지 + 진행 바.
    private func rangeGauge(_ pct: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("52주 위치").foregroundColor(.secondary)
                Spacer()
                Text("\(Int(pct.rounded()))%  \(rangeLabel(pct))")
                    .fontWeight(.medium)
                    .foregroundColor(gaugeColor(pct))
            }
            .font(.caption)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.systemFill)).frame(height: 5)
                    Capsule()
                        .fill(gaugeColor(pct))
                        .frame(width: max(5, geo.size.width * pct / 100.0), height: 5)
                }
            }
            .frame(height: 5)
        }
    }

    private func gaugeColor(_ pct: Double) -> Color {
        switch pct {
        case ..<25: return .blue
        case ..<75: return .secondary
        default: return .red
        }
    }

    // dailyBars 개수를 거래일 기준으로 개월/년 문자열로 환산. 22거래일 ≈ 1개월.
    private var allPeriodLabel: String {
        let n = dailyBars.count
        guard n > 0 else { return "전체" }
        let months = max(1, Int((Double(n) / 22.0).rounded()))
        if months >= 12 {
            let y = months / 12
            let m = months % 12
            return m == 0 ? "\(y)년" : "\(y)년\(m)개월"
        }
        return "\(months)개월"
    }

    // 목표가 상승여력 게이지. 앵커(손절가 or 평단가 or 추정 하한)~목표가 바에 현재가 위치 표시.
    private func upsideGauge(currentPrice: Double, targetPrice: Double, avgPrice: Double?, stopPrice: Double?) -> some View {
        let upside  = (targetPrice - currentPrice) / currentPrice * 100
        let reached = currentPrice >= targetPrice
        let anchor: Double = stopPrice ?? avgPrice ?? min(currentPrice * 0.85, targetPrice * 0.75)
        let range   = max(targetPrice - anchor, 1)
        let rawProg = (currentPrice - anchor) / range
        let progress = min(max(rawProg, 0), 1.05)

        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("목표가까지").foregroundColor(.secondary)
                Spacer()
                Text(reached ? "🎯 도달" : String(format: "%+.1f%%", upside))
                    .fontWeight(.semibold)
                    .foregroundColor(reached ? .green : upside < 5 ? .orange : .primary)
            }
            .font(.caption)

            GeometryReader { geo in
                let dotX = min(geo.size.width * progress, geo.size.width - 1)
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.systemFill)).frame(height: 5)
                    Capsule()
                        .fill(reached ? Color.green : Color.orange)
                        .frame(width: max(dotX, 5), height: 5)
                    RoundedRectangle(cornerRadius: 1)
                        .fill(reached ? Color.green : Color.orange)
                        .frame(width: 2.5, height: 14)
                        .offset(x: dotX - 1.25, y: -4.5)
                }
            }
            .frame(height: 10)

            HStack(alignment: .top) {
                if let s = stopPrice {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("\(Int(s).formatted())원").font(.caption2)
                        Text("손절").font(.caption2).foregroundColor(.blue)
                    }
                } else if let a = avgPrice {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("\(Int(a).formatted())원").font(.caption2)
                        Text("평단").font(.caption2).foregroundColor(.secondary)
                    }
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 1) {
                    Text("\(Int(targetPrice).formatted())원").font(.caption2)
                    Text("목표").font(.caption2).foregroundColor(.red)
                }
            }
        }
        .padding(.vertical, 4)
    }

    // AI 종합 코멘트 카드(2c). 사실(시세·52주·PER·수급·뉴스)을 백엔드가 모아 Claude가 해석.
    // Claude 생성이라 수 초 걸려 별도 로딩. 매매 판단/책임은 사용자 — 참고용 디스클레이머를 항상 붙인다.
    @ViewBuilder
    private func aiCommentCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "sparkles").foregroundColor(.purple)
                Text("AI 종합 코멘트").font(.subheadline.weight(.semibold))
                if analysisMode == .aggressive {
                    Text("⚔️ 공격적 모드")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.orange.opacity(0.15))
                        .foregroundColor(.orange)
                        .clipShape(Capsule())
                }
                // 특정 계좌 컨텍스트면 코멘트가 그 계좌 포지션 기준임을 표시.
                if let ctx = accountContext, accountHoldings.count >= 2 {
                    Text("\(accountNames[ctx] ?? "계좌") 기준")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.indigo.opacity(0.12))
                        .foregroundColor(.indigo)
                        .clipShape(Capsule())
                }
                Spacer()
                if analyzing { ProgressView().scaleEffect(0.8) }
            }
            .padding(.top, 8)

            // S13: 논지 변경 힌트 — 저장 직후 이전 논지 기준 코멘트가 그대로 있을 때 안내.
            if analysisThesisChanged {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.triangle.2.circlepath").font(.caption).foregroundColor(.indigo)
                    Text("논지가 바뀌었어요. 새로고침하면 새 논지 기준으로 코멘트가 만들어져요.")
                        .font(.caption).foregroundColor(.secondary)
                    Spacer()
                    Button {
                        analysisThesisChanged = false
                        Task { await loadAnalysis(force: true) }
                    } label: {
                        Text("새로고침").font(.caption.weight(.semibold)).foregroundColor(.indigo)
                    }
                }
                .padding(8)
                .background(Color.indigo.opacity(0.07))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }

            if let a = analysis {
                // 판단 변화 배지 — 직전 생성분 스탠스와 비교. 전환이 정보라 강조, 유지는 조용한 회색.
                if let stance = a.stance, let prev = a.prevStance {
                    HStack(spacing: 6) {
                        if stance == prev {
                            Text("\(stance) 유지")
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Color(.systemGray5))
                                .foregroundColor(.secondary)
                                .clipShape(Capsule())
                        } else {
                            HStack(spacing: 4) {
                                Text(prev).foregroundColor(.secondary)
                                Image(systemName: "arrow.right").font(.system(size: 9, weight: .bold)).foregroundColor(.secondary)
                                Text(stance).foregroundColor(stanceColor(stance))
                            }
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 9).padding(.vertical, 4)
                            .background(stanceColor(stance).opacity(0.12))
                            .clipShape(Capsule())
                        }
                        if let d = a.prevStanceDate {
                            Text("\(shortMonthDay(d)) 분석 대비").font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                    }
                    .padding(.bottom, 2)
                }

                // 핵심 요약 — 풀 코멘트 위에 강조 박스(보라). summary 없으면(옛 캐시) 건너뜀.
                if let summary = a.summary, !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 4) {
                            Image(systemName: "pin.fill").font(.caption2)
                            Text("핵심 요약").font(.caption.weight(.bold))
                        }
                        .foregroundColor(.purple)
                        Text(markdown(summary))
                            .font(.callout)
                            .lineSpacing(5)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.purple.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .padding(.bottom, 4)
                }

                let sections = parseCommentSections(a.comment)
                let collapsible = sections.count > 2
                let visible = (collapsible && !commentExpanded) ? Array(sections.prefix(2)) : sections

                // 보라 액센트 바 + 소제목 강조 + 본문 줄간격 (BriefingView proseBlock 톤과 통일)
                HStack(alignment: .top, spacing: 10) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.purple.opacity(0.35))
                        .frame(width: 3)
                    VStack(alignment: .leading, spacing: 16) {
                        ForEach(visible) { sec in
                            VStack(alignment: .leading, spacing: 6) {
                                if let h = sec.heading {
                                    Text(h)
                                        .font(.subheadline.weight(.bold))
                                        .foregroundColor(.purple)
                                }
                                ForEach(Array(sec.body.enumerated()), id: \.offset) { _, p in
                                    Text(markdown(p))
                                        .font(.callout)
                                        .lineSpacing(5)
                                        .fixedSize(horizontal: false, vertical: true)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                        }
                    }
                }

                if collapsible {
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) { commentExpanded.toggle() }
                    } label: {
                        HStack(spacing: 4) {
                            Text(commentExpanded ? "접기" : "더보기")
                            Image(systemName: commentExpanded ? "chevron.up" : "chevron.down")
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundColor(.purple)
                    }
                    .padding(.top, 2)
                }

                if let r = a.factsRichness {
                    factsRichnessRow(r)
                        .padding(.top, 4)
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .center, spacing: 0) {
                        Text(aiCommentFreshLabel(a))
                            .font(.caption2).foregroundColor(.secondary)
                        Spacer()
                        if analyzing {
                            ProgressView().scaleEffect(0.7)
                        } else if isSundayReuse {
                            // 일요일: 주말 동안 시세가 그대로라 직전(토요일) 분석을 재사용 → 재생성 잠금.
                            Text("주말엔 그대로라 직전 분석 표시")
                                .font(.caption2).foregroundColor(.secondary)
                        } else {
                            Button {
                                Task { await loadAnalysis(force: true) }
                            } label: {
                                Label("재생성", systemImage: "arrow.clockwise")
                                    .font(.caption2)
                                    .foregroundColor(.purple)
                            }
                            .help("지금 시점 데이터로 AI 코멘트를 다시 생성합니다")
                        }
                    }
                    Text("투자 판단과 책임은 본인에게 있습니다")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 2)
            } else if analyzing {
                Text("시세·수급·뉴스를 종합해 코멘트를 생성하고 있어요…")
                    .font(.footnote).foregroundColor(.secondary)
            } else {
                Text("코멘트를 불러오지 못했어요.")
                    .font(.footnote).foregroundColor(.secondary)
                Button {
                    Task { await loadAnalysis(force: false) }
                } label: {
                    Label("다시 시도", systemImage: "arrow.clockwise")
                        .font(.caption.weight(.semibold))
                        .foregroundColor(.purple)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 6)
        .cardStyle()
    }

    // 기술적 지표 카드. 한눈에 보는 시각 요약(추세 신호등 + RSI 게이지 + 거래량) 먼저,
    // 자세한 숫자·용어 설명은 ⓘ로 접어둔다(타고 들어가기). 계산만, 판단 없음.
    private func technicalCard(_ r: TechnicalResult, price: Double) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("기술적 지표").font(.subheadline.weight(.semibold)).padding(.top, 8)

            // ── 시각 요약: 추세 신호등 + RSI 게이지 ──
            HStack(alignment: .top, spacing: 16) {
                trendSignal(r, price: price)
                if r.rsi14 != nil {
                    Divider().frame(height: 44)
                    rsiGauge(r.rsi14!.doubleValue)
                }
            }

            // ── 거래량 ──
            if let v = r.volumeRatio?.doubleValue {
                volumeBadge(v)
            }

            // ── 접이식 설명 ──
            indicatorHelp(r, price: price)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 6)
        .cardStyle()
    }

    // 추세 신호등: MA5/MA20/MA60 각각 현재가가 위면 빨강(상승)·아래면 파랑(하락) 점.
    private func trendSignal(_ r: TechnicalResult, price: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("추세").font(.caption2).foregroundColor(.secondary)
            HStack(spacing: 12) {
                trendDot("MA5",  r.ma5?.doubleValue,  price)
                trendDot("MA20", r.ma20?.doubleValue, price)
                trendDot("MA60", r.ma60?.doubleValue, price)
            }
        }
    }

    // 이평선 한 칸: 위 점(색) + 아래 라벨. 현재가≥선이면 빨강↑, 미만이면 파랑↓.
    @ViewBuilder
    private func trendDot(_ label: String, _ ma: Double?, _ price: Double) -> some View {
        let above: Bool? = ma.map { price >= $0 }
        VStack(spacing: 3) {
            Circle()
                .fill(above == nil ? Color(.systemFill) : (above! ? Color.red : Color.blue))
                .frame(width: 14, height: 14)
                .overlay {
                    if let ab = above {
                        Image(systemName: ab ? "arrow.up" : "arrow.down")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
            Text(label).font(.system(size: 9)).foregroundColor(.secondary)
        }
    }

    // RSI 게이지: 0~100 바 + 30/70 구간 마커 + 현재 위치. 숫자보다 위치가 직관적.
    private func rsiGauge(_ v: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Text("RSI").font(.caption2).foregroundColor(.secondary)
                Text(String(format: "%.0f", v)).font(.caption.weight(.semibold)).foregroundColor(rsiColor(v))
                if !rsiLabel(v).isEmpty {
                    Text(rsiLabel(v)).font(.system(size: 9)).foregroundColor(rsiColor(v))
                }
            }
            GeometryReader { geo in
                let w = geo.size.width
                ZStack(alignment: .leading) {
                    // 3구간 배경: 과매도(파랑)·중립(회색)·과매수(빨강)
                    HStack(spacing: 0) {
                        Rectangle().fill(Color.blue.opacity(0.18)).frame(width: w * 0.3)
                        Rectangle().fill(Color(.systemFill).opacity(0.5))
                        Rectangle().fill(Color.red.opacity(0.18)).frame(width: w * 0.3)
                    }
                    .frame(height: 6)
                    .clipShape(Capsule())
                    // 현재 위치 마커
                    Circle()
                        .fill(rsiColor(v))
                        .frame(width: 10, height: 10)
                        .overlay(Circle().stroke(Color(.systemBackground), lineWidth: 1.5))
                        .offset(x: max(0, min(w - 10, w * CGFloat(v) / 100 - 5)))
                }
            }
            .frame(height: 10)
        }
        .frame(maxWidth: .infinity)
    }

    // 거래량 배지: 평소 대비 배수. 2배 이상이면 주황 강조 + 불꽃.
    private func volumeBadge(_ v: Double) -> some View {
        let hot = v >= 2.0
        return HStack(spacing: 6) {
            Image(systemName: hot ? "flame.fill" : "chart.bar.fill")
                .font(.caption2)
                .foregroundColor(hot ? .orange : .secondary)
            Text("거래량 평소의 \(String(format: "%.1f", v))배")
                .font(.caption)
                .foregroundColor(hot ? .orange : .primary)
            if hot { Text("거래 급증").font(.system(size: 9)).foregroundColor(.orange) }
            Spacer()
        }
    }

    // 접이식 지표 설명. 평소엔 ⓘ 한 줄, 탭하면 각 지표 뜻을 친근하게 풀어준다.
    @ViewBuilder
    private func indicatorHelp(_ r: TechnicalResult, price: Double) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { indicatorHelpExpanded.toggle() }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "info.circle")
                    Text(indicatorHelpExpanded ? "설명 접기" : "이게 무슨 뜻이죠?")
                    Image(systemName: indicatorHelpExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 9))
                }
                .font(.caption2).foregroundColor(.secondary)
            }
            .buttonStyle(.plain)

            if indicatorHelpExpanded {
                VStack(alignment: .leading, spacing: 8) {
                    helpItem("추세 신호등",
                        "최근 5·20·60일 평균값보다 지금 주가가 위에 있으면 빨강↑(오름세), 아래면 파랑↓(내림세)예요. 셋 다 빨강이면 단기·중기·장기 모두 상승 흐름.")
                    if let v = r.rsi14?.doubleValue {
                        helpItem("RSI \(String(format: "%.0f", v))",
                            "주가가 얼마나 달아올랐는지 0~100으로 보는 막대예요. 70 넘으면 좀 과열(🔴), 30 밑이면 너무 식음(🔵). 지금은 \(rsiPlainLabel(v)).")
                    }
                    if let v = r.volumeRatio?.doubleValue {
                        helpItem("거래량 \(String(format: "%.1f", v))배",
                            "최근 거래일 거래량을 최근 20일 평균과 비교한 거예요. 2배 넘으면 평소보다 사람이 확 몰린 것 — 큰 뉴스나 수급 변화 신호일 수 있어요.")
                    }
                    // 정확한 이평선 값(참고용)
                    if r.ma5 != nil || r.ma20 != nil || r.ma60 != nil {
                        Divider()
                        HStack(spacing: 10) {
                            if let v = r.ma5?.doubleValue  { maValueChip("5일", v) }
                            if let v = r.ma20?.doubleValue { maValueChip("20일", v) }
                            if let v = r.ma60?.doubleValue { maValueChip("60일", v) }
                        }
                    }
                }
                .padding(.top, 8)
            }
        }
    }

    private func helpItem(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption.weight(.semibold))
            Text(body).font(.caption2).foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func maValueChip(_ label: String, _ v: Double) -> some View {
        VStack(spacing: 1) {
            Text(label).font(.system(size: 9)).foregroundColor(.secondary)
            Text("\(Int(v.rounded()).formatted())").font(.caption2.weight(.medium).monospacedDigit())
        }
    }

    // RSI를 말로: 과매수권/과매도권/중립.
    private func rsiPlainLabel(_ v: Double) -> String {
        if v >= 70 { return "좀 달아오른 편이에요" }
        if v <= 30 { return "많이 식은 편이에요" }
        return "적당한 편이에요"
    }

    private func rsiColor(_ v: Double) -> Color {
        if v >= 70 { return .red }
        if v <= 30 { return .blue }
        return .primary
    }

    private func rsiLabel(_ v: Double) -> String {
        if v >= 70 { return "과매수권" }
        if v <= 30 { return "과매도권" }
        return ""
    }

    // 수급 카드. 상단: 외인·기관 방향 막대 차트(시각적). 하단: 전체 정확한 수치표.
    private func flowCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text("수급 · 순매수").font(.subheadline.weight(.semibold))
                Spacer()
                dataTag("전일 확정")
            }
            .padding(.top, 8)
            Chart(flowChartData) { e in
                BarMark(x: .value("날짜", e.date), y: .value("순매수", e.shares))
                    .foregroundStyle(by: .value("투자자", e.investor))
                    .position(by: .value("투자자", e.investor))
            }
            .chartForegroundStyleScale(["외인": Color.orange.opacity(0.85), "기관": Color.teal.opacity(0.85)])
            .chartXAxis { AxisMarks { _ in AxisValueLabel().font(.caption2) } }
            .chartYAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { v in
                    if let d = v.as(Double.self) {
                        AxisValueLabel { Text(flowText(Int64(d))).font(.caption2) }
                    }
                    AxisGridLine()
                }
            }
            .chartLegend(position: .top, alignment: .trailing)
            .frame(height: 110)
            Divider()
            Grid(alignment: .trailing, horizontalSpacing: 10, verticalSpacing: 8) {
                GridRow {
                    Text("날짜").gridColumnAlignment(.leading)
                    Text("외국인"); Text("기관"); Text("개인")
                }
                .font(.caption).foregroundColor(.secondary)
                Divider().gridCellColumns(4)
                ForEach(flows, id: \.date) { f in
                    GridRow {
                        Text(mmdd(f.date)).gridColumnAlignment(.leading).foregroundColor(.secondary)
                        flowCell(f.foreign)
                        flowCell(f.institution)
                        flowCell(f.individual)
                    }
                    .font(.caption.monospacedDigit())
                }
            }
            .padding(.bottom, 6)
            // U2: 수급-가격 민감도 흡수 — "외인·기관이 살수록 이 종목이 같이 올랐나" 상관을 수급 카드 안에서.
            flowSensSection()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    // U2 수급-가격 민감도 서브섹션(구 독립 카드 → '수급' 카드 하단으로 흡수). 데이터 없으면 렌더 안 함.
    @ViewBuilder
    private func flowSensSection() -> some View {
        if let fs = flowSensitivity {
            let shown = fs.items.filter { $0.n > 0 }
            if !shown.isEmpty {
                let days = Int(shown.first?.n ?? 0)
                Divider().padding(.vertical, 2)
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("수급-가격 민감도").font(.caption.weight(.semibold)).foregroundColor(.secondary)
                        Spacer()
                        Text("최근 \(days)거래일").font(.caption2).foregroundColor(.secondary)
                    }
                    Text("외인·기관이 많이 살수록 이 종목 주가가 그날 같이 올랐나요?")
                        .font(.caption2).foregroundColor(.secondary)
                    ForEach(Array(shown.enumerated()), id: \.offset) { idx, fc in
                        if idx > 0 { Divider() }
                        flowCorrRow(fc)
                    }
                    Text("수급은 전일까지 장후 확정값 기준이에요. 과거 통계라 미래를 보장하지 않아요.")
                        .font(.caption2).foregroundColor(.secondary)
                }
            }
        }
    }

    private var flowChartData: [FlowEntry] {
        flows.flatMap { f in [
            FlowEntry(id: "\(f.date)_외인", date: mmdd(f.date), investor: "외인", shares: Double(f.foreign)),
            FlowEntry(id: "\(f.date)_기관", date: mmdd(f.date), investor: "기관", shares: Double(f.institution)),
        ]}
    }

    private func flowCell(_ n: Int64) -> some View {
        Text(flowText(n)).foregroundColor(n > 0 ? .red : (n < 0 ? .blue : .secondary))
    }

    // 순매수 수량 축약: 1.2억 / 14만 / 0.5만 / 234. 부호 포함.
    private func flowText(_ n: Int64) -> String {
        if n == 0 { return "0" }
        let sign = n > 0 ? "+" : "-"
        let a = Double(abs(n))
        if a >= 1e8 { return sign + String(format: "%.1f억", a / 1e8) }
        if a >= 1e4 { return sign + String(format: "%.0f만", a / 1e4) }
        if a >= 1e3 { return sign + String(format: "%.1f만", a / 1e4) }
        return sign + Int64(a).formatted()
    }

    // "20260602" → "06/02"
    private func mmdd(_ d: String) -> String {
        guard d.count == 8 else { return d }
        let m = d.dropFirst(4).prefix(2)
        let day = d.suffix(2)
        return "\(m)/\(day)"
    }

    // "참고용 · 오늘 09:32 생성 · 232,000원 기준" 형태.
    private func aiCommentFreshLabel(_ a: Analysis) -> String {
        var label: String
        if !a.generatedAt.isEmpty {
            let todayStr = todayDateString()
            if todayStr == a.date {
                label = "참고용 · 오늘 \(a.generatedAt) 생성"
            } else {
                label = "참고용 · \(a.date) \(a.generatedAt) 생성"
            }
        } else {
            label = "참고용 · \(a.date) 기준"
        }
        if let gp = a.generatedPrice {
            let price = Int(gp.doubleValue)
            if price > 0 {
                let fmt = NumberFormatter()
                fmt.numberStyle = .decimal
                let priceStr = fmt.string(from: NSNumber(value: price)) ?? "\(price)"
                label += " · \(priceStr)원 기준"
            }
        }
        return label
    }

    // 근거 데이터 두께 — 코멘트 생성에 사용된 소스를 작은 칩으로 표시.
    @ViewBuilder
    private func factsRichnessRow(_ r: FactsRichness) -> some View {
        let chips: [(String, Bool)] = [
            (r.newsCount > 0 ? "뉴스 \(r.newsCount)건" : "뉴스 없음", r.newsCount > 0),
            ("수급", r.hasInvestorFlow),
            ("연간재무", r.hasFinancials),
            ("분기실적", r.hasQuarterlyIncome),
            ("공매도", r.hasShortSelling),
            ("밸류밴드", r.hasValuationBand),
            ("백테스트", r.hasBacktest),
            ("수급민감도", r.hasFlowSensitivity),
        ]
        VStack(alignment: .leading, spacing: 4) {
            Text("근거 데이터").font(.caption2).foregroundColor(.secondary)
            ChipFlowLayout(spacing: 4) {
                ForEach(Array(chips.enumerated()), id: \.offset) { _, chip in
                    Text(chip.0)
                        .font(.caption2)
                        .fixedSize()
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(chip.1 ? Color.purple.opacity(0.12) : Color(.systemFill))
                        .foregroundColor(chip.1 ? .purple : .secondary)
                        .cornerRadius(4)
                }
            }
        }
    }

    // "전일 확정", "실시간" 등 데이터 출처를 나타내는 작은 pill 태그.
    private func dataTag(_ label: String) -> some View {
        Text(label)
            .font(.caption2)
            .foregroundColor(.secondary)
            .padding(.horizontal, 5).padding(.vertical, 2)
            .background(Color(.systemFill))
            .cornerRadius(4)
    }

    private func todayDateString() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = TimeZone(identifier: "Asia/Seoul")
        return f.string(from: Date())
    }

    // 일요일(KST): 백엔드가 토요일 분석을 재사용(데이터 동일) → 재생성 잠금 + 안내.
    private var isSundayReuse: Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return cal.component(.weekday, from: Date()) == 1
    }

    // "YYYYMMDD"(일봉 date) → "M/d(요일)". 오늘 탭 거래일 표시용.
    private func tradingDayLabel(_ ymd8: String) -> String {
        let inF = DateFormatter(); inF.dateFormat = "yyyyMMdd"; inF.timeZone = TimeZone(identifier: "Asia/Seoul")
        guard ymd8.count == 8, let d = inF.date(from: ymd8) else { return "" }
        let outF = DateFormatter()
        outF.locale = Locale(identifier: "ko_KR"); outF.timeZone = inF.timeZone; outF.dateFormat = "M/d(E)"
        return outF.string(from: d)
    }

    // 밸류에이션 히스토리 밴드 카드 — PER/PBR 현재값을 과거 N년 밴드 위에 표시
    // U2 통합 밸류에이션 카드 — 역사 밴드(밸류에이션 히스토리) + 동종 상대 밸류를 한 헤더 아래 세그먼트로.
    @ViewBuilder
    private func valuationCard(_ band: ValuationBand?, _ pv: PeerValuation?) -> some View {
        let showBandPer = (band?.perCurrent ?? 0) > 0 && (band?.perMax ?? 0) > (band?.perMin ?? 0)
        let showBandPbr = (band?.pbrCurrent ?? 0) > 0 && (band?.pbrMax ?? 0) > (band?.pbrMin ?? 0)
        let hasBand = showBandPer || showBandPbr
        let hasPeer = (pv?.per != nil) || (pv?.pbr != nil)
        if hasBand || hasPeer {
            // 접힘 상태 결론 배지 — 역사 밴드 위치(상단권/하단권)를 우선, 없으면 동종 대비 라벨.
            let bandBadge: String? = showBandPer ? band?.perLabel : (showBandPbr ? band?.pbrLabel : nil)
            let peerBadge: String? = pv?.per?.label ?? pv?.pbr?.label
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("밸류에이션").font(.subheadline.weight(.semibold))
                    Spacer()
                    if let bl = bandBadge {
                        Text(bl).font(.caption2)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(valuationBandColor(bl).opacity(0.15))
                            .foregroundColor(valuationBandColor(bl)).cornerRadius(8)
                    } else if let pl = peerBadge {
                        Text(pl).font(.caption2)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(peerValuationColor(pl).opacity(0.15))
                            .foregroundColor(peerValuationColor(pl)).cornerRadius(8)
                    }
                    Image(systemName: valuationExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { valuationExpanded.toggle() }; if valuationExpanded { Usage.shared.expand("detail", "밸류에이션") } }
                if valuationExpanded {
                    Divider()
                    VStack(alignment: .leading, spacing: 12) {
                        if hasBand, let b = band {
                            Text("역사 밴드 · \(b.yearsUsed)년").font(.caption.weight(.semibold)).foregroundColor(.secondary)
                            if showBandPer {
                                valuationBandRow(
                                    name: "PER", current: b.perCurrent,
                                    bandMin: b.perMin, bandMax: b.perMax, median: b.perMedian,
                                    percentile: Int(b.perPercentile), label: b.perLabel
                                )
                            }
                            if showBandPbr {
                                if showBandPer { Divider() }
                                valuationBandRow(
                                    name: "PBR", current: b.pbrCurrent,
                                    bandMin: b.pbrMin, bandMax: b.pbrMax, median: b.pbrMedian,
                                    percentile: Int(b.pbrPercentile), label: b.pbrLabel
                                )
                            }
                            Text("연도말 종가 기준, 상장주식수 근사치 — 분할·증자 시 오차 가능")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        if hasPeer, let p = pv {
                            if hasBand { Divider() }
                            Text("동종 대비 · \(p.clusterLabel) 경쟁사 \(p.peerCount)곳").font(.caption.weight(.semibold)).foregroundColor(.secondary)
                            if let m = p.per { peerMetricRow(name: "PER", m: m) }
                            if let m = p.pbr {
                                if p.per != nil { Divider() }
                                peerMetricRow(name: "PBR", m: m)
                            }
                            Text("같은 사업 경쟁사 중앙값과 비교 — KIS 기준값, 상대 위치 참고용")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.top, 8).padding(.bottom, 4)
                }
            }
            .cardStyle()
        }
    }

    private func valuationBandRow(name: String, current: Double, bandMin: Double, bandMax: Double, median: Double, percentile: Int, label: String) -> some View {
        let color = valuationBandColor(label)
        let fraction = bandMax > bandMin ? CGFloat((current - bandMin) / (bandMax - bandMin)) : 0.5
        let clampedFraction = fraction < 0 ? 0.0 : (fraction > 1 ? 1.0 : fraction)

        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(name).font(.caption.weight(.semibold)).foregroundColor(.secondary)
                Text(String(format: "%.2f배", current)).font(.caption.weight(.bold))
                Spacer()
                Text(label)
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(color.opacity(0.15))
                    .foregroundColor(color)
                    .cornerRadius(8)
            }
            // 범위 바 + 현재 위치 마커
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color.gray.opacity(0.18))
                        .frame(height: 6)
                    // 중앙값 눈금
                    if bandMax > bandMin {
                        let midFraction = CGFloat((median - bandMin) / (bandMax - bandMin))
                        Rectangle()
                            .fill(Color.gray.opacity(0.5))
                            .frame(width: 1.5, height: 10)
                            .offset(x: midFraction * geo.size.width - 0.75, y: -2)
                    }
                    // 현재값 마커
                    RoundedRectangle(cornerRadius: 2)
                        .fill(color)
                        .frame(width: 3, height: 14)
                        .offset(x: clampedFraction * (geo.size.width - 3), y: -4)
                }
            }
            .frame(height: 14)
            HStack {
                Text(String(format: "%.1f배", bandMin)).font(.caption2).foregroundColor(.secondary)
                Spacer()
                Text("중앙 \(String(format: "%.1f배", median))").font(.caption2).foregroundColor(.secondary)
                Spacer()
                Text(String(format: "%.1f배", bandMax)).font(.caption2).foregroundColor(.secondary)
            }
        }
    }

    private func valuationBandColor(_ label: String) -> Color {
        switch label {
        case "역사적 하단권":   return .blue
        case "역사적 상단권":   return .red
        default:               return .orange   // 중간권·계산 불가
        }
    }

    private func peerMetricRow(name: String, m: PeerMetric) -> some View {
        let color = peerValuationColor(m.label)
        let lo = min(m.peerMin, m.current), hi = max(m.peerMax, m.current)
        let frac = hi > lo ? CGFloat((m.current - lo) / (hi - lo)) : 0.5
        let clamped = frac < 0 ? 0.0 : (frac > 1 ? 1.0 : frac)
        let diff = String(format: "%@%.0f%%", m.diffPct >= 0 ? "+" : "", m.diffPct)

        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(name).font(.caption.weight(.semibold)).foregroundColor(.secondary)
                Text(String(format: "%.2f배", m.current)).font(.caption.weight(.bold))
                Text("(\(diff))").font(.caption2).foregroundColor(color)
                Spacer()
                Text(m.label)
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(color.opacity(0.15))
                    .foregroundColor(color)
                    .cornerRadius(8)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color.gray.opacity(0.18))
                        .frame(height: 6)
                    if hi > lo {
                        let midFrac = CGFloat((m.peerMedian - lo) / (hi - lo))
                        Rectangle()
                            .fill(Color.gray.opacity(0.5))
                            .frame(width: 1.5, height: 10)
                            .offset(x: midFrac * geo.size.width - 0.75, y: -2)
                    }
                    RoundedRectangle(cornerRadius: 2)
                        .fill(color)
                        .frame(width: 3, height: 14)
                        .offset(x: clamped * (geo.size.width - 3), y: -4)
                }
            }
            .frame(height: 14)
            HStack {
                Text(String(format: "동종 최저 %.1f", m.peerMin)).font(.caption2).foregroundColor(.secondary)
                Spacer()
                Text(String(format: "중앙 %.1f배", m.peerMedian)).font(.caption2).foregroundColor(.secondary)
                Spacer()
                Text(String(format: "최고 %.1f", m.peerMax)).font(.caption2).foregroundColor(.secondary)
            }
        }
    }

    private func peerValuationColor(_ label: String) -> Color {
        switch label {
        case "동종 대비 낮음":   return .blue   // 동종보다 싼 편
        case "동종 대비 높음":   return .red
        default:               return .orange  // 비슷
        }
    }

    // 검증된 신호 카드 — 외인·기관 순매수·거래량 급증일의 익일 적중률(이 종목 실측)
    private func backtestCard(_ bt: Backtest) -> some View {
        let shown = bt.signals.filter { $0.n > 0 }
        guard !shown.isEmpty else { return AnyView(EmptyView()) }
        return AnyView(
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("검증된 신호").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("신호 \(shown.count)개").font(.caption2).foregroundColor(.secondary)
                    Image(systemName: backtestExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { backtestExpanded.toggle() }; if backtestExpanded { Usage.shared.expand("detail", "검증된 신호") } }
                if backtestExpanded {
                    Divider()
                    VStack(alignment: .leading, spacing: 12) {
                        Text("평소 익일 상승확률 \(Int(bt.baselineWinRate))% · 평균 \(String(format: "%+.2f", bt.baselineAvgReturn))% (세로선=평소 기준)")
                            .font(.caption2).foregroundColor(.secondary)
                        ForEach(Array(shown.enumerated()), id: \.offset) { idx, s in
                            if idx > 0 { Divider() }
                            backtestRow(s, baseline: Int(bt.baselineWinRate))
                        }
                        Text("이 종목 과거 통계일 뿐 미래를 보장하지 않아요. 표본(n)이 작으면 참고만 하세요.")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(.top, 8).padding(.bottom, 4)
                }
            }
            .cardStyle()
        )
    }

    private func backtestRow(_ s: SignalResult, baseline: Int) -> some View {
        let win = Int(s.winRate)
        let confident = s.confident
        let edgeUp = s.edge >= 0
        let accent: Color = edgeUp ? .red : .blue
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(s.signal).font(.caption.weight(.semibold))
                Text("표본 \(Int(s.n))일").font(.caption2).foregroundColor(.secondary)
                Spacer()
                if confident {
                    Text("\(edgeUp ? "+" : "")\(String(format: "%.1f", s.edge))%p")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(accent.opacity(0.15)).foregroundColor(accent).cornerRadius(8)
                } else {
                    Text("표본 부족")
                        .font(.caption2)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.gray.opacity(0.15)).foregroundColor(.secondary).cornerRadius(8)
                }
            }
            // 익일 상승확률 바 + 평소(baseline) 기준선
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color.gray.opacity(0.18)).frame(height: 6)
                    RoundedRectangle(cornerRadius: 3)
                        .fill((confident ? accent : Color.gray).opacity(confident ? 0.7 : 0.4))
                        .frame(width: geo.size.width * CGFloat(win) / 100.0, height: 6)
                    Rectangle()
                        .fill(Color.primary.opacity(0.5))
                        .frame(width: 1.5, height: 10)
                        .offset(x: geo.size.width * CGFloat(baseline) / 100.0 - 0.75, y: -2)
                }
            }
            .frame(height: 10)
            HStack {
                Text("익일 상승확률 \(win)%")
                    .font(.caption2).foregroundColor(confident ? .primary : .secondary)
                Spacer()
                Text("평균 \(String(format: "%+.2f", s.avgReturn))%")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        .opacity(confident ? 1.0 : 0.6)
    }

    // 매수 프리모템 카드(F5) — 매수 가설이 깨지는 조건 목록 + 발동 상태
    private func premortemCard(_ pm: Premortem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("매수 가설 점검").font(.subheadline.weight(.semibold))
                Spacer()
                // T2: evaluable+active만 "감시 중". !evaluable+active는 "기록"으로 분리.
                let watchCount  = pm.invalidations.filter { $0.active && $0.evaluable }.count
                let recordCount = pm.invalidations.filter { $0.active && !$0.evaluable }.count
                if watchCount > 0 || recordCount > 0 {
                    let label = [
                        watchCount  > 0 ? "감시 중 \(watchCount)개"  : nil,
                        recordCount > 0 ? "기록 \(recordCount)개"   : nil,
                    ].compactMap { $0 }.joined(separator: " · ")
                    Text(label).font(.caption2).foregroundColor(.secondary)
                }
                Image(systemName: premortemExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { premortemExpanded.toggle() }; if premortemExpanded { Usage.shared.expand("detail", "매수 가설 점검") } }
            if premortemExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 10) {
                    if !pm.reason.isEmpty {
                        Text("매수 사유: \(pm.reason)").font(.caption).foregroundColor(.secondary)
                    }
                    if !pm.bullCase.isEmpty {
                        Text("맞다면: \(pm.bullCase)").font(.caption)
                    }
                    if !pm.bearCase.isEmpty {
                        Text("틀렸다면: \(pm.bearCase)").font(.caption)
                    }
                    if !pm.invalidations.isEmpty {
                        Divider()
                        Text("무효화 조건").font(.caption.weight(.semibold))
                        ForEach(Array(pm.invalidations.enumerated()), id: \.offset) { _, inv in
                            HStack(alignment: .top, spacing: 6) {
                                // T2: evaluable=false(기록만)인 active 조건은 눈 아이콘 대신 메모 아이콘.
                                let isRecordOnly = inv.active && !inv.evaluable
                                Image(systemName: inv.active
                                    ? (isRecordOnly ? "note.text" : "eye")
                                    : "exclamationmark.triangle.fill")
                                    .font(.caption2)
                                    .foregroundColor(inv.active ? .secondary : .orange)
                                VStack(alignment: .leading, spacing: 1) {
                                    HStack(spacing: 4) {
                                        Text(inv.desc).font(.caption)
                                            .foregroundColor(inv.active ? .primary : .orange)
                                        if isRecordOnly {
                                            Text("기록만")
                                                .font(.system(size: 9, weight: .medium))
                                                .foregroundColor(.secondary)
                                                .padding(.horizontal, 4).padding(.vertical, 1)
                                                .background(Color(.systemFill))
                                                .clipShape(Capsule())
                                        }
                                    }
                                    if let anchor = inv.anchor {
                                        Text(anchor).font(.caption2).foregroundColor(.secondary)
                                    }
                                    if let fired = inv.firedAt {
                                        Text("발동됨 · \(String(fired.prefix(10)))").font(.caption2).foregroundColor(.orange)
                                    }
                                }
                            }
                        }
                    }
                    Text("가설이 틀렸음을 빨리 알기 위한 조건이에요. 발동해도 매매 지시가 아니라 점검 신호예요.")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 8).padding(.bottom, 4)
            }
        }
        .cardStyle()
    }

    // 매매 복기 카드(B2) — 완결된 매매의 과정/결과 분리 복기
    private func tradeReviewCard(_ tr: TradeReview) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("매매 복기").font(.subheadline.weight(.semibold))
                Spacer()
                let pctColor: Color = tr.realizedPct >= 0 ? .red : .blue
                Text(String(format: "%+.1f%%", tr.realizedPct)).font(.caption2.weight(.semibold))
                    .foregroundColor(pctColor)
                Image(systemName: tradeReviewExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { tradeReviewExpanded.toggle() }; if tradeReviewExpanded { Usage.shared.expand("detail", "매매 복기") } }
            if tradeReviewExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 10) {
                    // 기간 요약
                    HStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("매수").font(.caption2).foregroundColor(.secondary)
                            Text(String(tr.buyDate.prefix(10))).font(.caption)
                        }
                        Image(systemName: "arrow.right").font(.caption2).foregroundColor(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("매도").font(.caption2).foregroundColor(.secondary)
                            Text(String(tr.sellDate.prefix(10))).font(.caption)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("보유").font(.caption2).foregroundColor(.secondary)
                            Text("\(tr.holdingTradingDays)거래일").font(.caption)
                        }
                    }
                    if let highClose = tr.periodHighClose, let highDate = tr.periodHighDate,
                       let vsHigh = tr.sellVsHighPct {
                        // KotlinLong? / KotlinDouble? → .int64Value / .doubleValue
                        let hcVal = highClose.int64Value
                        let vsVal = vsHigh.doubleValue
                        Divider()
                        HStack {
                            Text("구간 최고").font(.caption2).foregroundColor(.secondary)
                            Text("\(hcVal.formatted())원 (\(String(highDate.prefix(10))))")
                                .font(.caption)
                            Spacer()
                            Text(String(format: "매도가 %+.1f%%", vsVal))
                                .font(.caption2)
                                .foregroundColor(vsVal >= 0 ? .red : .blue)
                        }
                    }
                    if let a5 = tr.afterSell5dPct {
                        let a5Val = a5.doubleValue
                        let a20Val = tr.afterSell20dPct?.doubleValue
                        Divider()
                        HStack {
                            Text("매도 후 추이").font(.caption2).foregroundColor(.secondary)
                            Spacer()
                            Text(String(format: "5일 %+.1f%%", a5Val)).font(.caption2)
                                .foregroundColor(a5Val >= 0 ? .red : .blue)
                            if let a20 = a20Val {
                                Text(String(format: "/ 20일 %+.1f%%", a20)).font(.caption2)
                                    .foregroundColor(a20 >= 0 ? .red : .blue)
                            }
                        }
                    }
                    if let summary = tr.summary {
                        Divider()
                        HStack(alignment: .top, spacing: 6) {
                            Text("📝").font(.caption)
                            Text(markdown(summary)).font(.caption)
                        }
                        .padding(8)
                        .background(Color.orange.opacity(0.08))
                        .cornerRadius(6)
                    }
                    Text(markdown(tr.comment)).font(.caption)
                    if tr.partialHistory {
                        Text("⚠️ 매수일이 일봉 이력 범위 밖 — 구간 수치는 잡힌 범위만의 값").font(.caption2).foregroundColor(.orange)
                    }
                    Text("생성: \(String(tr.generatedAt.prefix(16)).replacingOccurrences(of: "T", with: " "))")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 8).padding(.bottom, 4)
            }
        }
        .cardStyle()
    }

    // 유사 국면 통계 카드(F1) — 오늘 상태와 비슷했던 과거 시점들의 이후 실제 수익률 분포
    private func analogCard(_ an: AnalogReport) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("유사 국면 통계").font(.subheadline.weight(.semibold))
                Spacer()
                Text("과거 \(an.n)개 국면 실측").font(.caption2).foregroundColor(.secondary)
                Image(systemName: analogExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { analogExpanded.toggle() }; if analogExpanded { Usage.shared.expand("detail", "유사 국면 통계") } }
            if analogExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 12) {
                    if let v = an.vectorToday {
                        Text("오늘 상태: 52주 \(Int(v.pos52w))% · 20일 \(String(format: "%+.1f", v.ret20))% · 거래량 \(String(format: "%.1f", v.volumeRatio))배 · RSI \(Int(v.rsi14))")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    ForEach(Array(an.horizons.enumerated()), id: \.offset) { idx, h in
                        if idx > 0 { Divider() }
                        analogHorizonRow(h)
                    }
                    Text(an.caveat)
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 8).padding(.bottom, 4)
            }
        }
        .cardStyle()
    }

    private func analogHorizonRow(_ h: AnalogHorizon) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text("\(h.days)일 후").font(.caption.weight(.semibold))
                Spacer()
                Text("중앙값 \(String(format: "%+.1f", h.median))%")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Text("범위 \(String(format: "%.1f", h.min))~\(String(format: "%+.1f", h.max))%")
                .font(.caption2).foregroundColor(.secondary)
        }
    }

    // 수급-가격 민감도 카드 — 외인/기관 순매수량 vs 당일 등락률 순위 상관(Spearman — 아웃라이어 강건)
    private func flowCorrRow(_ fc: FlowCorrelation) -> some View {
        let r = fc.r
        let confident = fc.confident
        let absR = abs(r)
        let isPositive = r >= 0
        let accent: Color = absR < 0.1 ? .gray : (isPositive ? .red : .blue)

        // 사용자에게 친숙한 한마디
        let plainLabel: String
        if !confident {
            plainLabel = "표본 부족"
        } else if absR < 0.1 {
            plainLabel = "별 관계 없어요"
        } else if isPositive {
            plainLabel = absR < 0.3 ? "조금 같이 올라요" : absR < 0.5 ? "어느 정도 같이 올라요" : "강하게 같이 올라요"
        } else {
            plainLabel = absR < 0.3 ? "조금 반대로 움직여요" : absR < 0.5 ? "어느 정도 반대로 움직여요" : "강하게 반대로 움직여요"
        }

        // 한 줄 설명
        let desc: String?
        if confident && absR >= 0.1 {
            desc = isPositive
                ? "\(fc.investor)이 많이 살수록 그날 주가가 같이 오른 경향이에요"
                : "\(fc.investor)이 많이 살수록 그날 주가가 오히려 내린 경향이에요"
        } else {
            desc = nil
        }

        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(fc.investor).font(.caption.weight(.semibold))
                Text("\(Int(fc.n))일").font(.caption2).foregroundColor(.secondary)
                Spacer()
                Text(plainLabel)
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background((confident && absR >= 0.1 ? accent : Color.gray).opacity(0.15))
                    .foregroundColor(confident && absR >= 0.1 ? accent : .secondary)
                    .cornerRadius(8)
            }
            // 강도 바: 0~1 범위, 색상으로 방향 구분
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color.gray.opacity(0.18)).frame(height: 6)
                    RoundedRectangle(cornerRadius: 3)
                        .fill((confident ? accent : Color.gray).opacity(confident ? 0.7 : 0.4))
                        .frame(width: geo.size.width * CGFloat(min(absR, 1.0)), height: 6)
                }
            }
            .frame(height: 6)
            if let desc {
                Text(desc).font(.caption2).foregroundColor(.secondary)
            }
        }
        .opacity(confident ? 1.0 : 0.6)
    }

    // 공매도 카드 — 거래량·잔고 수치 + ⓘ 공매도 설명 토글
    private func shortSellingCard(_ ss: ShortSellingSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("공매도 동향").font(.subheadline.weight(.semibold))
                Spacer()
                Image(systemName: shortSellingExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { shortSellingExpanded.toggle() }; if shortSellingExpanded { Usage.shared.expand("detail", "공매도 동향") } }
            if shortSellingExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 10) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("공매도는 **주식을 빌려서 파는** 것이에요.").font(.caption)
                        Text("지금 비싸게 팔고 → 나중에 싸게 사서 갚아 차익을 얻는 방식이라, 하락에 베팅하는 세력이 많을수록 **공매도 잔고**가 늘어나요.").font(.caption)
                        HStack(spacing: 16) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("잔고 증가").font(.caption.weight(.semibold)).foregroundColor(.red)
                                Text("하락 베팅 강화\n단기 하락 압력").font(.caption2).foregroundColor(.secondary)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text("잔고 감소").font(.caption.weight(.semibold)).foregroundColor(.blue)
                                Text("숏커버링(청산 매수)\n단기 상승 압력").font(.caption2).foregroundColor(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                        Text("잔고는 T+2일 지연 확정이라, 최신 2거래일은 '집계 중'으로 보여요.")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(10)
                    .background(Color(.systemGray6))
                    .cornerRadius(8)
                    HStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("공매도 거래량").font(.caption).foregroundColor(.secondary)
                            Text("\(formatShortVol(ss.recentVolume))주").font(.footnote.weight(.semibold))
                            Text(ss.recentVolumeDate).font(.caption2).foregroundColor(.secondary)
                        }
                        Divider().frame(height: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("공매도 잔고").font(.caption).foregroundColor(.secondary)
                            if let bal = ss.balance {
                                Text("\(formatShortVol(bal.int64Value))주").font(.footnote.weight(.semibold))
                                HStack(spacing: 4) {
                                    if let pctBox = ss.balanceChangePct {
                                        let pct = pctBox.doubleValue
                                        let isUp = pct > 0.5; let isDown = pct < -0.5
                                        Image(systemName: isUp ? "arrow.up" : isDown ? "arrow.down" : "minus")
                                            .font(.caption2).foregroundColor(isUp ? .red : isDown ? .blue : .secondary)
                                        Text(pct >= 0 ? "+\(String(format: "%.1f", pct))%" : "\(String(format: "%.1f", pct))%")
                                            .font(.caption2).foregroundColor(isUp ? .red : isDown ? .blue : .secondary)
                                    }
                                    if let d = ss.balanceDate {
                                        Text("(\(d) 확정)").font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                            } else {
                                Text("집계 중").font(.footnote).foregroundColor(.secondary)
                                Text("T+2일 지연").font(.caption2).foregroundColor(.secondary)
                            }
                        }
                        Spacer()
                    }
                }
                .padding(.top, 8).padding(.bottom, 4)
            }
        }
        .cardStyle()
    }

    private func dividendCardView(_ div: DividendCard) -> some View {
        let wonfmt = NumberFormatter(); wonfmt.numberStyle = .decimal
        let won: (Int64) -> String = { v in (wonfmt.string(from: NSNumber(value: v)) ?? "\(v)") + "원" }
        let yr = Int(div.fiscalYear)
        var series: [(Int, Int64)] = [(yr, div.dpsThis)]
        if let p = div.dpsPrev?.int64Value   { series.insert((yr - 1, p), at: 0) }
        if let p = div.dpsPrev2?.int64Value  { series.insert((yr - 2, p), at: 0) }
        let seriesText = series.map { "\($0.0) \(won($0.1))" }.joined(separator: " → ")
        let yoyText: String
        if let pct = div.dpsYoyPct?.doubleValue {
            yoyText = " (\(pct >= 0 ? "+" : "")\(String(format: "%.1f", pct))%)"
        } else { yoyText = "" }
        let eyPct = div.expectedYieldPct?.doubleValue
        var refs: [String] = []
        if let v = div.yieldPctAtRecord?.doubleValue { refs.append("배당 시점 시가배당률 \(String(format: "%.1f", v))%") }
        if let v = div.payoutPct?.doubleValue         { refs.append("배당성향 \(String(format: "%.1f", v))%") }
        if let v = div.settleMonth?.int32Value        { refs.append("결산월 \(v)월") }
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("배당 (DART 배당사항)").font(.subheadline.weight(.semibold))
                    if let ey = eyPct {
                        Text("예상 배당수익률 \(String(format: "%.2f", ey))%")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
                Spacer()
                Image(systemName: dividendExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { dividendExpanded.toggle() }; if dividendExpanded { Usage.shared.expand("detail", "배당") } }
            if dividendExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 10) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("주당 현금배당금").font(.caption).foregroundColor(.secondary)
                        Text(seriesText + yoyText).font(.footnote)
                    }
                    if let ey = eyPct {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("예상 배당수익률").font(.caption).foregroundColor(.secondary)
                            Text(String(format: "%.2f%%", ey)).font(.footnote.weight(.semibold))
                            Text("최신 주당배당금 ÷ 현재가 · 차기 배당 미확정")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    if !refs.isEmpty {
                        Text(refs.joined(separator: " · ")).font(.caption2).foregroundColor(.secondary)
                    }
                    Text("\(yr) 사업연도 확정값 기준 · 참고용")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 8).padding(.bottom, 4)
            }
        }
        .cardStyle()
    }

    private func formatShortVol(_ vol: Int64) -> String {
        if vol >= 10_000 {
            return String(format: "%.1f만", Double(vol) / 10_000)
        } else {
            let f = NumberFormatter()
            f.numberStyle = .decimal
            return f.string(from: NSNumber(value: vol)) ?? "\(vol)"
        }
    }

    // ── 뉴스·공시 영향 (호재/악재 판정) — 접이식 ──
    // 뉴스·공시를 카드 단위로 호재/악재·강도·선반영까지 판정. 접어도 netBias 배지로 결론은 보인다.
    // Claude 호출이라 로딩이 느릴 수 있음(백엔드 30분 캐시 적중 시 즉시).
    @ViewBuilder
    private func catalystCard() -> some View {
        if catalysts == nil && !catalystsLoading && catalystAttempted {
            // 로드 실패(Claude 오류/타임아웃). 뉴스·공시 원문 섹션을 없앴으므로 빈 화면 방지용 폴백.
            HStack(spacing: 8) {
                Image(systemName: "newspaper").foregroundColor(.secondary)
                Text("뉴스·공시 영향을 불러오지 못했어요").font(.caption).foregroundColor(.secondary)
                Spacer()
                Button("다시 시도") { Task { await loadCatalysts(force: true) } }
                    .font(.caption.weight(.semibold))
            }
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardStyle()
        } else if catalysts != nil || catalystsLoading {
            VStack(spacing: 0) {
                HStack(spacing: 6) {
                    Image(systemName: "newspaper").foregroundColor(.orange)
                    Text("뉴스·공시 영향").font(.subheadline.weight(.semibold))
                    Spacer()
                    if let rep = catalysts {
                        Text(rep.netBias)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(netBiasColor(rep.netBias).opacity(0.15))
                            .foregroundColor(netBiasColor(rep.netBias))
                            .clipShape(Capsule())
                    } else {
                        ProgressView().scaleEffect(0.8)
                    }
                    Image(systemName: catalystExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { catalystExpanded.toggle() }; if catalystExpanded { Usage.shared.expand("detail", "뉴스·공시 영향") } }

                if catalystExpanded {
                    if let rep = catalysts {
                        VStack(alignment: .leading, spacing: 8) {
                            if !rep.summary.isEmpty {
                                Text(rep.summary)
                                    .font(.caption).foregroundColor(.primary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            if rep.items.isEmpty {
                                Text("최근 7일 새 재료(공시·뉴스)가 없습니다.")
                                    .font(.caption2).foregroundColor(.secondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            } else {
                                ForEach(rep.items, id: \.url) { c in
                                    Divider()
                                    catalystRow(c)
                                }
                            }
                        }
                        .padding(.bottom, 8)
                    } else {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("재료 분석 중…").font(.caption).foregroundColor(.secondary)
                            Spacer()
                        }
                        .padding(.bottom, 10)
                    }
                }
            }
            .cardStyle()
        }
    }

    private func catalystRow(_ c: CatalystItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                // 호재(빨강)/악재(파랑)/중립(회색) + 강도. "규모"를 병기해 강도가 재료의 사업적
                // 크기(연매출 대비)이지 주가 반응 예측이 아님을 표기 — ②-1 실측에서 강도 상의
                // 익일 반응이 오히려 최저(0/8)였다(docs/catalyst-validation-2026-07.md).
                Text("\(c.sentiment) · 규모 \(c.strength)")
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(sentimentColor(c.sentiment).opacity(0.15))
                    .foregroundColor(sentimentColor(c.sentiment))
                    .clipShape(Capsule())
                Text(c.category).font(.caption2).foregroundColor(.secondary)
                Spacer()
                Text(c.source).font(.caption2).foregroundColor(.secondary)
                Text("·").foregroundColor(.secondary)
                Text(catalystDate(c)).font(.caption2).foregroundColor(.secondary)
            }
            Link(destination: URL(string: c.url) ?? URL(string: "https://news.naver.com")!) {
                Text(c.title.trimmingCharacters(in: .whitespacesAndNewlines))
                    .font(.caption).foregroundColor(.primary)
                    .lineLimit(2).multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !c.reason.isEmpty {
                Text(c.reason).font(.caption2).foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if c.preReflected {
                // 경고(⚠·주황) → 사실 서술(ⓘ·회색): "선반영이니 기대 낮춤"이라는 해석 지시를
                // 실측이 지지하지 않아 사실만 표기(docs/catalyst-validation-2026-07.md ③).
                HStack(alignment: .top, spacing: 3) {
                    Image(systemName: "info.circle.fill")
                        .font(.system(size: 9)).foregroundColor(.secondary)
                    Text("재료 전후 주가가 이미 크게 움직임" + (c.preReflectedNote.map { " · \($0)" } ?? ""))
                        .font(.caption2).foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            // F2 임팩트 통계 — 수주·공급계약 공시에만 표시
            if c.category == "수주·공급계약", c.source == "공시",
               let imp = catalystImpact, imp.n > 0 {
                let day1 = imp.horizons.first(where: { $0.days == 1 })
                let day5 = imp.horizons.first(where: { $0.days == 5 })
                let parts = [day1.map { "익일 평균 \(formatPct($0.avgPct))" },
                             day5.map { "5일 \(formatPct($0.avgPct))" }].compactMap { $0 }
                if !parts.isEmpty {
                    HStack(spacing: 3) {
                        Image(systemName: "chart.bar.xaxis")
                            .font(.system(size: 9)).foregroundColor(.secondary)
                        Text("과거 수주 공시 \(imp.n)건: " + parts.joined(separator: ", "))
                            .font(.caption2).foregroundColor(.secondary)
                    }
                }
            }
        }
        .padding(.vertical, 5)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // 공시는 YYYYMMDD, 뉴스는 RFC822 → 표기 분기.
    private func catalystDate(_ c: CatalystItem) -> String {
        c.source == "공시" ? formattedDate8(c.date) : shortDate(c.date)
    }

    private func sentimentColor(_ s: String) -> Color {
        switch s {
        case "호재": return .red   // 한국 관례: 상승/호재 = 빨강
        case "악재": return .blue
        default:     return .gray
        }
    }

    // +3.2% / -1.5% 형식
    private func formatPct(_ v: Double) -> String {
        String(format: "%+.1f%%", v)
    }

    private func netBiasColor(_ b: String) -> Color {
        switch b {
        case "호재우위": return .red
        case "악재우위": return .blue
        default:         return .gray  // 혼조·중립
        }
    }

    // "Wed, 04 Jun 2026 10:30:00 +0900" → "06/04 10:30"
    private func shortDate(_ raw: String) -> String {
        // RFC 822 파싱. 실패하면 원본 앞부분만.
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"
        guard let date = fmt.date(from: raw) else {
            return String(raw.prefix(16))
        }
        let out = DateFormatter()
        out.dateFormat = "MM/dd HH:mm"
        return out.string(from: date)
    }

    private func loadLogs() {
        logEntries = logRepo.getByCode(code: item.code, limit: 5)
    }

    // MARK: - 계좌 컨텍스트

    /// 계좌별 holding 행·계좌명 로드 후 현재 컨텍스트를 item에 적용.
    private func loadAccountContext() {
        accountHoldings = Db.holding.byCode(code: item.code)
        accountNames = Dictionary(uniqueKeysWithValues:
            (Db.account.all() as! [AccountInfo]).map { ($0.id, $0.name) })
        // 2개 미만 계좌면 전체=단일 계좌라 컨텍스트 구분이 무의미 → 전체로 정규화(배지도 숨김)
        if accountHoldings.count < 2 { accountContext = nil }
        applyContext()
    }

    /// 컨텍스트에 맞춰 item의 포지션 필드를 갈아끼운다(카드·차트 기준선·AI 코멘트 공통 반영).
    private func applyContext() {
        if let ctx = accountContext,
           let h = accountHoldings.first(where: { $0.accountId == ctx }) {
            item = WatchItem(code: item.code, name: item.name,
                             avgPrice: h.avgPrice, qty: h.qty,
                             targetPrice: h.targetPrice, stopPrice: h.stopPrice,
                             thesis: item.thesis)
        } else {
            item = Db.holding.hydrate(item: WatchItem(
                code: item.code, name: item.name,
                avgPrice: nil, qty: nil, targetPrice: nil, stopPrice: nil,
                thesis: item.thesis))
        }
    }

    /// 배지 메뉴에서 계좌 선택. 포지션이 바뀌므로 AI 코멘트도 해당 컨텍스트로 다시 조회
    /// (컨텍스트별 캐시 키가 달라 서버에서 자연 분리, 같은 날 재전환은 캐시 적중).
    private func switchContext(_ accountId: Int64?) {
        guard accountContext != accountId else { return }
        accountContext = accountId
        applyContext()
        analysis = nil
        Task { await loadAnalysis() }
    }

    /// 컨텍스트 배지 라벨 — 전체면 "전체 · N계좌", 계좌면 계좌명.
    private var contextLabel: String {
        if let ctx = accountContext {
            return accountNames[ctx] ?? "계좌"
        }
        return "전체 · \(accountHoldings.count)계좌"
    }

    /// 현재 컨텍스트의 계좌 성격 — 특정 계좌면 그 계좌, 전체면 보유 계좌 전부 장기일 때만 "long"
    /// (혼합 판정: 하나라도 자유면 자유). DB에서 직접 읽어 상태 로드 순서와 무관하게 정확.
    private var contextHorizon: String? {
        let ids: [KotlinLong]
        if let ctx = accountContext {
            ids = [KotlinLong(longLong: ctx)]
        } else {
            ids = Db.holding.byCode(code: item.code).map { KotlinLong(longLong: $0.accountId) }
        }
        return Db.account.effectiveHorizon(accountIds: ids) == "long" ? "long" : nil
    }

    // 실적 일정 — 접기 섹션
    @ViewBuilder
    private func earningsDueDateSection() -> some View {
        if let e = earningsEntry {
            let days = Int(e.daysUntil)
            VStack(spacing: 0) {
                HStack(spacing: 6) {
                    Image(systemName: "calendar").foregroundColor(.blue)
                    Text("실적 일정").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(ddayBadge(days)).font(.caption2.weight(.semibold)).foregroundColor(ddayBadgeColor(days))
                    Image(systemName: earningsExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption).foregroundColor(.secondary)
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { earningsExpanded.toggle() }; if earningsExpanded { Usage.shared.expand("detail", "실적 일정") } }
                if earningsExpanded {
                    Divider()
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(e.reportName).font(.caption)
                            Text("제출 기한: \(formattedDate8(e.dueDate))").font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                        Text(ddayBadge(days))
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(ddayBadgeColor(days).opacity(0.15))
                            .foregroundColor(ddayBadgeColor(days))
                            .clipShape(Capsule())
                    }
                    .padding(.vertical, 8)
                }
            }
            .cardStyle()
        }
    }

    private func ddayBadge(_ days: Int) -> String {
        switch days {
        case 0:    return "D-day"
        case 1...: return "D-\(days)"
        default:   return "D+\(abs(days))"
        }
    }
    private func ddayBadgeColor(_ days: Int) -> Color {
        switch days {
        case ..<14: return .red
        case ..<30: return .orange
        default:    return .secondary
        }
    }

    // 지표 영향 — 접기 섹션 (섹터 + 지표별 우호/부담)
    @ViewBuilder
    private func formattedDate8(_ d: String) -> String {
        guard d.count == 8 else { return d }
        return "\(d.prefix(4)).\(d.dropFirst(4).prefix(2)).\(d.suffix(2))"
    }

    // 행동 로그 카드. 이 종목의 최근 기록(최대 5건). 있을 때만 표시.
    private func logCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("행동 기록").font(.subheadline.weight(.semibold)).padding(.top, 8)
            ForEach(logEntries, id: \.id) { entry in
                let isSwiped = swipedLogId == entry.id
                ZStack(alignment: .trailing) {
                    Button(role: .destructive) {
                        logRepo.delete(id: entry.id)
                        withAnimation { logEntries.removeAll { $0.id == entry.id }; swipedLogId = nil }
                    } label: {
                        Image(systemName: "trash.fill")
                            .foregroundColor(.white)
                            .frame(width: 68)
                            .frame(maxHeight: .infinity)
                            .background(Color.red)
                            .cornerRadius(8)
                    }
                    HStack(spacing: 8) {
                        actionBadge(entry.action)
                        if let r = entry.reason {
                            Text(r).font(.caption).lineLimit(1)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 1) {
                            Text(shortTs(entry.createdAt))
                                .font(.caption2).foregroundColor(.secondary)
                            if let p = entry.price {
                                Text("\(p.int64Value.formatted())원")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .background(Color(.secondarySystemBackground))
                    .offset(x: isSwiped ? -80 : 0)
                    .animation(.easeInOut(duration: 0.2), value: isSwiped)
                    .gesture(
                        DragGesture(minimumDistance: 20)
                            .onEnded { v in
                                withAnimation {
                                    if v.translation.width < -40 { swipedLogId = entry.id }
                                    else if v.translation.width > 20 { swipedLogId = nil }
                                }
                            }
                    )
                    .onTapGesture { withAnimation { swipedLogId = nil } }
                }
                .clipped()
                if entry.id != logEntries.last?.id { Divider() }
            }
            .padding(.bottom, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    private func actionBadge(_ action: String) -> some View {
        let (label, color): (String, Color) = switch action {
        case "buy":  ("매수", .red)
        case "sell": ("매도", .blue)
        default:     ("관심", .orange)
        }
        return Text(label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
    }

    // epoch millis → "06/04 21:30"
    private func shortTs(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let fmt = DateFormatter()
        fmt.dateFormat = "MM/dd HH:mm"
        return fmt.string(from: date)
    }

    // 판단 변화 배지 색: 한국 컨벤션(긍정=빨강·부정=파랑·중립=회색).
    private func stanceColor(_ s: String) -> Color {
        switch s {
        case "긍정": return .red
        case "부정": return .blue
        default: return .secondary
        }
    }

    // "2026-07-10" → "7/10" (배지 캡션용)
    private func shortMonthDay(_ d: String) -> String {
        let parts = d.split(separator: "-")
        guard parts.count == 3, let m = Int(parts[1]), let day = Int(parts[2]) else { return d }
        return "\(m)/\(day)"
    }

    // 취소선 제거 + **굵게** 직접 파싱. SwiftUI 마크다운 파서는 "**+2.4%**에"처럼
    // 굵은 구간 뒤에 한글이 바로 붙으면 CommonMark 경계 규칙 탓에 별표를 그대로 남기는
    // 버그가 있어, 한글 문장에선 쓸 수 없다. 그래서 굵게는 우리가 직접 적용한다.
    private func markdown(_ s: String) -> AttributedString { boldMarkdown(s) }

    // AI 코멘트를 (소제목, 본문 단락들) 섹션으로 파싱. **소제목**만 있는 블록을 헤더로 인식,
    // 이어지는 블록들을 그 섹션의 본문으로 묶는다. 헤더 없는 옛 포맷도 한 섹션으로 안전 처리.
    private struct CommentSection: Identifiable {
        let id = UUID()
        let heading: String?
        let body: [String]
    }

    private func parseCommentSections(_ comment: String) -> [CommentSection] {
        let blocks = comment.components(separatedBy: "\n\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0 != "---" }
        var sections: [CommentSection] = []
        var heading: String? = nil
        var body: [String] = []
        func flush() {
            if heading != nil || !body.isEmpty {
                sections.append(CommentSection(heading: heading, body: body))
            }
            heading = nil; body = []
        }
        for b in blocks {
            if let h = headingOnly(b) {
                flush()
                heading = h
            } else {
                body.append(b)
            }
        }
        flush()
        return sections
    }

    // "**최근 흐름**" 처럼 짧고 통째로 굵은 줄 → 소제목 텍스트, 아니면 nil(본문).
    private func headingOnly(_ s: String) -> String? {
        guard s.hasPrefix("**"), s.hasSuffix("**"), s.count > 4 else { return nil }
        let inner = String(s.dropFirst(2).dropLast(2))
        guard !inner.contains("**"), !inner.contains("\n"), inner.count <= 20 else { return nil }
        return inner
    }

    private func load() async {
        loading = true
        if let q = try? await api.getQuote(code: item.code) { quote = q }
        warnings = (try? await api.getWarnings(code: item.code)) ?? []
        priceLimits = try? await api.getPriceLimits(code: item.code)
        flows = (try? await api.getInvestorFlow(code: item.code, days: 5)) ?? []
        if let daily = try? await api.getDaily(code: item.code, bars: 120) {
            dailyBars = daily
            technicalResult = TechnicalIndicators.shared.calculate(bars: daily)
        }
        targetPriceInfo = try? await api.getTargetPrice(code: item.code)
        loading = false

        // 실적·공매도·밸류에이션은 느린 네트워크 이후 병렬 로드 (접기 기본이라 늦어도 무방)
        // U2: '지표 영향' 카드 제거로 getStockSignals 호출 제외(브리핑 '내 종목 영향'이 정본).
        async let earnsTask         = api.getEarnings(codes: [item.code])
        async let shortSellingTask  = api.getShortSelling(code: item.code)
        async let valuationBandTask = api.getValuationBand(code: item.code)
        async let peerValuationTask = api.getPeerValuation(code: item.code)
        async let backtestTask          = api.getBacktest(code: item.code)
        async let flowSensitivityTask   = api.getFlowSensitivity(code: item.code)
        async let analogTask            = api.getAnalog(code: item.code)
        async let premortemTask         = api.getPremortem(code: item.code)
        async let dividendTask          = api.getDividend(code: item.code)
        earningsEntry   = (try? await earnsTask)?.first
        shortSelling    = try? await shortSellingTask
        valuationBand   = try? await valuationBandTask
        peerValuation   = try? await peerValuationTask
        backtest        = try? await backtestTask
        flowSensitivity = try? await flowSensitivityTask
        analog          = try? await analogTask
        premortem       = try? await premortemTask
        dividendCard    = try? await dividendTask
    }

    private func loadAnalysis(force: Bool = false) async {
        analyzing = true
        if force {
            analysis = nil
            commentExpanded = false
        }
        // C16 논지 변천 — 로컬 이력(정본)에서 최근 5개. 2건 미만이면 EdgeApi가 전송 생략.
        let history = Db.watchlist.thesisHistory(code: item.code, limit: 5)
        if let avgNum = item.avgPrice, let qtyNum = item.qty {
            analysis = try? await api.getAnalysisPersonalized(
                code: item.code,
                avgPrice: avgNum.doubleValue,
                qty: qtyNum.int64Value,
                targetPrice: item.targetPrice?.doubleValue ?? 0.0,
                stopPrice: item.stopPrice?.doubleValue ?? 0.0,
                mode: analysisMode.rawValue,
                refresh: force,
                thesis: item.thesis,
                thesisHistory: history,
                horizon: contextHorizon
            )
        } else {
            analysis = try? await api.getAnalysis(
                code: item.code,
                mode: analysisMode.rawValue,
                refresh: force,
                thesis: item.thesis,
                thesisHistory: history
            )
        }
        analyzing = false
    }

    // S14: 이전 세션에서 저장한 복기 파라미터로 재POST(당일 서버 캐시 적중 = 무료).
    private func loadStoredTradeReview() async {
        guard tradeReview == nil else { return }
        guard let data = UserDefaults.standard.data(forKey: "trReviewParams_\(item.code)"),
              let p = try? JSONDecoder().decode(TradeReviewParams.self, from: data) else { return }
        let review = try? await api.postTradeReview(
            code: item.code,
            buyDate: p.buyDate, buyPrice: p.buyPrice,
            sellDate: p.sellDate, sellPrice: p.sellPrice,
            qty: nil, buyReason: p.buyReason, sellReason: p.sellReason, thesis: p.thesis
        )
        if let r = review { tradeReview = r }
    }

    private func loadCatalysts(force: Bool = false) async {
        catalystsLoading = true
        if force { catalysts = nil }
        catalysts = try? await api.getCatalysts(code: item.code, days: 7, refresh: force)
        catalystsLoading = false
        catalystAttempted = true
        // F2 임팩트 통계 — 로딩 상태 별도 없음(데이터 있으면 표시, 없으면 숨김)
        catalystImpact = try? await api.getCatalystImpact(code: item.code)
    }

    private func loadDeepResearch() async {
        guard !deepResearchLoading else { return }
        deepResearchLoading = true
        deepResearchError = false
        deepResearch = try? await api.getDeepResearch(code: item.code)
        deepResearchError = (deepResearch == nil)
        deepResearchLoading = false
        if deepResearch != nil {
            withAnimation(.easeInOut(duration: 0.2)) { deepResearchExpanded = true }
        }
    }

    // ── 딥리서치 섹션(C2) ────────────────────────────────────────
    @ViewBuilder
    private func deepResearchSection() -> some View {
        if let dr = deepResearch {
            deepResearchCard(dr)
        } else if deepResearchLoading {
            HStack(spacing: 8) {
                ProgressView()
                Text("딥리서치 생성 중… (수십 초 소요)").font(.caption).foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .cardStyle()
        } else if deepResearchError {
            // 검색 과금 기능이라 일일 한도(기본 5건)가 있다 — 실패의 흔한 원인이라 문구에 포함.
            Text("딥리서치를 만들지 못했어요 — 하루 한도(5건)를 다 썼거나 일시 오류예요. 잠시 후 다시 시도해 주세요.")
                .font(.caption).foregroundColor(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
                .cardStyle()
        }
    }

    private func deepResearchCard(_ dr: DeepResearch) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "doc.text.magnifyingglass").font(.caption).foregroundColor(.teal)
                Text("딥리서치").font(.subheadline.weight(.semibold))
                Spacer()
                Text(dr.date).font(.caption2).foregroundColor(.secondary)
                Image(systemName: deepResearchExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { deepResearchExpanded.toggle() }; if deepResearchExpanded { Usage.shared.expand("detail", "딥리서치") } }

            if deepResearchExpanded {
                Divider()
                VStack(alignment: .leading, spacing: 12) {
                    // 핵심 요약
                    if let summary = dr.summary {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 4) {
                                Image(systemName: "sparkles").font(.caption2)
                                Text("핵심 요약").font(.caption.weight(.bold))
                            }
                            .foregroundColor(.teal)
                            Text(markdown(summary))
                                .font(.callout)
                                .lineSpacing(4)
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.teal.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }

                    // 본문 — 소제목 단락 구조
                    let sections = parseCommentSections(dr.comment)
                    HStack(alignment: .top, spacing: 10) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.teal.opacity(0.35))
                            .frame(width: 3)
                        VStack(alignment: .leading, spacing: 14) {
                            ForEach(sections) { sec in
                                VStack(alignment: .leading, spacing: 5) {
                                    if let h = sec.heading {
                                        Text(h)
                                            .font(.subheadline.weight(.bold))
                                            .foregroundColor(.teal)
                                    }
                                    ForEach(Array(sec.body.enumerated()), id: \.offset) { _, p in
                                        Text(markdown(p))
                                            .font(.callout)
                                            .lineSpacing(4)
                                            .fixedSize(horizontal: false, vertical: true)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }
                        }
                    }

                    // 출처 목록
                    if !dr.sources.isEmpty {
                        Divider()
                        VStack(alignment: .leading, spacing: 4) {
                            Text("출처").font(.caption.weight(.semibold)).foregroundColor(.secondary)
                            ForEach(Array(dr.sources.enumerated()), id: \.offset) { _, src in
                                if let url = URL(string: src.url) {
                                    Link(destination: url) {
                                        HStack(alignment: .top, spacing: 4) {
                                            Image(systemName: "link").font(.caption2).padding(.top, 2)
                                            Text(src.title).font(.caption).lineLimit(2)
                                                .multilineTextAlignment(.leading)
                                                .frame(maxWidth: .infinity, alignment: .leading)
                                        }
                                        .foregroundColor(.teal)
                                    }
                                } else {
                                    Text("• \(src.title)").font(.caption).foregroundColor(.secondary)
                                }
                            }
                        }
                    }

                    Text("생성: \(dr.generatedAt) KST").font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 10).padding(.bottom, 4)
            }
        }
        .cardStyle()
    }
}

// **굵게** 마크다운 → AttributedString(볼드). StockDetailView·StockAskSheetView 공유.
func boldMarkdown(_ s: String) -> AttributedString {
    var text = s.replacingOccurrences(of: #"~~(.+?)~~"#, with: "$1", options: .regularExpression)
    text = text.replacingOccurrences(of: "~~", with: "")

    guard let regex = try? NSRegularExpression(pattern: #"\*\*(.+?)\*\*"#) else {
        return AttributedString(text.replacingOccurrences(of: "**", with: ""))
    }
    let ns = text as NSString
    var out = AttributedString()
    var cursor = 0
    for m in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
        if m.range.location > cursor {
            let plain = ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor))
            out += AttributedString(plain.replacingOccurrences(of: "**", with: ""))
        }
        var bold = AttributedString(ns.substring(with: m.range(at: 1)))
        bold.inlinePresentationIntent = .stronglyEmphasized
        out += bold
        cursor = m.range.location + m.range.length
    }
    if cursor < ns.length {
        out += AttributedString(ns.substring(from: cursor).replacingOccurrences(of: "**", with: ""))
    }
    return out
}

// 카드 공통 스타일(상세·포지션 카드 공유).
private extension View {
    func cardStyle() -> some View {
        self
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(Color(.secondarySystemBackground))
            .cornerRadius(12)
    }
}

// 종목 Q&A 시트 — 사실 데이터를 근거로 자유 질문에 답한다.
// history는 시트 내 세션 단위로 유지(닫으면 리셋). 서버는 무상태라 앱이 이전 턴을 매번 전송.
struct StockAskSheetView: View {
    let item: WatchItem
    let api: EdgeApi
    let mode: AnalysisMode
    // 상세 화면의 계좌 컨텍스트 성격 — "long"이면 답변도 장기 관점(Q13). nil = 기존 동작.
    var horizon: String? = nil
    @Environment(\.dismiss) private var dismiss

    @State private var turns: [AskTurn] = []
    @State private var inputText = ""
    @State private var sending = false
    @State private var errorMsg: String? = nil

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 16) {
                            if turns.isEmpty && !sending {
                                VStack(spacing: 8) {
                                    Image(systemName: "questionmark.bubble")
                                        .font(.system(size: 32))
                                        .foregroundColor(.purple.opacity(0.5))
                                    Text("\(item.name)에 대해 무엇이든 물어보세요")
                                        .font(.callout.weight(.semibold))
                                    Text("뉴스·수급·PER·밸류 등 현재 데이터 기반으로 답변해요. 다른 종목과의 비교 질문도 가능해요")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                .frame(maxWidth: .infinity)
                                .multilineTextAlignment(.center)
                                .padding(.top, 48)
                                .padding(.horizontal, 24)
                            }
                            ForEach(Array(turns.enumerated()), id: \.offset) { idx, turn in
                                VStack(alignment: .leading, spacing: 8) {
                                    // 질문 (오른쪽 정렬)
                                    HStack {
                                        Spacer()
                                        Text(turn.question)
                                            .font(.callout)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 8)
                                            .background(Color.purple.opacity(0.12))
                                            .cornerRadius(12)
                                    }
                                    // 답변 (왼쪽 정렬)
                                    HStack(alignment: .top, spacing: 8) {
                                        Image(systemName: "sparkles")
                                            .font(.caption)
                                            .foregroundColor(.purple)
                                            .padding(.top, 2)
                                        Text(boldMarkdown(turn.answer))
                                            .font(.callout)
                                            .lineSpacing(5)
                                            .fixedSize(horizontal: false, vertical: true)
                                        Spacer()
                                    }
                                }
                                .id(idx)
                            }
                            if sending {
                                HStack(spacing: 8) {
                                    ProgressView().scaleEffect(0.8)
                                    Text("답변 생성 중…").font(.caption).foregroundColor(.secondary)
                                    Spacer()
                                }
                                .id("loading")
                            }
                            if let err = errorMsg {
                                Text(err).font(.caption).foregroundColor(.red).padding(.top, 2)
                            }
                            Color.clear.frame(height: 1).id("bottom")
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    .onChange(of: turns.count) { withAnimation { proxy.scrollTo("bottom") } }
                    .onChange(of: sending) { withAnimation { proxy.scrollTo("bottom") } }
                }

                Divider()
                HStack(spacing: 8) {
                    TextField("질문 입력 (최대 300자)", text: $inputText, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(1...4)
                        .disabled(sending)
                    Button {
                        Task { await sendQuestion() }
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 30))
                            .foregroundColor(canSend ? .purple : Color(.systemFill))
                    }
                    .disabled(!canSend)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color(.systemBackground))
            }
            .navigationTitle("\(item.name) Q&A")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("닫기") { dismiss() }
                }
                if mode == .aggressive {
                    ToolbarItem(placement: .topBarTrailing) {
                        Text("⚔️ 공격적")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.orange.opacity(0.15))
                            .foregroundColor(.orange)
                            .clipShape(Capsule())
                    }
                }
            }
        }
    }

    private var canSend: Bool {
        !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !sending
    }

    private func sendQuestion() async {
        let q = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        errorMsg = nil
        sending = true
        inputText = ""
        do {
            let ans = try await api.ask(
                code: item.code,
                question: q,
                avgPrice: item.avgPrice,
                qty: item.qty,
                targetPrice: item.targetPrice,
                stopPrice: item.stopPrice,
                mode: mode.rawValue,
                history: turns,
                thesis: item.thesis,
                horizon: horizon
            )
            turns.append(AskTurn(question: q, answer: ans.answer))
        } catch {
            errorMsg = "답변을 불러오지 못했어요. 다시 시도해 주세요."
            inputText = q
        }
        sending = false
    }
}

// 행동 기록 입력 시트. action(관심/매수/매도) 선택 + 사유(선택) 입력 후 저장.
struct ActionLogSheetView: View {
    let code: String
    let name: String?
    let logRepo: ActionLogRepository
    let currentPrice: Int64        // 기록 시점 현재가. 0 = 미기록.
    var api: EdgeApi? = nil        // 프리모템·복기 생성용(nil이면 토글 숨김)
    var item: WatchItem? = nil     // 평단·손절가·논지 전달용
    var onSellWithReview: ((TradeReview?) -> Void)? = nil  // B2: 매도 후 복기 결과 전달
    @Environment(\.dismiss) private var dismiss

    @State private var selectedAction = "interest"
    @State private var reason = ""
    @State private var makePremortem = true    // 매수 시 무효화 조건 생성(F5)
    @State private var makeTradeReview = true  // 매도 시 매매 복기 생성(B2)

    private let actions: [(id: String, label: String)] = [
        ("interest", "관심"),
        ("buy",      "매수"),
        ("sell",     "매도"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("행동") {
                    Picker("", selection: $selectedAction) {
                        ForEach(actions, id: \.id) { a in
                            Text(a.label).tag(a.id)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
                    .listRowBackground(Color.clear)
                }
                Section("사유 (선택)") {
                    TextField("왜 관심/매수/매도 하려는지 한 줄로", text: $reason)
                }
                if selectedAction == "buy" && api != nil {
                    Section {
                        Toggle("무효화 조건 만들기", isOn: $makePremortem)
                        if makePremortem {
                            Text("AI가 이 매수 논리가 깨지는 조건(지지 이탈·수급 이탈 등)을 만들어 두고, 발동하면 알려줘요. 사유가 구체적일수록 조건이 정확해져요.")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                if selectedAction == "sell" && api != nil {
                    Section {
                        Toggle("매매 복기 만들기", isOn: $makeTradeReview)
                        if makeTradeReview {
                            Text("AI가 이 매매를 과정/결과 2축으로 복기해줘요. 매수 기록에 사유가 있을수록 정확해져요.")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("행동 기록")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("저장") {
                        // T1: 매매 당시 손절/목표를 스냅샷 저장 → 규율 채점이 현재 holding(사후 변경·청산 시
                        // 소멸)이 아니라 이 시점 계획을 참조하게 한다. 미설정(0)은 리포지토리에서 null 처리.
                        logRepo.insert(
                            code: code,
                            name: name,
                            action: selectedAction,
                            reason: reason.isEmpty ? nil : reason,
                            price: currentPrice,
                            stopPrice: Int64(item?.stopPrice?.doubleValue ?? 0),
                            targetPrice: Int64(item?.targetPrice?.doubleValue ?? 0)
                        )
                        // F5: 매수 + 토글 on → 프리모템 생성(백그라운드, 실패해도 기록엔 영향 없음)
                        if selectedAction == "buy", makePremortem, let api {
                            let r = reason
                            let avg = item?.avgPrice
                            let qty = item?.qty
                            let stop = item?.stopPrice
                            Task.detached {
                                _ = try? await api.createPremortem(
                                    code: code, reason: r,
                                    avgPrice: avg, qty: qty, stopPrice: stop)
                            }
                        }
                        // B2: 매도 + 토글 on → 분할 매수 평균가 계산해 복기 생성(S15)
                        if selectedAction == "sell", makeTradeReview, let api, let onReview = onSellWithReview {
                            let allLogs = logRepo.getByCode(code: code, limit: 50)
                            // S15: 현 포지션 매수 = 가장 최근 sell 이후의 buy 전부
                            let lastSell = allLogs.first(where: { $0.action == "sell" })
                            let buyLogs = allLogs.filter { log in
                                log.action == "buy" && (log.price?.int64Value ?? 0) > 0
                                    && (lastSell == nil || log.createdAt > lastSell!.createdAt)
                            }
                            if !buyLogs.isEmpty, currentPrice > 0 {
                                let prices = buyLogs.compactMap { $0.price?.int64Value }.filter { $0 > 0 }
                                let avgBuyPrice = Double(prices.reduce(0, +)) / Double(prices.count)
                                let buyDate = epochToISO(buyLogs.last!.createdAt)  // 가장 오래된 매수
                                let sellDate = todayISO()
                                let sellPrice = Double(currentPrice)
                                let buyReason = buyLogs.last?.reason  // 가장 오래된 매수 사유
                                let sellReason = reason.isEmpty ? nil : reason
                                let thesis = item?.thesis
                                // S14: 파라미터 저장(화면 이탈 후 재진입 시 복원용)
                                let params = TradeReviewParams(
                                    buyDate: buyDate, buyPrice: avgBuyPrice,
                                    sellDate: sellDate, sellPrice: sellPrice,
                                    buyReason: buyReason, sellReason: sellReason, thesis: thesis
                                )
                                if let data = try? JSONEncoder().encode(params) {
                                    UserDefaults.standard.set(data, forKey: "trReviewParams_\(code)")
                                }
                                Task.detached {
                                    let review = try? await api.postTradeReview(
                                        code: code,
                                        buyDate: buyDate, buyPrice: avgBuyPrice,
                                        sellDate: sellDate, sellPrice: sellPrice,
                                        qty: nil,
                                        buyReason: buyReason,
                                        sellReason: sellReason,
                                        thesis: thesis
                                    )
                                    await MainActor.run { onReview(review) }
                                }
                            }
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }

    // epoch millis → "yyyy-MM-dd" (KST)
    private func epochToISO(_ millis: Int64) -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.timeZone = TimeZone(identifier: "Asia/Seoul")
        return fmt.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    private func todayISO() -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyy-MM-dd"
        fmt.timeZone = TimeZone(identifier: "Asia/Seoul")
        return fmt.string(from: Date())
    }
}

// MARK: - 매매 복기 파라미터 (S14: UserDefaults 저장/복원)

private struct TradeReviewParams: Codable {
    let buyDate: String
    let buyPrice: Double
    let sellDate: String
    let sellPrice: Double
    let buyReason: String?
    let sellReason: String?
    let thesis: String?
}

// MARK: - 차트 헬퍼 타입

private struct FlowEntry: Identifiable {
    let id: String
    let date: String
    let investor: String
    let shares: Double
}

// 종가 + 이평선 미니 라인 차트. bars[0] = 최신, reversed 후 최근 30개 표시.
// MA5·MA20은 시계열 라인, MA60은 현재값 기준선(RuleMark).
// 차트 기간 토글. barCount = 표시할 일봉 개수(영업일 기준 근사).
enum ChartPeriod: CaseIterable {
    case today, week, m1, m3, all
    var label: String {
        switch self {
        case .today: "오늘"
        case .week:  "1주"
        case .m1:    "1개월"
        case .m3:    "3개월"
        case .all:   "전체"
        }
    }
    var barCount: Int {
        switch self {
        case .week:  5
        case .m1:    22
        case .m3:    66
        case .all:   999
        case .today: 0   // PriceLineChart 미사용, todaySummaryView 표시
        }
    }
}

private func priceYLabel(_ v: Double) -> String {
    let n = Int(v)
    if n >= 10_000 { return "\(n / 10_000)만" }
    return n.formatted()
}

// "내 기준선 차트": 종가 라인 + 일별 고저 밴드 + 20일 추세선 위에
// 내 평단·목표·손절을 가로 기준선으로 얹는다.
private struct PriceLineChart: View {
    let bars: [DailyBar]
    let displayCount: Int
    let avg: Double?
    let target: Double?
    let stop: Double?

    private struct Pt: Identifiable {
        let id: Int
        let close: Double
        let high: Double
        let low: Double
        let ma20: Double?
    }

    // 최신일이 앞이라 reverse 후, displayCount 만큼 뒤(최근)에서 자른다.
    // MA20은 잘리기 전 전체 시계열로 계산해 표시 구간 첫날부터 값이 있게 한다.
    private var pts: [Pt] {
        let all = Array(bars.reversed())   // [0]=oldest
        guard !all.isEmpty else { return [] }
        let start = max(0, all.count - displayCount)
        return (start..<all.count).map { i in
            let ma: Double? = i >= 19
                ? all[(i - 19)...i].reduce(0.0) { $0 + Double($1.close) } / 20
                : nil
            let b = all[i]
            return Pt(id: i - start, close: Double(b.close),
                      high: Double(b.high), low: Double(b.low), ma20: ma)
        }
    }

    // y축 범위: 표시 구간 고저 + 기준선 + MA20까지 포함해 모두 차트 안에 들어오게.
    private var yDomain: ClosedRange<Double> {
        let ma20s = pts.compactMap(\.ma20)
        let lows  = pts.map(\.low)  + [avg, target, stop].compactMap { $0 } + ma20s
        let highs = pts.map(\.high) + [avg, target, stop].compactMap { $0 } + ma20s
        let lo = (lows.min() ?? 0) * 0.99
        let hi = (highs.max() ?? 1) * 1.01
        return lo...(hi > lo ? hi : lo + 1)
    }

    var body: some View {
        let data = pts
        Chart {
            // 일별 고저 밴드 — 변동 폭을 옅은 영역으로.
            ForEach(data) { p in
                AreaMark(x: .value("일", p.id),
                         yStart: .value("저", p.low), yEnd: .value("고", p.high))
                    .foregroundStyle(Color.primary.opacity(0.10))
                    .interpolationMethod(.monotone)
            }
            // 20일 추세선(주황 점선) — 과거 데이터가 충분하면 모든 기간에 표시.
            ForEach(data) { p in
                if let ma = p.ma20 {
                    LineMark(x: .value("일", p.id), y: .value("가격", ma),
                             series: .value("계열", "추세선"))
                        .foregroundStyle(Color.orange)
                        .lineStyle(StrokeStyle(lineWidth: 1.2, dash: [4, 3]))
                        .interpolationMethod(.monotone)
                }
            }
            // 종가 라인(굵게)
            ForEach(data) { p in
                LineMark(x: .value("일", p.id), y: .value("가격", p.close),
                         series: .value("계열", "종가"))
                    .foregroundStyle(Color.primary)
                    .lineStyle(StrokeStyle(lineWidth: 2.2))
                    .interpolationMethod(.monotone)
            }
            // 마지막 종가점 강조 + "현재" 라벨
            if let last = data.last {
                PointMark(x: .value("일", last.id), y: .value("가격", last.close))
                    .foregroundStyle(Color.primary)
                    .symbolSize(40)
            }
            // 내 기준선들
            baseline(target, .red)
            baseline(avg,    .green)
            baseline(stop,   .blue)
        }
        .chartYScale(domain: yDomain)
        .chartXAxis(.hidden)
        .chartYAxis {
            // 우측: 일반 가격 눈금
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { v in
                if let d = v.as(Double.self), d > 0 {
                    AxisValueLabel {
                        Text(priceYLabel(d)).font(.system(size: 9)).foregroundStyle(Color.secondary)
                    }
                }
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.3, dash: [3, 3]))
            }
            // 좌측: 기준선(목표/평단/손절) 컬러 레이블 — 데이터와 겹치지 않음
            AxisMarks(position: .leading, values: baselineAxisValues) { v in
                if let d = v.as(Double.self) {
                    AxisValueLabel { baselineLabel(d) }
                }
            }
        }
        .chartLegend(.hidden)
    }

    private var baselineAxisValues: [Double] {
        [target, avg, stop].compactMap { $0 }
    }

    // 기준선 레이블 뷰 (target/avg/stop 순서로 매칭)
    private func baselineLabel(_ d: Double) -> some View {
        Group {
            if let t = target, t == d {
                bRow("목표", d, "arrowtriangle.up.fill", .red)
            } else if let a = avg, a == d {
                bRow("평단", d, "circle.fill", .green)
            } else {
                bRow("손절", d, "arrowtriangle.down.fill", .blue)
            }
        }
    }

    private func bRow(_ label: String, _ v: Double, _ symbol: String, _ color: Color) -> some View {
        HStack(spacing: 2) {
            Image(systemName: symbol).font(.system(size: 6))
            Text("\(label) \(priceYLabel(v))").font(.system(size: 8, weight: .medium))
        }
        .foregroundColor(color)
    }

    // 기준선 1개(값 있을 때만). 라인만 — 레이블은 좌측 y축에 표시.
    @ChartContentBuilder
    private func baseline(_ value: Double?, _ color: Color) -> some ChartContent {
        if let v = value {
            RuleMark(y: .value("가격", v))
                .foregroundStyle(color.opacity(0.7))
                .lineStyle(StrokeStyle(lineWidth: 1.0, dash: [3, 2]))
        }
    }
}

// 거래량 막대. 가격 차트와 같은 기간·개수로 잘라 x축 정렬. 급증일(20일 평균 2배↑) 빨강.
private struct VolumeBars: View {
    let bars: [DailyBar]
    let displayCount: Int

    private struct VPt: Identifiable { let id: Int; let vol: Double; let hot: Bool }

    private var pts: [VPt] {
        let all = Array(bars.reversed())
        guard !all.isEmpty else { return [] }
        let start = max(0, all.count - displayCount)
        let shown = Array(all[start...])
        let avg = shown.isEmpty ? 0 : shown.reduce(0.0) { $0 + Double($1.volume) } / Double(shown.count)
        return shown.enumerated().map { i, b in
            let v = Double(b.volume)
            return VPt(id: i, vol: v, hot: avg > 0 && v >= avg * 2)
        }
    }

    var body: some View {
        Chart(pts) { p in
            BarMark(x: .value("일", p.id), y: .value("거래량", p.vol))
                .foregroundStyle(p.hot ? Color.red.opacity(0.6) : Color.secondary.opacity(0.35))
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartLegend(.hidden)
    }
}

// 칩·뱃지를 자연스럽게 줄바꿈하는 흐름 레이아웃.
// 각 자식 뷰는 .fixedSize()로 크기를 고정해야 제대로 동작한다.
@available(iOS 16.0, *)
private struct ChipFlowLayout: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = makeRows(maxWidth: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.reduce(0.0) { $0 + $1.height + spacing } - spacing
        return CGSize(width: proposal.width ?? 0, height: max(0, height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = makeRows(maxWidth: bounds.width, subviews: subviews)
        var y = bounds.minY
        for row in rows {
            var x = bounds.minX
            for item in row.items {
                item.view.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: .unspecified)
                x += item.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct RowItem { let view: LayoutSubview; let width: CGFloat; let height: CGFloat }
    private struct Row { let items: [RowItem]; let height: CGFloat }

    private func makeRows(maxWidth: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var rowItems: [RowItem] = []
        var rowWidth: CGFloat = 0

        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            let item = RowItem(view: view, width: size.width, height: size.height)
            if rowWidth + size.width > maxWidth, !rowItems.isEmpty {
                rows.append(Row(items: rowItems, height: rowItems.map(\.height).max() ?? 0))
                rowItems = []
                rowWidth = 0
            }
            rowItems.append(item)
            rowWidth += size.width + spacing
        }
        if !rowItems.isEmpty {
            rows.append(Row(items: rowItems, height: rowItems.map(\.height).max() ?? 0))
        }
        return rows
    }
}
