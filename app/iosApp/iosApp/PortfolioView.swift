import SwiftUI
import SharedLogic
import Charts

// Phase 3 — 내 자산 탭. 평단가·수량이 입력된 종목만 모아 현재가로 평가금액·손익을 집계한다.
// 데이터 입력은 각 종목 상세(WatchlistView → StockDetailView)에서 하고, 여기선 집계만.
struct PortfolioView: View {
    private let api = Db.api
    @State private var rows: [HoldingRow] = []
    @State private var sectorClassify: [String: String] = [:]  // code → sectorLabel (백엔드)
    @State private var loading = false
    @State private var lastUpdated: Date?

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
        }
        .task { await load() }
        .refreshable { await load() }
    }

    // 보유 종목 리스트 + 상단 집계 카드
    private var holdingsList: some View {
        List {
            Section {
                summaryCard
            }

            Section("보유 종목 \(rows.count)개") {
                ForEach(rows, id: \.item.code) { row in
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
        let invested   = rows.reduce(0.0) { $0 + $1.invested }
        let evaluated  = rows.reduce(0.0) { $0 + $1.evaluated }
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
            if rows.count > 1 {
                let colorByName = Dictionary(
                    uniqueKeysWithValues: rows.enumerated().map { ($1.item.name, Self.sliceColor($0)) }
                )
                Divider()
                HStack(alignment: .center, spacing: 16) {
                    Chart(rows, id: \.item.code) { row in
                        SectorMark(
                            angle: .value("비중", row.evaluated),
                            innerRadius: .ratio(0.55),
                            angularInset: 1.5
                        )
                        .cornerRadius(3)
                        .foregroundStyle(by: .value("종목", row.item.name))
                    }
                    .chartForegroundStyleScale(
                        domain: rows.map { $0.item.name },
                        range: rows.indices.map { Self.sliceColor($0) }
                    )
                    .chartLegend(.hidden)
                    .frame(width: 100, height: 100)

                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(rows.sorted { $0.evaluated > $1.evaluated }, id: \.item.code) { row in
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
            let hasPnl = rows.contains { $0.pnl != 0 }
            if rows.count >= 1 && hasPnl {
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
        let sorted = rows.sorted { $0.pnl > $1.pnl }  // 수익 → 손실 순
        let maxAbs = max(sorted.map { abs($0.pnl) }.max() ?? 1.0, 1.0)

        return VStack(alignment: .leading, spacing: 6) {
            Text("손익 기여도").font(.caption).foregroundColor(.secondary)
            ForEach(sorted, id: \.item.code) { row in
                HStack(spacing: 6) {
                    Text(row.item.name)
                        .font(.caption2)
                        .lineLimit(1)
                        .frame(width: 68, alignment: .leading)

                    // 발산 막대: 가운데 기준선, 수익 오른쪽(빨강), 손실 왼쪽(파랑)
                    GeometryReader { geo in
                        let half = geo.size.width / 2
                        let ratio = CGFloat(min(abs(row.pnl) / maxAbs, 1.0))
                        let fillW = max(2, half * ratio)
                        ZStack {
                            Rectangle()
                                .fill(Color.secondary.opacity(0.2))
                                .frame(width: 1, height: 10)
                            if row.pnl >= 0 {
                                HStack(spacing: 0) {
                                    Color.clear.frame(width: half)
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(Color.red.opacity(0.65))
                                        .frame(width: fillW, height: 8)
                                    Spacer()
                                }
                            } else {
                                HStack(spacing: 0) {
                                    Spacer()
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(Color.blue.opacity(0.65))
                                        .frame(width: fillW, height: 8)
                                    Color.clear.frame(width: half)
                                }
                            }
                        }
                    }
                    .frame(height: 10)

                    let sign = row.pnl >= 0 ? "+" : ""
                    Text("\(sign)\(Int(row.pnl).formatted())")
                        .font(.caption2.monospacedDigit())
                        .foregroundColor(row.pnl >= 0 ? .red : .blue)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        // 고정 폭: 값 길이에 따라 컬럼이 달라지면 위 GeometryReader 차트 폭·중심선이 행마다 어긋난다.
                        .frame(width: 92, alignment: .trailing)
                }
            }
        }
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
        for row in rows {
            let sector = sectorClassify[row.item.code] ?? "기타"
            map[sector, default: 0] += row.evaluated
        }
        return map.map { (sector: $0.key, evaluated: $0.value) }
                  .sorted { $0.evaluated > $1.evaluated }
    }

    // MARK: - 로드

    private func load() async {
        loading = true
        let all = Db.watchlist.all()
        let holdings = all.filter { $0.avgPrice != nil && $0.qty != nil }
        guard !holdings.isEmpty else {
            rows = []
            loading = false
            return
        }
        let codes = holdings.map { $0.code }
        let quotes = (try? await api.getQuotes(codes: codes)) ?? []
        let quoteMap = Dictionary(uniqueKeysWithValues: quotes.map { ($0.code, $0) })
        rows = holdings.compactMap { item in
            guard let avgNum = item.avgPrice, let qtyNum = item.qty else { return nil }
            let avg = avgNum.doubleValue
            let qty = Double(qtyNum.int64Value)
            let quote = quoteMap[item.code]
            let price = quote.map { Double($0.price) } ?? avg
            return HoldingRow(item: item, quote: quote, avg: avg, qty: qty, price: price)
        }
        lastUpdated = Date()
        loading = false

        // 섹터 분류는 7일 캐시라 별도 비동기 로드 (화면 표시 차단 없이)
        if let entries = try? await api.getSectorClassify(codes: codes) {
            sectorClassify = Dictionary(uniqueKeysWithValues: entries.map { ($0.code, $0.sectorLabel) })
        }
    }

    // MARK: - 포맷 헬퍼

    private func shortTime(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        return f.string(from: d)
    }
}

// 보유 종목 1건 계산 모델
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
