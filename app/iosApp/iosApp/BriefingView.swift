import SwiftUI
import SharedLogic

private enum BriefingTab: String, CaseIterable {
    case myStocks = "내 종목"
    case market  = "시장"
}

struct BriefingView: View {
    private let api = Db.api

    @State private var selectedTab: BriefingTab = .myStocks

    // quotes 로드 중: 전체 화면 스피너. false가 되면 하이라이트/보유현황 즉시 표시.
    @State private var loading = false
    // supply 로드 중: 수급 섹션 내부 스피너. quotes와 독립적으로 관리.
    @State private var supplyLoading = false
    @State private var errorText: String?

    // 시장 지표(코스피·환율·미국지수). 관심종목과 무관해 quotes와 독립 병렬 로드.
    @State private var macroLoading = false
    @State private var macroItems: [MacroIndicator] = []

    // 매크로 → 내 종목 영향(Claude 해석). 느려서 섹션 내부 스피너 + 독립 병렬.
    @State private var impactLoading = false
    @State private var impactComment = ""
    @State private var impactHoldings: [StockImpact] = []
    @State private var impactWatch: [StockImpact] = []

    @State private var topGainers: [QuoteRow] = []
    @State private var topLosers: [QuoteRow] = []
    @State private var holdings: [HoldingRow] = []
    @State private var supplyRows: [SupplyRow] = []

    // DART 최근 공시
    @State private var dartLoading = false
    @State private var dartItems: [DartItem] = []

    // 실적 일정
    @State private var earningsLoading = false
    @State private var earningsItems: [EarningsEntry] = []

    // 섹터 동향
    @State private var sectorLoading = false
    @State private var sectorItems: [SectorIndex] = []

    // 섹터 분석 (Claude 코멘트 + 주목 종목)
    @State private var sectorBriefingLoading = false
    @State private var sectorBriefingComment = ""
    @State private var sectorSpotlight: [SpotlightStock] = []

