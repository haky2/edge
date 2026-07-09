import SwiftUI
import SharedLogic
import Charts

// Phase 3 — 내 자산 탭. 평단가·수량이 입력된 종목만 모아 현재가로 평가금액·손익을 집계한다.
// 데이터 입력은 각 종목 상세(WatchlistView → StockDetailView)에서 하고, 여기선 집계만.
struct PortfolioView: View {
    private let api = Db.api
    @State private var rows: [HoldingRow] = []
    @State private var sectorClassify: [String: String] = [:]  // code → sectorLabel (백엔드)
    @State private var portfolioReview: PortfolioReview? = nil
    @State private var rebalanceCheck: RebalanceCheck? = nil
    @State private var baselineResetting = false
    @State private var reviewLoading = false
    @State private var reviewExpanded = true
    @State private var reviewCommentExpanded = false
    @State private var loading = false
    @State private var lastUpdated: Date?
    @State private var showAccountMgmt = false
    @State private var accounts: [AccountInfo] = []
    @State private var selectedAccountId: Int64? = nil
    @AppStorage(analysisModeKey) private var modeRaw = AnalysisMode.defensive.rawValue
    private var analysisMode: AnalysisMode { AnalysisMode(rawValue: modeRaw) ?? .defensive }

    // 도넛·레전드 공용 팔레트. 인덱스로 색을 고정한다.
    private static let sliceColors: [Color] = [.blue, .green, .orange, .purple, .pink, .teal, .indigo, .mint, .cyan, .yellow]
    private static func sliceColor(_ i: Int) -> Color { sliceColors[i % sliceColors.count] }

    // 섹터 라벨 → 색상 (백엔드 Sector.label 기준)
    private static let sectorColorMap: [String: Color] = [
        "메모리반도체": .blue, "파운드리·장비": .cyan, "AI반도체": .indigo,
        "AI·클라우드": .purple, "IT서비스·SI": .purple,
        "인터넷플랫폼": .mint, "로봇·자동화": .teal, "자율주행": .teal,
        "완성차": .orange, "자동차부품": .orange, "2차전지": .yellow,
        "조선": .teal, "방산·항공우주": .red,
        "전력기기": .orange, "전선": .orange, "신재생에너지": .green,
        "가전": .green, "디스플레이": .cyan, "전자부품": .green,
        "기타": .secondary,
    ]
    private static func sectorColor(_ label: String) -> Color {
        sectorColorMap[label] ?? .secondary
    }

