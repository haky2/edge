import SwiftUI
import SharedLogic
import Charts

// Phase 3 — 내 자산 탭. 평단가·수량이 입력된 종목만 모아 현재가로 평가금액·손익을 집계한다.
// 데이터 입력은 각 종목 상세(WatchlistView → StockDetailView)에서 하고, 여기선 집계만.
struct PortfolioView: View {
    private let api = Db.api
    @State private var rows: [HoldingRow] = []
    @State private var loading = false

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
                    .chartLegend(.hidden)
                    .frame(width: 100, height: 100)

                    // 간단 레전드
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(rows.sorted { $0.evaluated > $1.evaluated }, id: \.item.code) { row in
                            let pct = evaluated == 0 ? 0 : row.evaluated / evaluated * 100
                            HStack(spacing: 4) {
                                Circle().frame(width: 8, height: 8)
                                    .foregroundStyle(.secondary)  // Charts가 색 할당
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