    // 하이라이트·보유현황용: 쿼츠 로드 후 저장 (spotlight NavigationLink에서도 재사용)
    @State private var allItemsLoaded: [WatchItem] = []
    @State private var quoteMapLoaded: [String: Quote] = [:]

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    content
                }
            }
            .navigationTitle("브리핑")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if loading || supplyLoading || dartLoading || macroLoading || impactLoading || earningsLoading || sectorLoading || sectorBriefingLoading {
                        ProgressView().scaleEffect(0.8)
                    } else {
                        Button { Task { await load() } } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .task { await load() }
            .refreshable { await load() }
        }
    }

    private var content: some View {
        List {
            if let e = errorText {
                Section {
                    Text(e).font(.footnote).foregroundColor(.secondary)
                }
            }
            // 탭 선택기 — 섹션 구분선 없이 세그먼트 컨트롤만 노출
            Picker("", selection: $selectedTab) {
                ForEach(BriefingTab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color(.systemGroupedBackground))
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))

            if selectedTab == .myStocks {
                highlightSection
                holdingsSection
                supplySection
                dartSection
            } else {
                marketSection
                sectorSection
                sectorBriefingSection
                earningsSection
                impactSection
            }
        }
    }

    // MARK: - 섹션: 시장 지표

    @ViewBuilder
    private var marketSection: some View {
        Section("시장 지표") {
            if macroLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("확인 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if macroItems.isEmpty {
                Text("불러오기 실패").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(macroItems, id: \.key) { macroRow($0) }
            }
        }
    }

    private func macroRow(_ m: MacroIndicator) -> some View {
        // 보합(0%)은 회색, 상승 빨강/하락 파랑(국내 관례).
        let flat = m.changeRate == 0
        let up = m.changeRate > 0
        let color: Color = flat ? .secondary : (up ? .red : .blue)
        let arrow = flat ? "–" : (up ? "▲" : "▼")
        return VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(m.label).font(.body)
                if !m.tag.isEmpty {
                    Text(m.tag)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(tagColor(m.tag).opacity(0.15))
                        .foregroundColor(tagColor(m.tag))
                        .clipShape(Capsule())
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(formatMacroValue(m)).font(.body.weight(.semibold))
                    Text("\(arrow) \(String(format: "%.2f", abs(m.changeRate)))%")
                        .font(.caption).foregroundColor(color)
                }
            }
            if m.key == "fear_greed" {
                Text("CNN이 산출하는 시장 심리 지수. 0=극단적 공포 · 50=중립 · 100=극단적 탐욕.")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    // 공포탐욕지수는 0–100 점수라 소수 1자리로 단순 표시. 나머지는 천단위 구분 + 소수 2자리.
    // 주의: Swift String(format:)은 "%,.2f" 천단위 플래그 미지원 → NumberFormatter 사용.
    private func formatMacroValue(_ m: MacroIndicator) -> String {
        if m.key == "fear_greed" { return String(format: "%.1f", m.value) }
        return macroValueFormatter.string(from: NSNumber(value: m.value)) ?? String(format: "%.2f", m.value)
    }

    // 공포탐욕 tag 색: 공포 계열 → 파랑, 탐욕 계열 → 빨강, 중립 → 회색.
    private func tagColor(_ tag: String) -> Color {
        if tag.contains("공포") { return .blue }
        if tag.contains("탐욕") { return .red }
        return .secondary
    }

    // MARK: - 섹션: 섹터 동향

    @ViewBuilder
    private var sectorSection: some View {
        Section("섹터 동향") {
            if sectorLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("확인 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if sectorItems.isEmpty {
                Text("불러오기 실패").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(sectorItems, id: \.key) { sectorRow($0) }
            }
        }
    }

    private func sectorRow(_ s: SectorIndex) -> some View {
        let flat = s.changeRate == 0
        let up = s.changeRate > 0
        let color: Color = flat ? .secondary : (up ? .red : .blue)
        let arrow = flat ? "–" : (up ? "▲" : "▼")
        return HStack {
            Text(s.label).font(.body)
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(sectorValueFormatter.string(from: NSNumber(value: s.value)) ?? String(format: "%.2f", s.value))
                    .font(.body.weight(.semibold))
                Text("\(arrow) \(String(format: "%.2f", abs(s.changeRate)))%")
                    .font(.caption).foregroundColor(color)
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: 섹터 분석 (Claude)

    @ViewBuilder
    private var sectorBriefingSection: some View {
        Section("섹터 분석") {
            if sectorBriefingLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("AI가 분석 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if sectorBriefingComment.isEmpty {
                Text("불러오기 실패").font(.footnote).foregroundColor(.secondary)
            } else {
                Text(markdown(sectorBriefingComment)).font(.callout)
            }
        }
        // 주목 종목 — 코멘트 준비됐을 때만 표시
        if !sectorBriefingLoading && !sectorSpotlight.isEmpty {
            Section("오늘 주목 종목") {
                ForEach(sectorSpotlight, id: \.code) { stock in
                    let item = allItemsLoaded.first { $0.code == stock.code }
                    let quote = quoteMapLoaded[stock.code]
                    if let item {
                        NavigationLink {
                            StockDetailView(item: item, quote: quote, api: api)
                        } label: {
                            spotlightRow(stock, quote: quote)
                        }
                    } else {
                        spotlightRow(stock, quote: nil)
                    }
                }
            }
        }
    }

    private func spotlightRow(_ stock: SpotlightStock, quote: Quote?) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(stock.name).font(.body)
                Text(stock.sectorLabel).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            if let q = quote {
                let up = q.changeRate >= 0
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(q.price.formatted())원").font(.body.weight(.semibold))
                    Text("\(up ? "▲" : "▼") \(String(format: "%.2f", abs(q.changeRate)))%")
                        .font(.caption).foregroundColor(up ? .red : .blue)
                }
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: 실적 일정

    @ViewBuilder
    private var earningsSection: some View {
        Section("실적 일정 (D-90 이내)") {
            if earningsLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("확인 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if earningsItems.isEmpty {
                Text("90일 이내 예정 없음").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(earningsItems, id: \.code) { earningsRow($0) }
            }
        }
    }

    private func earningsRow(_ e: EarningsEntry) -> some View {
        let days = Int(e.daysUntil)  // Kotlin Int → Swift Int32 → Int
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(e.corpName).font(.body)
                Text(e.reportName).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            Text(ddayLabel(days))
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 8).padding(.vertical, 3)
                .background(ddayColor(days).opacity(0.15))
                .foregroundColor(ddayColor(days))
                .clipShape(Capsule())
        }
        .padding(.vertical, 2)
    }

    private func ddayLabel(_ days: Int) -> String {
        switch days {
        case 0:         return "D-day"
        case 1...:      return "D-\(days)"
        default:        return "D+\(abs(days))"
        }
    }

    private func ddayColor(_ days: Int) -> Color {
        switch days {
        case ..<0:      return .red      // 기한 초과
        case 0..<14:    return .red
        case 14..<30:   return .orange
        default:        return .secondary
        }
    }

    // MARK: - 섹션: 매크로 영향 (내 종목)

    @ViewBuilder
    private var impactSection: some View {
        // 종합 코멘트
        Section("내 종목 영향 (오늘)") {
            if impactLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("AI가 해석 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if impactComment.isEmpty {
                Text("불러오기 실패").font(.footnote).foregroundColor(.secondary)
            } else {
                Text(markdown(impactComment)).font(.callout)
                Text("※ 참고용 해석입니다. 투자 판단·책임은 본인에게 있습니다.")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        // 보유/관심 종목별 방향(코멘트가 준비됐을 때만)
        if !impactLoading && !impactComment.isEmpty {
            if !impactHoldings.isEmpty {
                Section("보유 — 지표 영향") {
                    ForEach(impactHoldings, id: \.code) { impactRow($0) }
                }
            }
            if !impactWatch.isEmpty {
                Section("관심 — 지표 영향") {
                    ForEach(impactWatch, id: \.code) { impactRow($0) }
                }
            }
        }
    }

    private func impactRow(_ s: StockImpact) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.name).font(.body)
                    Text(s.sectorLabel).font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                netBadge(s.net)
            }
            if s.signals.isEmpty {
                Text("영향 매핑 준비 중").font(.caption2).foregroundColor(.secondary)
            } else {
                ForEach(s.signals, id: \.indicator) { sig in
                    let dir = Int(sig.direction)
                    Text("\(sig.indicator) \(signedRate(sig.changeRate))% → \(directionLabel(dir))")
                        .font(.caption2)
                        .foregroundColor(directionColor(dir))
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func netBadge(_ net: String) -> some View {
        let color: Color = net == "우호적" ? .red : (net == "부담" ? .blue : .secondary)
        return Text(net)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
    }

    private func directionLabel(_ d: Int) -> String {
        d > 0 ? "우호" : (d < 0 ? "부담" : "중립")
    }

    private func directionColor(_ d: Int) -> Color {
        d > 0 ? .red : (d < 0 ? .blue : .secondary)
    }

    private func signedRate(_ v: Double) -> String {
        (v >= 0 ? "+" : "") + String(format: "%.2f", v)
    }

    // Claude 코멘트의 **굵게**·줄바꿈을 살려서 렌더(실패 시 평문).
    private func markdown(_ s: String) -> AttributedString {
        (try? AttributedString(
            markdown: s,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(s)
    }

    // MARK: - 섹션: 하이라이트

    @ViewBuilder
    private var highlightSection: some View {
        Section("오늘 하이라이트") {
            if topGainers.isEmpty && topLosers.isEmpty {
                Text("변동 종목 없음").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(topGainers, id: \.item.code) { quoteLink($0) }
                ForEach(topLosers,  id: \.item.code) { quoteLink($0) }
            }
        }
    }

    private func quoteLink(_ row: QuoteRow) -> some View {
        NavigationLink {
            StockDetailView(item: row.item, quote: row.quote, api: api)
        } label: {
            let up = row.quote.changeRate >= 0
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.item.name).font(.body)
                    Text(row.item.code).font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(row.quote.price.formatted())원").font(.body.weight(.semibold))
                    Text("\(up ? "▲" : "▼") \(String(format: "%.2f", abs(row.quote.changeRate)))%")
                        .font(.caption).foregroundColor(up ? .red : .blue)
                }
            }
            .padding(.vertical, 2)
        }
    }

    // MARK: - 섹션: 보유현황

    @ViewBuilder
    private var holdingsSection: some View {
        if holdings.isEmpty {
            Section("보유현황") {
                Text("평단가를 입력한 종목이 없어요")
                    .font(.footnote).foregroundColor(.secondary)
            }
        } else {
            let invested  = holdings.reduce(0.0) { $0 + $1.invested }
            let evaluated = holdings.reduce(0.0) { $0 + $1.evaluated }
            let totalPnl  = evaluated - invested
            let totalRate = invested == 0 ? 0.0 : totalPnl / invested * 100
            let up        = totalPnl >= 0

            Section {
                VStack(spacing: 6) {
                    HStack {
                        Text("총 평가금액").font(.caption).foregroundColor(.secondary)
                        Spacer()
                        Text("\(Int(evaluated).formatted())원").font(.subheadline.weight(.semibold))
                    }
                    HStack {
                        Text("총 손익").font(.caption).foregroundColor(.secondary)
                        Spacer()
                        Text("\(up ? "+" : "")\(Int(totalPnl).formatted())원  \(up ? "+" : "")\(String(format: "%.2f", totalRate))%")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(up ? .red : .blue)
                    }
                }
                .padding(.vertical, 4)
            } header: {
                Text("보유현황 (\(holdings.count)개)")
            }

            Section {
                ForEach(holdings, id: \.item.code) { row in
                    NavigationLink {
                        StockDetailView(item: row.item, quote: row.quote, api: api)
                    } label: {
                        holdingRow(row)
                    }
                }
            }
        }
    }

    private func holdingRow(_ row: HoldingRow) -> some View {
        let up = row.pnl >= 0
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.name).font(.body)
                Text(row.item.code).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("\(up ? "+" : "")\(Int(row.pnl).formatted())원")
                    .font(.subheadline.weight(.semibold)).foregroundColor(up ? .red : .blue)
                Text("\(up ? "+" : "")\(String(format: "%.1f", row.pnlRate))%")
                    .font(.caption).foregroundColor(up ? .red : .blue)
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: 수급주목

    @ViewBuilder
    private var supplySection: some View {
        Section("수급주목 (3일 연속 순매수)") {
            if supplyLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("확인 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if supplyRows.isEmpty {
                Text("해당 종목 없음").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(supplyRows, id: \.item.code) { row in
                    NavigationLink {
                        StockDetailView(item: row.item, quote: row.quote, api: api)
                    } label: {
                        supplyRow(row)
                    }
                }
            }
        }
    }

    private func supplyRow(_ row: SupplyRow) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.name).font(.body)
                Text(row.item.code).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                ForEach(row.labels, id: \.self) { label in
                    Text(label)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.orange.opacity(0.15))
                        .foregroundColor(.orange)
                        .clipShape(Capsule())
                }
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: DART 최근 공시

    @ViewBuilder
    private var dartSection: some View {
        Section("최근 공시 (7일)") {
            if dartLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("확인 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if dartItems.isEmpty {
                Text("공시 없음").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(dartItems.indices, id: \.self) { i in
                    let item = dartItems[i]
                    if let url = URL(string: item.url) {
                        Link(destination: url) {
                            dartRow(item)
                        }
                        .foregroundColor(.primary)
                    } else {
                        dartRow(item)
                    }
                }
            }
        }
    }

    private func dartRow(_ item: DartItem) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(item.reportName)
                .font(.subheadline)
                .lineLimit(2)
            HStack(spacing: 6) {
                Text(item.corpName).font(.caption2).foregroundColor(.secondary)
                Text(item.formattedDate).font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 3)
    }

    // MARK: - 로드

    private func load() async {
        loading = true
        supplyLoading = true
        dartLoading = true
        macroLoading = true
        impactLoading = true
        earningsLoading = true
        sectorLoading = true
        sectorBriefingLoading = true
        errorText = nil
        supplyRows = []
        dartItems = []
        macroItems = []
        impactComment = ""
        impactHoldings = []
        impactWatch = []
        earningsItems = []
        sectorItems = []
        sectorBriefingComment = ""
        sectorSpotlight = []

        let allItems = Db.watchlist.all()
        let codes = allItems.map { $0.code }

        // 시장 지표·섹터·실적일정·매크로 영향·섹터 브리핑은 quotes와 독립 병렬.
        async let macroTask: Void           = buildMacro()
        async let sectorTask: Void          = buildSectors()
        async let earningsTask: Void        = buildEarnings(codes: codes)
        async let impactTask: Void          = buildImpact(allItems: allItems)
        async let sectorBriefingTask: Void  = buildSectorBriefing(codes: codes)

        let quotes: [Quote]
        do {
            quotes = try await api.getQuotes(codes: codes)
        } catch {
            errorText = "불러오기 실패: \(error.localizedDescription)\n(백엔드: cd backend && ./run.sh)"
            loading = false
            supplyLoading = false
            dartLoading = false
            _ = await (macroTask, sectorTask, earningsTask, impactTask, sectorBriefingTask)
            return
        }
        let quoteMap = Dictionary(uniqueKeysWithValues: quotes.map { ($0.code, $0) })

        // quotes 완료 → 하이라이트/보유현황 즉시 반영. allItems·quoteMap을 state에 저장(섹터 브리핑 spotlight에서 재사용).
        allItemsLoaded = allItems
        quoteMapLoaded = quoteMap
        buildHighlights(allItems: allItems, quoteMap: quoteMap)
        buildHoldings(allItems: allItems, quoteMap: quoteMap)
        loading = false

        // supply·dart·macro·impact·sectorBriefing은 섹션 내부 스피너 유지하며 병렬 진행
        async let supplyTask: Void = buildSupply(allItems: allItems, quoteMap: quoteMap)
        async let dartTask: Void   = buildDart(codes: codes, allItems: allItems)
        _ = await (supplyTask, dartTask, macroTask, sectorTask, earningsTask, impactTask, sectorBriefingTask)

        supplyLoading = false
        dartLoading = false
    }

    private func buildSectorBriefing(codes: [String]) async {
        defer { sectorBriefingLoading = false }
        guard let result = try? await api.getSectorBriefing(codes: codes) else { return }
        sectorBriefingComment = result.comment
        sectorSpotlight = result.spotlight
    }

    private func buildMacro() async {
        defer { macroLoading = false }
        guard let items = try? await api.getMacro() else { return }
        macroItems = items
    }

    private func buildSectors() async {
        defer { sectorLoading = false }
        guard let items = try? await api.getSectors() else { return }
        sectorItems = items
    }

    private func buildEarnings(codes: [String]) async {
        defer { earningsLoading = false }
        guard let items = try? await api.getEarnings(codes: codes) else { return }
        earningsItems = items
    }

    private func buildImpact(allItems: [WatchItem]) async {
        defer { impactLoading = false }
        // 평단·수량이 있으면 보유, 없으면 관심(미보유) — holdingsSection 판정과 동일 기준.
        let holdings = allItems.filter { $0.avgPrice != nil && $0.qty != nil }.map { $0.code }
        let watchlist = allItems.filter { $0.avgPrice == nil || $0.qty == nil }.map { $0.code }
        guard let impact = try? await api.getMacroImpact(holdings: holdings, watchlist: watchlist) else { return }
        impactComment = impact.comment
        impactHoldings = impact.holdings
        impactWatch = impact.watchlist
    }

    private func buildHighlights(allItems: [WatchItem], quoteMap: [String: Quote]) {
        let rows = allItems.compactMap { item -> QuoteRow? in
            guard let q = quoteMap[item.code] else { return nil }
            return QuoteRow(item: item, quote: q)
        }
        let sorted = rows.sorted { $0.quote.changeRate > $1.quote.changeRate }
        topGainers = Array(sorted.prefix(2).filter { $0.quote.changeRate > 0 })
        topLosers  = Array(sorted.suffix(2).reversed().filter { $0.quote.changeRate < 0 })
    }

    private func buildHoldings(allItems: [WatchItem], quoteMap: [String: Quote]) {
        holdings = allItems.compactMap { item in
            guard let avgNum = item.avgPrice, let qtyNum = item.qty else { return nil }
            let avg   = avgNum.doubleValue
            let qty   = Double(qtyNum.int64Value)
            let q     = quoteMap[item.code]
            let price = q.map { Double($0.price) } ?? avg
            return HoldingRow(item: item, quote: q, avg: avg, qty: qty, price: price)
        }
    }

    private func buildDart(codes: [String], allItems: [WatchItem]) async {
        // 관심종목 전체 /dart 병렬 호출 → 최신순 정렬, 최대 10건
        var result: [DartItem] = []
        await withTaskGroup(of: [DartItem].self) { group in
            for code in codes {
                group.addTask {
                    guard let disclosures = try? await self.api.getDartDisclosures(code: code, days: 7) else {
                        return []
                    }
                    return disclosures.map { d in
                        DartItem(corpName: d.corpName, reportName: d.reportName, date: d.date, url: d.url)
                    }
                }
            }
            for await items in group { result.append(contentsOf: items) }
        }
        dartItems = Array(result.sorted { $0.date > $1.date }.prefix(10))
    }

    private func buildSupply(allItems: [WatchItem], quoteMap: [String: Quote]) async {
        var result: [SupplyRow] = []
        await withTaskGroup(of: SupplyRow?.self) { group in
            for item in allItems {
                group.addTask {
                    guard let flows = try? await self.api.getInvestorFlow(code: item.code, days: 3),
                          flows.count >= 3 else { return nil }
                    var labels: [String] = []
                    if flows[0].foreign > 0 && flows[1].foreign > 0 && flows[2].foreign > 0 {
                        labels.append("외인 3일↑")
                    }
                    if flows[0].institution > 0 && flows[1].institution > 0 && flows[2].institution > 0 {
                        labels.append("기관 3일↑")
                    }
                    guard !labels.isEmpty else { return nil }
                    return SupplyRow(item: item, quote: quoteMap[item.code], labels: labels)
                }
            }
            for await row in group {
                if let row { result.append(row) }
            }
        }
        supplyRows = result.sorted { $0.item.name < $1.item.name }
    }
}