    var body: some View {
        NavigationStack {
            Group {
                if loading && rows.isEmpty {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if rows.isEmpty {
                    emptyState
                } else {
                    holdingsList
                }
            }
            .navigationTitle("내 자산")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showAccountMgmt = true } label: {
                        Image(systemName: "creditcard")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if loading {
                        ProgressView().scaleEffect(0.8)
                    } else {
                        Menu {
                            if let t = lastUpdated {
                                Section("\(shortTime(t)) 기준 · 시세") {
                                    Button { Task { await load() } } label: {
                                        Label("전체 새로고침", systemImage: "arrow.clockwise")
                                    }
                                }
                            } else {
                                Button { Task { await load() } } label: {
                                    Label("전체 새로고침", systemImage: "arrow.clockwise")
                                }
                            }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        } primaryAction: {
                            Task { await load() }
                        }
                    }
                }
            }
            .sheet(isPresented: $showAccountMgmt, onDismiss: {
                // 계좌 추가/삭제/보유 이전이 있었을 수 있다 — 세그먼트·rows 동기화
                Task { await load() }
            }) {
                NavigationStack { AccountManagementView() }
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .onChange(of: modeRaw) {
            portfolioReview = nil
            Task { await loadPortfolioReview(force: false) }
        }
    }

    // G3: 커스텀 계좌(비기본 계좌)가 있을 때만 세그먼트 노출
    private var customAccounts: [AccountInfo] { accounts.filter { $0.isDefault == 0 } }

    // 선택 계좌 기준 필터링 (nil = 전체, 다계좌 동일 종목은 병합)
    private var displayRows: [HoldingRow] {
        guard let selId = selectedAccountId else { return Self.mergedByCode(rows) }
        return rows.filter { $0.accountId == selId }
    }

    // 같은 종목을 여러 계좌에 나눠 담은 경우 전체 뷰에서 1행으로 병합 — 수량 합·가중평균 평단
    // (hydrate와 동일 의미론, 투자원금 합 보존). ForEach id \.item.code 유일성도 이걸로 보장된다.
    private static func mergedByCode(_ rows: [HoldingRow]) -> [HoldingRow] {
        guard Set(rows.map { $0.item.code }).count != rows.count else { return rows }
        var order: [String] = []
        var groups: [String: [HoldingRow]] = [:]
        for r in rows {
            if groups[r.item.code] == nil { order.append(r.item.code) }
            groups[r.item.code, default: []].append(r)
        }
        return order.map { code in
            let g = groups[code]!
            guard g.count > 1 else { return g[0] }
            let qtySum = g.reduce(0.0) { $0 + $1.qty }
            let invested = g.reduce(0.0) { $0 + $1.avg * $1.qty }
            let avg = qtySum > 0 ? invested / qtySum : g[0].avg
            let first = g[0]
            let item = WatchItem(
                code: first.item.code, name: first.item.name,
                avgPrice: KotlinDouble(double: avg),
                qty: KotlinLong(longLong: Int64(qtySum)),
                targetPrice: g.compactMap { $0.item.targetPrice }.first,
                stopPrice: g.compactMap { $0.item.stopPrice }.first,
                thesis: first.item.thesis
            )
            return HoldingRow(item: item, quote: first.quote, avg: avg, qty: qtySum,
                              price: first.price, accountId: first.accountId)
        }
    }

    // 보유 종목 리스트 + 상단 집계 카드
    private var holdingsList: some View {
        List {
            if !customAccounts.isEmpty {
                Section {
                    Picker("계좌", selection: $selectedAccountId) {
                        Text("전체").tag(Int64?.none)
                        ForEach(accounts, id: \.id) { acc in
                            Text(acc.name).tag(Optional(acc.id))
                        }
                    }
                    .pickerStyle(.segmented)
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            Section {
                summaryCard
            }

            if portfolioReview != nil || reviewLoading {
                Section {
                    portfolioReviewCard
                }
            }

            Section("보유 종목 \(displayRows.count)개") {
                ForEach(displayRows, id: \.item.code) { row in
                    NavigationLink {
                        StockDetailView(item: row.item, quote: row.quote, api: api)
                    } label: {
                        holdingRow(row)
                    }
                }
            }
        }
        .contentMargins(.top, 0, for: .scrollContent)
    }

    // 총 투자금·평가금액·손익·수익률 집계 카드 + 도넛 + 손익 기여도 + 섹터 비중
    private var summaryCard: some View {
        let invested   = displayRows.reduce(0.0) { $0 + $1.invested }
        let evaluated  = displayRows.reduce(0.0) { $0 + $1.evaluated }
        let totalPnl   = evaluated - invested
        let totalRate  = invested == 0 ? 0.0 : totalPnl / invested * 100
        let pnlColor: Color = totalPnl > 0 ? .red : totalPnl < 0 ? .blue : .secondary

        return VStack(spacing: 12) {
            // ── 숫자 요약 ──
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("총 평가금액").font(.caption).foregroundColor(.secondary)
                    Text("\(Int(evaluated).formatted())원")
                        .font(.title2.weight(.bold))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text("총 손익").font(.caption).foregroundColor(.secondary)
                    Text("\(totalPnl > 0 ? "+" : "")\(Int(totalPnl).formatted())원")
                        .font(.headline.weight(.semibold))
                        .foregroundColor(pnlColor)
                    Text("\(totalPnl > 0 ? "+" : "")\(String(format: "%.2f", totalRate))%")
                        .font(.subheadline)
                        .foregroundColor(pnlColor)
                }
            }
            Divider()
            HStack {
                Text("총 투자금").font(.caption).foregroundColor(.secondary)
                Spacer()
                Text("\(Int(invested).formatted())원").font(.caption)
            }

            // ── 종목 비중 도넛 ──
            if displayRows.count > 1 {
                let colorByName = Dictionary(
                    uniqueKeysWithValues: displayRows.enumerated().map { ($1.item.name, Self.sliceColor($0)) }
                )
                Divider()
                HStack(alignment: .center, spacing: 16) {
                    Chart(displayRows, id: \.item.code) { row in
                        SectorMark(
                            angle: .value("비중", row.evaluated),
                            innerRadius: .ratio(0.55),
                            angularInset: 1.5
                        )
                        .cornerRadius(3)
                        .foregroundStyle(by: .value("종목", row.item.name))
                    }
                    .chartForegroundStyleScale(
                        domain: displayRows.map { $0.item.name },
                        range: displayRows.indices.map { Self.sliceColor($0) }
                    )
                    .chartLegend(.hidden)
                    .frame(width: 100, height: 100)

                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(displayRows.sorted { $0.evaluated > $1.evaluated }, id: \.item.code) { row in
                            let pct = evaluated == 0 ? 0 : row.evaluated / evaluated * 100
                            HStack(spacing: 4) {
                                Circle().frame(width: 8, height: 8)
                                    .foregroundStyle(colorByName[row.item.name] ?? .secondary)
                                Text(row.item.name).font(.caption2).lineLimit(1)
                                Spacer()
                                Text(String(format: "%.1f%%", pct)).font(.caption2.monospacedDigit())
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }

            // ── 손익 기여도 발산 막대 ──
            let hasPnl = displayRows.contains { $0.pnl != 0 }
            if displayRows.count >= 1 && hasPnl {
                Divider()
                pnlContributionView
            }

            // ── 섹터 비중 + 집중도 경고 ──
            if !sectorRows.isEmpty {
                Divider()
                sectorWeightView(totalEval: evaluated)
            }
        }
        .padding(.vertical, 4)
    }

    // MARK: - 손익 기여도 발산 막대

    private var pnlContributionView: some View {
        let sorted = displayRows.sorted { $0.pnl > $1.pnl }  // 수익 → 손실 순
        let maxAbs = max(sorted.map { abs($0.pnl) }.max() ?? 1.0, 1.0)

        return VStack(alignment: .leading, spacing: 6) {
            Text("손익 기여도").font(.caption).foregroundColor(.secondary)
            ForEach(sorted, id: \.item.code) { row in
                VStack(alignment: .leading, spacing: 2) {
                    // 이름 + 금액: 한 행에 — 이름이 잘리지 않도록 풀 폭 사용
                    HStack {
                        Text(row.item.name)
                            .font(.caption2)
                            .lineLimit(1)
                        Spacer()
                        let sign = row.pnl >= 0 ? "+" : ""
                        Text("\(sign)\(Int(row.pnl).formatted())")
                            .font(.caption2.monospacedDigit())
                            .foregroundColor(row.pnl >= 0 ? .red : .blue)
                    }
                    // 발산 막대: 전폭을 쓰므로 모든 행의 중심선이 동일 x에 정렬됨
                    GeometryReader { geo in
                        let half = geo.size.width / 2
                        let ratio = CGFloat(min(abs(row.pnl) / maxAbs, 1.0))
                        let fillW = max(2, half * ratio)
                        ZStack {
                            Rectangle()
                                .fill(Color.secondary.opacity(0.2))
                                .frame(width: 1, height: 6)
                            if row.pnl >= 0 {
                                HStack(spacing: 0) {
                                    Color.clear.frame(width: half)
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(Color.red.opacity(0.65))
                                        .frame(width: fillW, height: 5)
                                    Spacer()
                                }
                            } else {
                                HStack(spacing: 0) {
                                    Spacer()
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(Color.blue.opacity(0.65))
                                        .frame(width: fillW, height: 5)
                                    Color.clear.frame(width: half)
                                }
                            }
                        }
                    }
                    .frame(height: 6)
                }
            }
        }
    }

    // MARK: - 포트폴리오 종합 진단 카드

    @ViewBuilder
    private var portfolioReviewCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Image(systemName: "sparkles").foregroundColor(.purple)
                Text("포트폴리오 종합 진단").font(.subheadline.weight(.semibold))
                if analysisMode == .aggressive {
                    Text("⚔️ 공격적")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.orange.opacity(0.15))
                        .foregroundColor(.orange)
                        .clipShape(Capsule())
                }
                Spacer()
                if reviewLoading { ProgressView().scaleEffect(0.8) }
                Image(systemName: reviewExpanded ? "chevron.up" : "chevron.down")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { reviewExpanded.toggle() } }

            if reviewExpanded {
                if let rev = portfolioReview {
                    Divider()
                    VStack(alignment: .leading, spacing: 12) {
                        // 핵심 요약
                        if let summary = rev.summary, !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
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
                        }

                        // 코멘트
                        let commentBlocks = rev.comment
                            .components(separatedBy: "\n\n")
                            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                            .filter { !$0.isEmpty && $0 != "---" }
                        let visibleBlocks = reviewCommentExpanded ? commentBlocks : Array(commentBlocks.prefix(2))
                        HStack(alignment: .top, spacing: 10) {
                            RoundedRectangle(cornerRadius: 2)
                                .fill(Color.purple.opacity(0.35))
                                .frame(width: 3)
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(Array(visibleBlocks.enumerated()), id: \.offset) { _, block in
                                    Text(markdown(block))
                                        .font(.callout)
                                        .lineSpacing(5)
                                        .fixedSize(horizontal: false, vertical: true)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                        }
                        if commentBlocks.count > 2 {
                            Button {
                                withAnimation(.easeInOut(duration: 0.2)) { reviewCommentExpanded.toggle() }
                            } label: {
                                HStack(spacing: 4) {
                                    Text(reviewCommentExpanded ? "접기" : "더보기")
                                    Image(systemName: reviewCommentExpanded ? "chevron.up" : "chevron.down")
                                }
                                .font(.caption.weight(.semibold))
                                .foregroundColor(.purple)
                            }
                        }

                        // 노출 요약 (상위 2개)
                        let topExposures = rev.exposures.filter { $0.favorablePct > 0 || $0.adversePct > 0 }.prefix(2)
                        if !topExposures.isEmpty {
                            Divider()
                            VStack(alignment: .leading, spacing: 4) {
                                Text("주요 매크로 노출").font(.caption).foregroundColor(.secondary)
                                ForEach(Array(topExposures.enumerated()), id: \.offset) { _, ex in
                                    HStack(spacing: 6) {
                                        Text(ex.label).font(.caption2).foregroundColor(.secondary).lineLimit(1)
                                        Spacer()
                                        if ex.favorablePct > 0 {
                                            Text("수혜 \(Int(ex.favorablePct))%")
                                                .font(.caption2.monospacedDigit())
                                                .foregroundColor(.red)
                                        }
                                        if ex.adversePct > 0 {
                                            Text("부담 \(Int(ex.adversePct))%")
                                                .font(.caption2.monospacedDigit())
                                                .foregroundColor(.blue)
                                        }
                                    }
                                }
                            }
                        }

                        // 비중 드리프트 (R3)
                        if let rc = rebalanceCheck, rc.evaluated {
                            Divider()
                            VStack(alignment: .leading, spacing: 6) {
                                HStack(spacing: 4) {
                                    Text("비중 드리프트").font(.caption).foregroundColor(.secondary)
                                    if let d = rc.baselineSetAt {
                                        Text("(기준: \(d))").font(.caption2).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                }
                                ForEach(rc.drifts, id: \.code) { d in
                                    HStack(spacing: 4) {
                                        Text(d.name).font(.caption2).lineLimit(1)
                                            .frame(width: 72, alignment: .leading)
                                        Text(String(format: "%.1f%%", d.baselinePct))
                                            .font(.caption2.monospacedDigit()).foregroundColor(.secondary)
                                        Text("→").font(.caption2).foregroundColor(.secondary)
                                        Text(String(format: "%.1f%%", d.currentPct))
                                            .font(.caption2.monospacedDigit())
                                        let sign = d.deltaPp >= 0 ? "+" : ""
                                        Text("\(sign)\(String(format: "%.1f", d.deltaPp))%p")
                                            .font(.caption2.monospacedDigit())
                                            .foregroundColor(d.fired ? .orange : .secondary)
                                        if d.fired {
                                            Image(systemName: "exclamationmark.circle.fill")
                                                .font(.caption2).foregroundColor(.orange)
                                        }
                                        Spacer()
                                    }
                                }
                                if rc.topBandFired {
                                    HStack(spacing: 4) {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .font(.caption2).foregroundColor(.orange)
                                        Text("상단권 쏠림 \(String(format: "%.0f", rc.topBandWeightPct ?? 0))%")
                                            .font(.caption2).foregroundColor(.orange)
                                        Text("(\(rc.topBandStocks.joined(separator: "·")))")
                                            .font(.caption2).foregroundColor(.secondary)
                                        Spacer()
                                    }
                                }
                                HStack {
                                    Spacer()
                                    if baselineResetting {
                                        ProgressView().scaleEffect(0.7)
                                    } else {
                                        Button {
                                            Task {
                                                baselineResetting = true
                                                try? await api.postRebalanceBaseline()
                                                rebalanceCheck = try? await api.getRebalanceCheck()
                                                baselineResetting = false
                                            }
                                        } label: {
                                            Label("기준점 재설정", systemImage: "arrow.triangle.2.circlepath")
                                                .font(.caption2).foregroundColor(.orange)
                                        }
                                    }
                                }
                            }
                        }

                        // 생성 정보 + 재생성 버튼
                        HStack {
                            let todayStr: String = {
                                let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"
                                f.timeZone = TimeZone(identifier: "Asia/Seoul")
                                return f.string(from: Date())
                            }()
                            let label = rev.generatedAt.isEmpty ? "참고용 · \(rev.date) 기준"
                                : (rev.date == todayStr ? "참고용 · 오늘 \(rev.generatedAt) 생성" : "참고용 · \(rev.date) \(rev.generatedAt) 생성")
                            Text(label).font(.caption2).foregroundColor(.secondary)
                            Spacer()
                            if reviewLoading {
                                ProgressView().scaleEffect(0.7)
                            } else {
                                Button {
                                    Task { await loadPortfolioReview(force: true) }
                                } label: {
                                    Label("재생성", systemImage: "arrow.clockwise")
                                        .font(.caption2)
                                        .foregroundColor(.purple)
                                }
                            }
                        }
                        Text("투자 판단과 책임은 본인에게 있습니다")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(.top, 8).padding(.bottom, 4)
                } else if reviewLoading {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("포트폴리오 구조 분석 중…").font(.footnote).foregroundColor(.secondary)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - 섹터 비중 + 집중도 경고

    private func sectorWeightView(totalEval: Double) -> some View {
        let total = sectorRows.reduce(0.0) { $0 + $1.evaluated }
        let concentratedSectors = sectorRows.filter { $0.evaluated / total > 0.4 }

        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text("섹터 비중").font(.caption).foregroundColor(.secondary)
                if !concentratedSectors.isEmpty {
                    HStack(spacing: 3) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.caption2)
                            .foregroundColor(.orange)
                        Text("\(concentratedSectors.map { $0.sector }.joined(separator: "·")) 집중")
                            .font(.caption2)
                            .foregroundColor(.orange)
                    }
                }
            }

            ForEach(sectorRows, id: \.sector) { row in
                let pct = total == 0 ? 0.0 : row.evaluated / total
                let isConcentrated = pct > 0.4
                let color = Self.sectorColor(row.sector)
                HStack(spacing: 8) {
                    Text(row.sector)
                        .font(.caption2)
                        .foregroundColor(isConcentrated ? .orange : color)
                        .frame(width: 80, alignment: .leading)
                    GeometryReader { geo in
                        RoundedRectangle(cornerRadius: 3)
                            .fill((isConcentrated ? Color.orange : color).opacity(0.75))
                            .frame(width: max(4, geo.size.width * CGFloat(pct)), height: 10)
                    }
                    .frame(height: 10)
                    Text(String(format: "%.0f%%", pct * 100))
                        .font(.caption2.monospacedDigit())
                        .foregroundColor(isConcentrated ? .orange : .secondary)
                        .frame(width: 30, alignment: .trailing)
                    if isConcentrated {
                        Image(systemName: "exclamationmark.circle.fill")
                            .font(.caption2)
                            .foregroundColor(.orange)
                    }
                }
            }

            if !concentratedSectors.isEmpty {
                Text("한 섹터 비중이 40%를 초과하면 특정 업황·지표에 포트폴리오 전체가 흔들릴 수 있어요.")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    // MARK: - 보유 종목 행

    private func holdingRow(_ row: HoldingRow) -> some View {
        let pnlColor: Color = row.pnl > 0 ? .red : row.pnl < 0 ? .blue : .secondary
        return HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(row.item.name).font(.body)
                Text(row.item.code).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                if let q = row.quote {
                    let qColor: Color = q.changeRate > 0 ? .red : q.changeRate < 0 ? .blue : .secondary
                    let qSymbol = q.changeRate > 0 ? "▲" : q.changeRate < 0 ? "▼" : "—"
                    Text("\(q.price.formatted())원").font(.body.weight(.semibold))
                    Text("\(qSymbol) \(String(format: "%.2f", abs(q.changeRate)))%")
                        .font(.caption).foregroundColor(qColor)
                }
                Text("\(row.pnl > 0 ? "+" : "")\(Int(row.pnl).formatted())원 (\(row.pnl > 0 ? "+" : "")\(String(format: "%.1f", row.pnlRate))%)")
                    .font(.caption.monospacedDigit())
                    .foregroundColor(pnlColor)
            }
        }
        .padding(.vertical, 4)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray").font(.system(size: 40)).foregroundColor(.secondary)
            Text("평단가를 입력한 종목이 없어요")
                .font(.headline).foregroundColor(.secondary)
            Text("관심종목 상세에서 평단가·수량을 입력하면\n여기서 수익률을 집계해 줍니다")
                .font(.caption).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 집계 연산

    // 백엔드 분류 우선, 없으면 "기타"
    private var sectorRows: [(sector: String, evaluated: Double)] {
        var map: [String: Double] = [:]
        for row in displayRows {
            let sector = sectorClassify[row.item.code] ?? "기타"
            map[sector, default: 0] += row.evaluated
        }
        return map.map { (sector: $0.key, evaluated: $0.value) }
                  .sorted { $0.evaluated > $1.evaluated }
    }

    // MARK: - 로드

    private func load() async {
        loading = true
        // G1: holding 테이블이 보유 포지션의 단일 정본
        let allHoldings = Db.holding.all().filter { $0.avgPrice != nil && $0.qty != nil }
        // thesis는 watchlist 테이블 기반 — holding에 없으므로 별도 로드
        let thesisMap: [String: String] = Dictionary(
            uniqueKeysWithValues: (Db.watchlist.all() as! [WatchItem])
                .compactMap { i -> (String, String)? in
                    guard let t = i.thesis, !t.isEmpty else { return nil }
                    return (i.code, t)
                }
        )
        // G3: 계좌 목록 로드 (세그먼트 컨트롤 노출 여부 판단)
        accounts = Db.account.all() as! [AccountInfo]
        // 삭제된 계좌를 가리키는 선택 해제 — 세그먼트가 숨어도 필터만 남아 빈 화면이 고착되는 것 방지
        if let sel = selectedAccountId,
           !accounts.contains(where: { $0.id == sel }) || customAccounts.isEmpty {
            selectedAccountId = nil
        }
        guard !allHoldings.isEmpty else {
            rows = []
            loading = false
            return
        }
        let codes = allHoldings.map { $0.code }
        let quotes = (try? await api.getQuotes(codes: codes)) ?? []
        let quoteMap = Dictionary(uniqueKeysWithValues: quotes.map { ($0.code, $0) })
        rows = allHoldings.compactMap { h in
            guard let avgNum = h.avgPrice, let qtyNum = h.qty else { return nil }
            let avg = avgNum.doubleValue
            let qty = Double(qtyNum.int64Value)
            let quote = quoteMap[h.code]
            let price = quote.map { Double($0.price) } ?? avg
            // StockDetailView 연결용 WatchItem — holding 포지션 + watchlist 논지를 담아 전달
            let item = WatchItem(code: h.code, name: h.name,
                                 avgPrice: h.avgPrice, qty: h.qty,
                                 targetPrice: h.targetPrice, stopPrice: h.stopPrice,
                                 thesis: thesisMap[h.code])
            return HoldingRow(item: item, quote: quote, avg: avg, qty: qty, price: price, accountId: h.accountId)
        }
        lastUpdated = Date()
        loading = false

        // 섹터 분류 + 포트폴리오 진단은 화면 표시 차단 없이 별도 비동기 로드
        if let entries = try? await api.getSectorClassify(codes: codes) {
            sectorClassify = Dictionary(uniqueKeysWithValues: entries.map { ($0.code, $0.sectorLabel) })
        }
        await loadPortfolioReview(force: false)
    }

    private func loadPortfolioReview(force: Bool) async {
        guard !rows.isEmpty else { return }
        reviewLoading = true
        if force { portfolioReview = nil; reviewCommentExpanded = false }
        // 다계좌 동일 종목은 병합해 전달 — code 키 딕셔너리라 병합 없이는 마지막 계좌 값만 남는다
        var positions: [String: KotlinPair<KotlinDouble, KotlinLong>] = [:]
        var theses: [String: String] = [:]
        for row in Self.mergedByCode(rows) {
            positions[row.item.code] = KotlinPair(
                first: KotlinDouble(value: row.avg),
                second: KotlinLong(value: Int64(row.qty))
            )
            if let t = row.item.thesis, !t.isEmpty {
                theses[row.item.code] = t
            }
        }
        async let reviewTask = api.getPortfolioReview(
            positions: positions,
            theses: theses,
            mode: analysisMode.rawValue,
            refresh: force
        )
        async let checkTask = api.getRebalanceCheck()
        portfolioReview = try? await reviewTask
        rebalanceCheck = try? await checkTask
        reviewLoading = false
    }

    // MARK: - 포맷 헬퍼

    private func shortTime(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        return f.string(from: d)
    }

    private func markdown(_ s: String) -> AttributedString {
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
}

// 보유 종목 1건 계산 모델
private struct HoldingRow {
    let item: WatchItem
    let quote: Quote?
    let avg: Double
    let qty: Double
    let price: Double
    let accountId: Int64  // G3: 계좌 필터링용

    var invested:  Double { avg * qty }
    var evaluated: Double { price * qty }
    var pnl:       Double { (price - avg) * qty }
    var pnlRate:   Double { avg == 0 ? 0 : (price - avg) / avg * 100 }
}
