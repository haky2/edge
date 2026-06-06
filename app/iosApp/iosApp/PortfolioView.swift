import SwiftUI
import SharedLogic
import Charts

// Phase 3 — 내 자산 탭. 평단가·수량이 입력된 종목만 모아 현재가로 평가금액·손익을 집계한다.
// 데이터 입력은 각 종목 상세(WatchlistView → StockDetailView)에서 하고, 여기선 집계만.
struct PortfolioView: View {
    private let api = Db.api
    @State private var rows: [HoldingRow] = []
    @State private var loading = false

    // 도넛·레전드 공용 팔레트(SwiftUI Charts 기본 색 순서와 유사하게). 인덱스로 색을 고정한다.
    private static let sliceColors: [Color] = [.blue, .green, .orange, .purple, .pink, .teal, .indigo, .mint, .cyan, .yellow]
    private static func sliceColor(_ i: Int) -> Color { sliceColors[i % sliceColors.count] }

    // 종목코드 → 섹터 레이블 (보유 종목 추가 시 여기에 등록)
    private static let sectorMap: [String: String] = [
        "005930": "반도체", "000660": "반도체",                         // 삼성전자, SK하이닉스
        "018260": "IT서비스", "307950": "IT서비스",                     // 삼성SDS, 현대오토에버
        "064400": "IT서비스", "035420": "IT서비스",                     // LG씨엔에스, NAVER
        "012450": "방산", "047810": "방산",                             // 한화에어로스페이스, 한국항공우주
        "267260": "전력기기", "001440": "전력기기", "062040": "전력기기", // HD현대일렉트릭, 대한전선, 산일전기
        "329180": "조선",                                               // HD현대중공업
        "066570": "전자",                                               // LG전자
        "034020": "에너지",                                             // 두산에너빌리티
        "005380": "자동차",                                             // 현대차
    ]
    private static let sectorColors: [String: Color] = [
        "반도체": .blue, "IT서비스": .purple, "방산": .red,
        "전력기기": .orange, "조선": .teal, "전자": .green,
        "에너지": .yellow, "자동차": .cyan, "기타": .secondary,
    ]

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
            .toolbar {
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
        }
        .task { await load() }
        .refreshable { await load() }
    }

    // 보유 종목 리스트 + 상단 집계 카드
    private var holdingsList: some View {
        List {
            // 상단 집계 카드
            Section {
                summaryCard
            }
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)

            // 보유 종목 행
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
    }

    // 총 투자금·평가금액·손익·수익률 집계 카드 + 종목 비중 도넛
    private var summaryCard: some View {
        let invested   = rows.reduce(0.0) { $0 + $1.invested }
        let evaluated  = rows.reduce(0.0) { $0 + $1.evaluated }
        let totalPnl   = evaluated - invested
        let totalRate  = invested == 0 ? 0.0 : totalPnl / invested * 100
        let up         = totalPnl >= 0

        return VStack(spacing: 12) {
            // 숫자 요약
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("총 평가금액").font(.caption).foregroundColor(.secondary)
                    Text("\(Int(evaluated).formatted())원")
                        .font(.title2.weight(.bold))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text("총 손익").font(.caption).foregroundColor(.secondary)
                    Text("\(up ? "+" : "")\(Int(totalPnl).formatted())원")
                        .font(.headline.weight(.semibold))
                        .foregroundColor(up ? .red : .blue)
                    Text("\(up ? "+" : "")\(String(format: "%.2f", totalRate))%")
                        .font(.subheadline)
                        .foregroundColor(up ? .red : .blue)
                }
            }
            Divider()
            HStack {
                Text("총 투자금").font(.caption).foregroundColor(.secondary)
                Spacer()
                Text("\(Int(invested).formatted())원").font(.caption)
            }

            // 종목 비중 도넛
            if rows.count > 1 {
                // 도넛 조각과 레전드 점이 같은 색을 쓰도록 종목명→색을 명시적으로 고정한다.
                // (레전드 Circle은 차트 밖 도형이라 Charts가 자동으로 색을 주지 않음)
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

                    // 간단 레전드 — 도넛과 동일한 색
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

            // 섹터 비중 바
            if rows.count >= 1 && !sectorRows.isEmpty {
                Divider()
                VStack(alignment: .leading, spacing: 4) {
                    Text("섹터 비중").font(.caption).foregroundColor(.secondary)
                    let totalEval = sectorRows.reduce(0.0) { $0 + $1.evaluated }
                    ForEach(sectorRows, id: \.sector) { row in
                        let pct = totalEval == 0 ? 0.0 : row.evaluated / totalEval
                        let color = Self.sectorColors[row.sector] ?? .secondary
                        HStack(spacing: 8) {
                            Text(row.sector)
                                .font(.caption2)
                                .foregroundColor(color)
                                .frame(width: 52, alignment: .leading)
                            GeometryReader { geo in
                                RoundedRectangle(cornerRadius: 3)
                                    .fill(color.opacity(0.75))
                                    .frame(width: max(4, geo.size.width * CGFloat(pct)), height: 10)
                            }
                            .frame(height: 10)
                            Text(String(format: "%.0f%%", pct * 100))
                                .font(.caption2.monospacedDigit())
                                .foregroundColor(.secondary)
                                .frame(width: 30, alignment: .trailing)
                        }
                    }
                }
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
        .padding(.horizontal)
        .padding(.vertical, 4)
    }

    // 보유 종목 한 행: 이름 + 현재가/등락 | 평가손익/수익률
    private func holdingRow(_ row: HoldingRow) -> some View {
        let up = row.pnl >= 0
        return HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(row.item.name).font(.body)
                Text(row.item.code).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                if let q = row.quote {
                    let qUp = q.change >= 0
                    Text("\(q.price.formatted())원").font(.body.weight(.semibold))
                    Text("\(qUp ? "▲" : "▼") \(String(format: "%.2f", abs(q.changeRate)))%")
                        .font(.caption).foregroundColor(qUp ? .red : .blue)
                }
                Text("\(up ? "+" : "")\(Int(row.pnl).formatted())원 (\(up ? "+" : "")\(String(format: "%.1f", row.pnlRate))%)")
                    .font(.caption.monospacedDigit())
                    .foregroundColor(up ? .red : .blue)
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

    // 섹터별 평가금액 합산
    private var sectorRows: [(sector: String, evaluated: Double)] {
        var map: [String: Double] = [:]
        for row in rows {
            let sector = Self.sectorMap[row.item.code] ?? "기타"
            map[sector, default: 0] += row.evaluated
        }
        return map.map { (sector: $0.key, evaluated: $0.value) }
                  .sorted { $0.evaluated > $1.evaluated }
    }

    private func load() async {
        loading = true
        let all = Db.watchlist.all()
        // 평단가·수량이 모두 입력된 종목만
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
            let price = quote.map { Double($0.price) } ?? avg  // 시세 없으면 평단으로 대체
            return HoldingRow(item: item, quote: quote, avg: avg, qty: qty, price: price)
        }
        loading = false
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
