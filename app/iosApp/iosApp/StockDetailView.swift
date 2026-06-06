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
    @State private var dartExpanded = false
    @State private var earningsExpanded = false
    @State private var signalExpanded = false
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
                    if let tr = technicalResult { technicalCard(tr) }  // 이평·RSI·거래량
                } else if loading {
                    ProgressView().padding(.top, 40)
                }
                if !flows.isEmpty {
                    flowCard()       // 수급: 외인/기관/개인 일별 순매수
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
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showEdit = true } label: { Image(systemName: "square.and.pencil") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if loading {
                    ProgressView()
                } else {
                    Button { Task { await load() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
        .task { await load() }             // 진입 시 시세·수급·뉴스 갱신(빠름)
        .task { await loadAnalysis() }     // AI 코멘트는 느려서 별도로(동시 진행)
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

    // 가격 차트 + 기본 지표 카드. 일봉 데이터 로드 전엔 숫자만 표시.
    @ViewBuilder
    private func priceChartCard(_ q: Quote) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if !dailyBars.isEmpty {
                HStack(spacing: 10) {
                    chartLegendItem("종가", .primary)
                    chartLegendItem("MA5", .blue)
                    chartLegendItem("MA20", .orange)
                    chartLegendItem("MA60", .purple)
                }
                .font(.caption2)
                .padding(.top, 4)
                PriceLineChart(bars: dailyBars)
                    .frame(height: 150)
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

    private func miniStat(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.caption2).foregroundColor(.secondary)
            Spacer()
            Text(value).font(.caption2.weight(.medium))
        }
    }

    private func chartLegendItem(_ label: String, _ color: Color) -> some View {
        HStack(spacing: 3) {
            Rectangle().fill(color).frame(width: 12, height: 2)
            Text(label).foregroundColor(.secondary)
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

            if let t = item.targetPrice?.doubleValue {
                Divider()
                let gap = (t - price) / price * 100   // 현재가 대비 남은 거리
                let reached = price >= t
                row("목표가", "\(Int(t).formatted()) 원  " + (reached ? "🎯 도달" : String(format: "(%+.1f%%)", gap)))
            }
            if let s = item.stopPrice?.doubleValue {
                let gap = (s - price) / price * 100
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
                        "주가 ÷ 주당순이익. 이익 대비 주가 수준 — 낮을수록 이익 대비 저렴, 성장 기대가 크면 높게 형성됨.")
                }
                if q.pbr > 0 {
                    valuationRow("PBR", String(format: "%.2f배", q.pbr),
                        "주가 ÷ 주당순자산. 1배면 장부가치 수준 — 낮을수록 자산 대비 저렴.")
                }
            }
            if let tp = targetPriceInfo {
                if q.per > 0 || q.pbr > 0 || !q.sectorName.isEmpty { Divider() }
                let upside = Double(tp.price - q.price) / Double(q.price) * 100
                valuationRow(
                    "컨센서스 목표주가",
                    "\(tp.price.formatted())원  \(upside >= 0 ? "▲" : "▼")\(String(format: "%.1f%%", abs(upside)))",
                    tp.basis
                )
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
    private func valuationRow(_ label: String, _ value: String, _ meaning: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(label).foregroundColor(.secondary)
                Spacer()
                Text(value).fontWeight(.medium)
            }
            .font(.caption)
            Text(meaning).font(.caption2).foregroundColor(.secondary)
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

    // AI 종합 코멘트 카드(2c). 사실(시세·52주·PER·수급·뉴스)을 백엔드가 모아 Claude가 해석.
    // Claude 생성이라 수 초 걸려 별도 로딩. 매매 판단/책임은 사용자 — 참고용 디스클레이머를 항상 붙인다.
    @ViewBuilder
    private func aiCommentCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "sparkles").foregroundColor(.purple)
                Text("AI 종합 코멘트").font(.subheadline.weight(.semibold))
                Spacer()
                if analyzing { ProgressView().scaleEffect(0.8) }
            }
            .padding(.top, 8)

            if let a = analysis {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(a.comment.components(separatedBy: "\n\n"), id: \.self) { para in
                        let trimmed = para.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !trimmed.isEmpty {
                            Text(markdown(trimmed))
                                .font(.callout)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                Text(aiCommentFreshLabel(a) + " · 투자 판단과 책임은 본인에게 있습니다")
                    .font(.caption2).foregroundColor(.secondary)
                    .padding(.top, 2)
            } else if analyzing {
                Text("시세·수급·뉴스를 종합해 코멘트를 생성하고 있어요…")
                    .font(.footnote).foregroundColor(.secondary)
            } else {
                Text("코멘트를 불러오지 못했어요. 새로고침해 주세요.")
                    .font(.footnote).foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 6)
        .cardStyle()
    }

    // 기술적 지표 카드. 이평선(MA5/20/60)·RSI14·거래량 비율 표시. 계산만, 판단 없음.
    // null = 일봉 데이터 부족(해당 항목 숨김).
    private func technicalCard(_ r: TechnicalResult) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("기술적 지표").font(.subheadline.weight(.semibold)).padding(.top, 8)

            if let v = r.ma5?.doubleValue {
                technicalRow("MA5", "\(Int(v.rounded()).formatted()) 원",
                    "최근 5일 종가 평균. 현재가가 이 선 위면 단기 상승 추세.")
            }
            if let v = r.ma20?.doubleValue {
                technicalRow("MA20", "\(Int(v.rounded()).formatted()) 원",
                    "최근 20일 종가 평균(중기 추세선). 골든크로스·데드크로스의 기준선.")
            }
            if let v = r.ma60?.doubleValue {
                technicalRow("MA60", "\(Int(v.rounded()).formatted()) 원",
                    "최근 60일(약 3개월) 종가 평균. 장기 추세 방향을 보는 데 주로 사용.")
            }

            if r.ma5 != nil || r.ma20 != nil || r.ma60 != nil {
                if r.rsi14 != nil || r.volumeRatio != nil { Divider() }
            }

            if let v = r.rsi14?.doubleValue {
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text("RSI14").foregroundColor(.secondary)
                        Spacer()
                        Text(String(format: "%.1f", v))
                            .fontWeight(.medium)
                            .foregroundColor(rsiColor(v))
                        if !rsiLabel(v).isEmpty {
                            Text("· \(rsiLabel(v))").font(.caption2).foregroundColor(rsiColor(v))
                        }
                    }
                    .font(.caption)
                    Text("0~100 모멘텀 지표. 70 이상이면 단기 과매수(조정 가능성), 30 이하면 과매도(반등 가능성).")
                        .font(.caption2).foregroundColor(.secondary)
                }
            }

            if let v = r.volumeRatio?.doubleValue {
                technicalRow("거래량(20일 평균 대비)", String(format: "%.1f배", v),
                    "오늘 거래량 ÷ 최근 20일 평균. 2배 이상이면 거래 급증 — 큰 재료나 수급 변화가 있을 때 나타남.",
                    valueColor: v >= 2.0 ? .orange : .primary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, 6)
        .cardStyle()
    }

    // 기술적 지표 한 행: 라벨·값 + 아래에 설명 한 줄.
    private func technicalRow(_ label: String, _ value: String, _ desc: String, valueColor: Color = .primary) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(label).foregroundColor(.secondary)
                Spacer()
                Text(value).fontWeight(.medium).foregroundColor(valueColor)
            }
            .font(.caption)
            Text(desc).font(.caption2).foregroundColor(.secondary)
        }
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

    // 순매수 수량 축약: 1.2억 / 1102만 / 1,234. 부호 포함.
    private func flowText(_ n: Int64) -> String {
        if n == 0 { return "0" }
        let sign = n > 0 ? "+" : "-"
        let a = Double(abs(n))
        if a >= 1e8 { return sign + String(format: "%.1f억", a / 1e8) }
        if a >= 1e4 { return sign + String(format: "%.0f만", a / 1e4) }
        return sign + Int64(a).formatted()
    }

    // "20260602" → "06/02"
    private func mmdd(_ d: String) -> String {
        guard d.count == 8 else { return d }
        let m = d.dropFirst(4).prefix(2)
        let day = d.suffix(2)
        return "\(m)/\(day)"
    }

    // "참고용 · 오늘 09:32 생성" 또는 "참고용 · 2026-06-06 기준" 형태.
    private func aiCommentFreshLabel(_ a: Analysis) -> String {
        if !a.generatedAt.isEmpty {
            let todayStr = todayDateString()
            if todayStr == a.date {
                return "참고용 · 오늘 \(a.generatedAt) 생성"
            } else {
                return "참고용 · \(a.date) \(a.generatedAt) 생성"
            }
        }
        return "참고용 · \(a.date) 기준"
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
                            Text("매핑된 섹터 없음 (미지원 종목)").font(.caption2).foregroundColor(.secondary)
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

    private func load() async {
        loading = true
        if let q = try? await api.getQuote(code: item.code) { quote = q }
        flows = (try? await api.getInvestorFlow(code: item.code, days: 5)) ?? []
        news = (try? await api.getNews(stockName: item.name, display: 5)) ?? []
        if let daily = try? await api.getDaily(code: item.code, bars: 62) {
            dailyBars = daily
            technicalResult = TechnicalIndicators.shared.calculate(bars: daily)
        }
        targetPriceInfo = try? await api.getTargetPrice(code: item.code)
        loading = false

        // DART·실적·지표영향은 느린 네트워크 이후 병렬 로드 (접기 기본이라 늦어도 무방)
        async let dartTask   = api.getDartDisclosures(code: item.code, days: 30)
        async let earnsTask  = api.getEarnings(codes: [item.code])
        async let signalTask = api.getStockSignals(code: item.code)
        dartDisclosures = (try? await dartTask) ?? []
        earningsEntry   = (try? await earnsTask)?.first
        stockSignal     = try? await signalTask
    }

    private func loadAnalysis() async {
        analyzing = true
        if let avgNum = item.avgPrice, let qtyNum = item.qty {
            analysis = try? await api.getAnalysisPersonalized(
                code: item.code,
                avgPrice: avgNum.doubleValue,
                qty: qtyNum.int64Value,
                targetPrice: item.targetPrice?.doubleValue ?? 0.0,
                stopPrice: item.stopPrice?.doubleValue ?? 0.0
            )
        } else {
            analysis = try? await api.getAnalysis(code: item.code)
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
private struct PriceLineChart: View {
    let bars: [DailyBar]

    private struct CPoint: Identifiable {
        let id: Int; let close: Double
    }
    private struct MAPoint: Identifiable {
        let id: Int; let value: Double
    }

    private var orderedBars: [DailyBar] { Array(bars.reversed()) }

    private var closePts: [CPoint] {
        let all = orderedBars
        let start = max(0, all.count - 30)
        return all[start...].enumerated().map { i, b in CPoint(id: i, close: Double(b.close)) }
    }

    private func maPts(period: Int) -> [MAPoint] {
        let all = orderedBars
        let displayStart = max(0, all.count - 30)
        var result: [MAPoint] = []
        for i in displayStart..<all.count {
            guard i >= period - 1 else { continue }
            let sum = all[(i - period + 1)...i].reduce(0) { $0 + Double($1.close) }
            result.append(MAPoint(id: i - displayStart, value: sum / Double(period)))
        }
        return result
    }

    private var currentMA60: Double? {
        let all = orderedBars
        guard all.count >= 60 else { return nil }
        return all.suffix(60).reduce(0.0) { $0 + Double($1.close) } / 60.0
    }

    var body: some View {
        let ma5  = maPts(period: 5)
        let ma20 = maPts(period: 20)
        let allY = closePts.map(\.close) + ma5.map(\.value) + ma20.map(\.value)
        let yMin = (allY.min() ?? 0) * 0.997

        Chart {
            // 영역(면) — 종가 아래 옅은 그라데이션. 계열 색 스케일과 무관(직접 스타일).
            ForEach(closePts) { p in
                AreaMark(x: .value("일", p.id), yStart: .value("min", yMin), yEnd: .value("가격", p.close))
                    .foregroundStyle(LinearGradient(colors: [Color.blue.opacity(0.12), Color.clear], startPoint: .top, endPoint: .bottom))
            }
            .interpolationMethod(.monotone)
            // 라인 3종 — y 레이블을 "가격"으로 통일하고 series 로 계열을 구분해야
            // 계열별 색(chartForegroundStyleScale)이 확실히 적용된다.
            ForEach(closePts) { p in
                LineMark(x: .value("일", p.id), y: .value("가격", p.close), series: .value("계열", "종가"))
            }
            .foregroundStyle(by: .value("계열", "종가"))
            .lineStyle(StrokeStyle(lineWidth: 1.8))
            .interpolationMethod(.monotone)
            ForEach(ma5) { p in
                LineMark(x: .value("일", p.id), y: .value("가격", p.value), series: .value("계열", "MA5"))
            }
            .foregroundStyle(by: .value("계열", "MA5"))
            .lineStyle(StrokeStyle(lineWidth: 1.6))
            .interpolationMethod(.monotone)
            ForEach(ma20) { p in
                LineMark(x: .value("일", p.id), y: .value("가격", p.value), series: .value("계열", "MA20"))
            }
            .foregroundStyle(by: .value("계열", "MA20"))
            .lineStyle(StrokeStyle(lineWidth: 1.6))
            .interpolationMethod(.monotone)
            if let ma60 = currentMA60 {
                RuleMark(y: .value("가격", ma60))
                    .foregroundStyle(Color.purple)
                    .lineStyle(StrokeStyle(lineWidth: 1.3, dash: [4, 3]))
                    .annotation(position: .trailing, alignment: .center) {
                        Text("MA60").font(.system(size: 8)).foregroundColor(.purple)
                    }
            }
        }
        .chartForegroundStyleScale([
            "종가": Color.primary,
            "MA5":  Color.blue,
            "MA20": Color.orange,
        ])
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) { v in
                if let d = v.as(Double.self), d > 0 {
                    AxisValueLabel {
                        Text(priceYLabel(d)).font(.system(size: 9)).foregroundStyle(Color.secondary)
                    }
                }
                AxisGridLine(stroke: StrokeStyle(lineWidth: 0.3, dash: [3, 3]))
            }
        }
        .chartLegend(.hidden)
    }

    private func priceYLabel(_ v: Double) -> String {
        let n = Int(v)
        if n >= 1_000_000 { return "\(n / 10_000)만" }
        if n >= 10_000    { return "\(n / 1_000)천" }
        return n.formatted()
    }
}
