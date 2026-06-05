import SwiftUI
import SharedLogic

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
    @State private var logEntries: [ActionLogEntry] = []  // 이 종목 행동 로그
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
                    priceHeader(q)   // 현재가 + 등락
                    detailCard(q)    // 거래량·시고저·52주
                    analysisCard(q)  // 지표 해석 ① 계산 기반(52주 위치·수급 흐름 요약)
                    if let tr = technicalResult { technicalCard(tr) }  // 이평·RSI·거래량(2단계)
                    aiCommentCard()  // ② Claude 종합 코멘트(2c)
                    positionCard(q)  // 내 포지션 + 수익률(1.5)
                } else if loading {
                    ProgressView().padding(.top, 40)
                }
                if !flows.isEmpty {
                    flowCard()       // 수급: 외인/기관/개인 일별 순매수(Phase 2)
                }
                if !news.isEmpty {
                    newsCard()       // 관련 뉴스 헤드라인(2b)
                }
                if !logEntries.isEmpty {
                    logCard()        // 행동 로그(매매일지)
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
            ActionLogSheetView(code: item.code, logRepo: logRepo)
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

    // 상세 지표 카드
    private func detailCard(_ q: Quote) -> some View {
        VStack(spacing: 0) {
            row("거래량", q.volume.formatted())
            Divider()
            row("시가", q.open.formatted())
            row("고가", q.high.formatted())
            row("저가", q.low.formatted())
            Divider()
            row("52주 최고", q.high52w.formatted())
            row("52주 최저", q.low52w.formatted())
        }
        .cardStyle()
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
                insight("52주 위치", "\(Int(c.pctInRange52w.rounded()))% · \(rangeLabel(c.pctInRange52w))")
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
                Text(a.comment)
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
                Text("참고용 · \(a.date) 기준 · 투자 판단과 책임은 본인에게 있습니다")
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

    // 수급 카드(Phase 2). 외인/기관/개인 일별 순매수 수량(주). 순매수=빨강 / 순매도=파랑.
    // 장후 확정 일별값(백엔드가 미확정 당일은 제외). 큰 수는 만/억으로 축약.
    private func flowCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("수급 · 순매수(주)").font(.subheadline.weight(.semibold))
                .padding(.top, 8)
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
                    Text(shortTs(entry.createdAt))
                        .font(.caption2).foregroundColor(.secondary)
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

    private func load() async {
        loading = true
        if let q = try? await api.getQuote(code: item.code) { quote = q }
        flows = (try? await api.getInvestorFlow(code: item.code, days: 5)) ?? []
        news = (try? await api.getNews(stockName: item.name, display: 5)) ?? []
        if let daily = try? await api.getDaily(code: item.code, bars: 62) {
            technicalResult = TechnicalIndicators.shared.calculate(bars: daily)
        }
        targetPriceInfo = try? await api.getTargetPrice(code: item.code)
        loading = false
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
                            reason: reason.isEmpty ? nil : reason
                        )
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }
}
