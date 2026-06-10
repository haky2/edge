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
    @State private var flows: [InvestorFlow] = []   // 일별 수급(외인/기관/개인)
    @State private var news: [NewsItem] = []         // 관련 뉴스
    @State private var analysis: Analysis?           // AI 종합 코멘트
    @State private var technicalResult: TechnicalResult?  // 이평·RSI·거래량 추세(2단계)
    @State private var targetPriceInfo: TargetPriceInfo?   // 컨센서스 목표주가
    @State private var dailyBars: [DailyBar] = []           // 일봉 (차트용)
    @State private var logEntries: [ActionLogEntry] = []  // 이 종목 행동 로그
    @State private var dartDisclosures: [DartDisclosure] = []
    @State private var earningsEntry: EarningsEntry?
    @State private var stockSignal: StockImpact?
    @State private var shortSelling: ShortSellingSummary?
    @State private var shortSellingHelpExpanded = false
    @State private var valuationBand: ValuationBand?
    @State private var backtest: Backtest?          // 신호별 익일 적중률(검증된 신호)
    @State private var flowSensitivity: FlowSensitivity?  // 수급-가격 민감도
    @State private var dartExpanded = false
    @State private var earningsExpanded = false
    @State private var signalExpanded = false
    @State private var indicatorHelpExpanded = false   // 기술적 지표 설명 접기
    @State private var valuationHelpExpanded = false    // PER/PBR 설명 접기
    @State private var chartPeriod: ChartPeriod = .m3   // 가격 차트 기간 토글
    @State private var trendLineHelpExpanded = false     // 20일 추세선 설명 토글
    @State private var commentExpanded = false   // AI 코멘트 더보기/접기
    @AppStorage(analysisModeKey) private var modeRaw = AnalysisMode.defensive.rawValue
    private var analysisMode: AnalysisMode { AnalysisMode(rawValue: modeRaw) ?? .defensive }
    @State private var analyzing = false
    @State private var loading = false
    @State private var showEdit = false
    @State private var showLogSheet = false

    init(item: WatchItem, quote: Quote?, api: EdgeApi, logRepo: ActionLogRepository = Db.actionLog) {
        _item = State(initialValue: item)
        self.api = api
        self.logRepo = logRepo
        _quote = State(initialValue: quote) // 리스트가 받아둔 시세로 초기화(바로 보이게)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text(item.code).font(.caption).foregroundColor(.secondary)

                if let q = quote {
                    priceHeader(q)      // 현재가 + 등락
                    priceChartCard(q)   // 가격 차트 + 기본 지표
                    positionCard(q)  // 내 포지션 + 수익률
                    aiCommentCard()  // AI 종합 코멘트 (포지션 다음 → 맥락 연결)
                    analysisCard(q)  // 지표 해석 ① 계산 기반(52주 위치·수급 흐름 요약)
                    if let band = valuationBand { valuationBandCard(band) }  // 밸류에이션 히스토리 밴드
                    if let tr = technicalResult { technicalCard(tr, price: Double(q.price)) }  // 이평·RSI·거래량
                } else if loading {
                    ProgressView().padding(.top, 40)
                }
                if !flows.isEmpty {
                    flowCard()       // 수급: 외인/기관/개인 일별 순매수
                }
                if let bt = backtest { backtestCard(bt) }  // 검증된 신호(익일 적중률)
                if let fs = flowSensitivity { flowSensitivityCard(fs) }  // 수급-가격 민감도
                if let ss = shortSelling {
                    shortSellingCard(ss)
                }
                if !news.isEmpty {
                    newsCard()
                }
                dartDisclosureSection()
                earningsDueDateSection()
                macroSignalSection()
                if !logEntries.isEmpty {
                    logCard()
                }
            }
            .padding()
        }
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showLogSheet = true } label: { Image(systemName: "pencil.and.list.clipboard") }
                    .help("매매 기록")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showEdit = true } label: { Image(systemName: "square.and.pencil") }
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
        .onChange(of: modeRaw) {
            analysis = nil   // 이전 모드 코멘트 즉시 제거 → 로딩 상태 바로 표시
            Task { await loadAnalysis() }
        }
        .onAppear { loadLogs() }
        .sheet(isPresented: $showEdit) {
            PositionEditView(item: item) { updated in item = updated }  // 저장 시 화면 즉시 반영
        }
        .sheet(isPresented: $showLogSheet, onDismiss: loadLogs) {
            ActionLogSheetView(code: item.code, logRepo: logRepo, currentPrice: quote?.price ?? 0)
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

        return VStack(alignment: .leading, spacing: 12) {
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
            // 거래량 + 해석
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .lastTextBaseline, spacing: 4) {
                    Text("오늘 거래량").font(.caption).foregroundColor(.secondary)
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

    // 지표 해석 ① 계산 기반(LLM 없음). 이미 받은 시세·수급으로 즉시 계산한 "위치/흐름" 요약.
    // 사실만 보여주고 매수/매도 판단은 하지 않는다(그건 추후 Claude 층).
    @ViewBuilder
    private func analysisCard(_ q: Quote) -> some View {
        let ctx = StockAnalysis.shared.priceContext(q: q)
        let streaks = StockAnalysis.shared.flowStreaks(flows: flows)
        VStack(alignment: .leading, spacing: 8) {
            Text("지표 해석").font(.subheadline.weight(.semibold)).padding(.top, 8)

            if let c = ctx {
                rangeGauge(c.pctInRange52w)
                insight("52주 고점 대비", String(format: "%.1f%%", c.pctFromHigh52w))
                insight("52주 저점 대비", String(format: "+%.1f%%", c.pctFromLow52w))
            }
            // 밸류에이션(PER/PBR) — 업종명 + 값 + 의미 설명(① 계층: 사실만, 판단 X).
            let hasValuation = q.per > 0 || q.pbr > 0
            if hasValuation {
                if ctx != nil { Divider() }
                // 업종명 — PER/PBR의 상대적 해석 맥락 제공 (섹터 평균 PER은 추후 KRX 데이터로 추가 예정)
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
                        "내 돈을 몇 년 모으면 이 회사를 통째로 살 수 있나 — 낮을수록 이익 대비 싼 편이에요. 성장 기대가 크면 높게 매겨져요.",
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
                        Text("\(tp.price.formatted())원")
                            .fontWeight(.medium)
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
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 6)
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
                Spacer()
                if analyzing { ProgressView().scaleEffect(0.8) }
            }
            .padding(.top, 8)

            if let a = analysis {
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
                            "오늘 거래량을 최근 20일 평균과 비교한 거예요. 2배 넘으면 평소보다 사람이 확 몰린 것 — 큰 뉴스나 수급 변화 신호일 수 있어요.")
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
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
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

    // 밸류에이션 히스토리 밴드 카드 — PER/PBR 현재값을 과거 N년 밴드 위에 표시
    private func valuationBandCard(_ band: ValuationBand) -> some View {
        let showPer = band.perCurrent > 0 && band.perMax > band.perMin
        let showPbr = band.pbrCurrent > 0 && band.pbrMax > band.pbrMin
        guard showPer || showPbr else { return AnyView(EmptyView()) }
        return AnyView(
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("밸류에이션 히스토리").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("\(band.yearsUsed)년 밴드").font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 4)

                if showPer {
                    valuationBandRow(
                        name: "PER",
                        current: band.perCurrent,
                        bandMin: band.perMin, bandMax: band.perMax, median: band.perMedian,
                        percentile: Int(band.perPercentile),
                        label: band.perLabel
                    )
                }
                if showPbr {
                    if showPer { Divider() }
                    valuationBandRow(
                        name: "PBR",
                        current: band.pbrCurrent,
                        bandMin: band.pbrMin, bandMax: band.pbrMax, median: band.pbrMedian,
                        percentile: Int(band.pbrPercentile),
                        label: band.pbrLabel
                    )
                }

                Text("연도말 종가 기준, 상장주식수 근사치 — 분할·증자 시 오차 가능")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding()
            .cardStyle()
        )
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
        case "역사적 저평가":   return .blue
        case "역사적 고평가":   return .red
        default:               return .orange
        }
    }

    // 검증된 신호 카드 — 외인·기관 순매수·거래량 급증일의 익일 적중률(이 종목 실측)
    private func backtestCard(_ bt: Backtest) -> some View {
        let shown = bt.signals.filter { $0.n > 0 }
        guard !shown.isEmpty else { return AnyView(EmptyView()) }
        return AnyView(
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("검증된 신호").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("최근 \(bt.tradingDays)거래일 실측").font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 4)

                Text("평소 익일 상승확률 \(Int(bt.baselineWinRate))% · 평균 \(String(format: "%+.2f", bt.baselineAvgReturn))% (세로선=평소 기준)")
                    .font(.caption2).foregroundColor(.secondary)

                ForEach(Array(shown.enumerated()), id: \.offset) { idx, s in
                    if idx > 0 { Divider() }
                    backtestRow(s, baseline: Int(bt.baselineWinRate))
                }

                Text("이 종목 과거 통계일 뿐 미래를 보장하지 않아요. 표본(n)이 작으면 참고만 하세요.")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding()
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

    // 수급-가격 민감도 카드 — 외인/기관 순매수량 vs 당일 등락률 Pearson 상관
    private func flowSensitivityCard(_ fs: FlowSensitivity) -> some View {
        let shown = fs.items.filter { $0.n > 0 }
        guard !shown.isEmpty else { return AnyView(EmptyView()) }
        let days = Int(shown.first?.n ?? 0)
        return AnyView(
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("수급-가격 민감도").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text("최근 \(days)거래일 기준").font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 4)

                Text("외인·기관이 많이 살수록 이 종목 주가가 그날 같이 올랐나요?")
                    .font(.caption2).foregroundColor(.secondary)

                ForEach(Array(shown.enumerated()), id: \.offset) { idx, fc in
                    if idx > 0 { Divider() }
                    flowCorrRow(fc)
                }

                Text("수급은 전일까지 장후 확정값 기준이에요. 과거 통계라 미래를 보장하지 않아요.")
                    .font(.caption2).foregroundColor(.secondary)
            }
            .padding()
            .cardStyle()
        )
    }

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
        VStack(alignment: .leading, spacing: 10) {
            // 헤더
            HStack {
                Text("공매도 동향").font(.subheadline.weight(.semibold))
                Spacer()
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        shortSellingHelpExpanded.toggle()
                    }
                } label: {
                    Label(shortSellingHelpExpanded ? "접기" : "공매도란?",
                          systemImage: "info.circle")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }

            // 설명 토글 (접기/펼치기)
            if shortSellingHelpExpanded {
                VStack(alignment: .leading, spacing: 6) {
                    Text("공매도는 **주식을 빌려서 파는** 것이에요.")
                        .font(.caption)
                    Text("지금 비싸게 팔고 → 나중에 싸게 사서 갚아 차익을 얻는 방식이라, 하락에 베팅하는 세력이 많을수록 **공매도 잔고**가 늘어나요.")
                        .font(.caption)
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
            }

            // 수치 요약
            HStack(spacing: 16) {
                // 최근 거래량
                VStack(alignment: .leading, spacing: 2) {
                    Text("공매도 거래량").font(.caption).foregroundColor(.secondary)
                    Text("\(formatShortVol(ss.recentVolume))주")
                        .font(.footnote.weight(.semibold))
                    Text(ss.recentVolumeDate).font(.caption2).foregroundColor(.secondary)
                }

                Divider().frame(height: 36)

                // 잔고
                VStack(alignment: .leading, spacing: 2) {
                    Text("공매도 잔고").font(.caption).foregroundColor(.secondary)
                    if let bal = ss.balance {
                        Text("\(formatShortVol(bal.int64Value))주")
                            .font(.footnote.weight(.semibold))
                        HStack(spacing: 4) {
                            if let pctBox = ss.balanceChangePct {
                                let pct = pctBox.doubleValue
                                let isUp = pct > 0.5
                                let isDown = pct < -0.5
                                Image(systemName: isUp ? "arrow.up" : isDown ? "arrow.down" : "minus")
                                    .font(.caption2)
                                    .foregroundColor(isUp ? .red : isDown ? .blue : .secondary)
                                Text(pct >= 0 ? "+\(String(format: "%.1f", pct))%" : "\(String(format: "%.1f", pct))%")
                                    .font(.caption2)
                                    .foregroundColor(isUp ? .red : isDown ? .blue : .secondary)
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
            .padding(.top, 2)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
        .shadow(color: .black.opacity(0.06), radius: 4, x: 0, y: 2)
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

    // 뉴스 카드(2b). 종목명으로 네이버 검색한 최신 헤드라인. 탭하면 Safari로 원문 이동.
    private func newsCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("관련 뉴스").font(.subheadline.weight(.semibold)).padding(.top, 8)
            ForEach(news, id: \.url) { article in
                Link(destination: URL(string: article.url) ?? URL(string: "https://news.naver.com")!) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(article.title)
                            .font(.caption)
                            .foregroundColor(.primary)
                            .lineLimit(2)
                        HStack(spacing: 6) {
                            Text(article.source).font(.caption2).foregroundColor(.secondary)
                            Text("·").foregroundColor(.secondary)
                            Text(shortDate(article.publishedAt)).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 3)
                }
                if article.url != news.last?.url { Divider() }
            }
            .padding(.bottom, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
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

    // DART 공시 — 접기 섹션 (30일, 없으면 미표시)
    @ViewBuilder
    private func dartDisclosureSection() -> some View {
        if !dartDisclosures.isEmpty {
            VStack(spacing: 0) {
                DisclosureGroup(isExpanded: $dartExpanded) {
                    ForEach(dartDisclosures, id: \.url) { d in
                        Divider()
                        if let url = URL(string: d.url) {
                            Link(destination: url) {
                                dartDisclosureRow(d)
                            }
                            .foregroundColor(.primary)
                        } else {
                            dartDisclosureRow(d)
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.text").foregroundColor(.orange)
                        Text("공시 (\(dartDisclosures.count)건, 30일)")
                            .font(.subheadline.weight(.semibold))
                    }
                }
                .padding(.vertical, 10)
            }
            .cardStyle()
        }
    }

    private func dartDisclosureRow(_ d: DartDisclosure) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(d.reportName).font(.caption).lineLimit(2)
            Text(formattedDate8(d.date)).font(.caption2).foregroundColor(.secondary)
        }
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // 실적 일정 — 접기 섹션
    @ViewBuilder
    private func earningsDueDateSection() -> some View {
        if let e = earningsEntry {
            let days = Int(e.daysUntil)
            VStack(spacing: 0) {
                DisclosureGroup(isExpanded: $earningsExpanded) {
                    Divider()
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(e.reportName).font(.caption)
                            Text("제출 기한: \(formattedDate8(e.dueDate))")
                                .font(.caption2).foregroundColor(.secondary)
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
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "calendar").foregroundColor(.blue)
                        Text("실적 일정")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        Text(ddayBadge(days))
                            .font(.caption2.weight(.semibold))
                            .foregroundColor(ddayBadgeColor(days))
                    }
                }
                .padding(.vertical, 10)
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
    private func macroSignalSection() -> some View {
        if let sig = stockSignal {
            VStack(spacing: 0) {
                DisclosureGroup(isExpanded: $signalExpanded) {
                    Divider()
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("섹터: \(sig.sectorLabel)").font(.caption).foregroundColor(.secondary)
                            Spacer()
                            netBadgeDetail(sig.net)
                        }
                        .padding(.top, 4)
                        if sig.signals.isEmpty {
                            Text("아직 지원하지 않는 종목이에요").font(.caption2).foregroundColor(.secondary)
                        } else {
                            ForEach(sig.signals, id: \.indicator) { s in
                                let dir = Int(s.direction)
                                HStack(alignment: .top, spacing: 6) {
                                    Text(signalArrow(dir))
                                        .font(.caption2)
                                        .foregroundColor(directionColor(dir))
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text("\(s.indicator) \(signedPct(s.changeRate))%")
                                            .font(.caption2.weight(.medium))
                                        Text(s.note).font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                    }
                    .padding(.bottom, 8)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "chart.line.uptrend.xyaxis").foregroundColor(.teal)
                        Text("지표 영향")
                            .font(.subheadline.weight(.semibold))
                        Spacer()
                        if sig.net != "-" { netBadgeDetail(sig.net) }
                    }
                }
                .padding(.vertical, 10)
            }
            .cardStyle()
        }
    }

    private func netBadgeDetail(_ net: String) -> some View {
        let color: Color = net == "우호적" ? .red : (net == "부담" ? .blue : .secondary)
        return Text(net)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
    }

    private func signalArrow(_ d: Int) -> String {
        d > 0 ? "↑" : (d < 0 ? "↓" : "→")
    }
    private func directionColor(_ d: Int) -> Color {
        d > 0 ? .red : (d < 0 ? .blue : .secondary)
    }
    private func signedPct(_ v: Double) -> String {
        (v >= 0 ? "+" : "") + String(format: "%.2f", v)
    }

    private func formattedDate8(_ d: String) -> String {
        guard d.count == 8 else { return d }
        return "\(d.prefix(4)).\(d.dropFirst(4).prefix(2)).\(d.suffix(2))"
    }

    // 행동 로그 카드. 이 종목의 최근 기록(최대 5건). 있을 때만 표시.
    private func logCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("행동 기록").font(.subheadline.weight(.semibold)).padding(.top, 8)
            ForEach(logEntries, id: \.id) { entry in
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

    private func markdown(_ s: String) -> AttributedString {
        (try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(s)
    }

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
        flows = (try? await api.getInvestorFlow(code: item.code, days: 5)) ?? []
        news = (try? await api.getNews(stockName: item.name, display: 5)) ?? []
        if let daily = try? await api.getDaily(code: item.code, bars: 120) {
            dailyBars = daily
            technicalResult = TechnicalIndicators.shared.calculate(bars: daily)
        }
        targetPriceInfo = try? await api.getTargetPrice(code: item.code)
        loading = false

        // DART·실적·지표영향·공매도·밸류에이션은 느린 네트워크 이후 병렬 로드 (접기 기본이라 늦어도 무방)
        async let dartTask          = api.getDartDisclosures(code: item.code, days: 30)
        async let earnsTask         = api.getEarnings(codes: [item.code])
        async let signalTask        = api.getStockSignals(code: item.code)
        async let shortSellingTask  = api.getShortSelling(code: item.code)
        async let valuationBandTask = api.getValuationBand(code: item.code)
        async let backtestTask          = api.getBacktest(code: item.code)
        async let flowSensitivityTask   = api.getFlowSensitivity(code: item.code)
        dartDisclosures = (try? await dartTask) ?? []
        earningsEntry   = (try? await earnsTask)?.first
        stockSignal     = try? await signalTask
        shortSelling    = try? await shortSellingTask
        valuationBand   = try? await valuationBandTask
        backtest        = try? await backtestTask
        flowSensitivity = try? await flowSensitivityTask
    }

    private func loadAnalysis(force: Bool = false) async {
        analyzing = true
        if force { commentExpanded = false }
        if let avgNum = item.avgPrice, let qtyNum = item.qty {
            analysis = try? await api.getAnalysisPersonalized(
                code: item.code,
                avgPrice: avgNum.doubleValue,
                qty: qtyNum.int64Value,
                targetPrice: item.targetPrice?.doubleValue ?? 0.0,
                stopPrice: item.stopPrice?.doubleValue ?? 0.0,
                mode: analysisMode.rawValue,
                refresh: force
            )
        } else {
            analysis = try? await api.getAnalysis(code: item.code, mode: analysisMode.rawValue, refresh: force)
        }
        analyzing = false
    }
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

// 행동 기록 입력 시트. action(관심/매수/매도) 선택 + 사유(선택) 입력 후 저장.
struct ActionLogSheetView: View {
    let code: String
    let logRepo: ActionLogRepository
    let currentPrice: Int64        // 기록 시점 현재가. 0 = 미기록.
    @Environment(\.dismiss) private var dismiss

    @State private var selectedAction = "interest"
    @State private var reason = ""

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
            }
            .navigationTitle("행동 기록")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("저장") {
                        logRepo.insert(
                            code: code,
                            action: selectedAction,
                            reason: reason.isEmpty ? nil : reason,
                            price: currentPrice
                        )
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }
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