// MARK: - 포맷터

// 업종지수 값 표시용(천단위 구분 + 소수 2자리).
private let sectorValueFormatter: NumberFormatter = {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return f
}()

// 지수·환율 값 표시용(천단위 구분 + 소수 2자리). 매 호출 생성 비용을 피해 파일 레벨 1회 생성.
private let macroValueFormatter: NumberFormatter = {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return f
}()

// MARK: - 내부 모델

private struct QuoteRow {
    let item: WatchItem
    let quote: Quote
}

private struct HoldingRow {
    let item: WatchItem
    let quote: Quote?
    let avg: Double
    let qty: Double
    let price: Double

    var invested:  Double { avg * qty }
    var evaluated: Double { price * qty }
    var pnl:       Double { (price - avg) * qty }
    var pnlRate:   Double { avg == 0 ? 0 : (price - avg) / avg * 100 }
}

private struct SupplyRow {
    let item: WatchItem
    let quote: Quote?
    let labels: [String]
}

private struct DartItem {
    let corpName: String
    let reportName: String
    let date: String    // YYYYMMDD
    let url: String

    // "20250601" → "25.06.01"
    var formattedDate: String {
        guard date.count == 8 else { return date }
        let y = date.dropFirst(2).prefix(2)
        let m = date.dropFirst(4).prefix(2)
        let d = date.dropFirst(6).prefix(2)
        return "\(y).\(m).\(d)"
    }
}
