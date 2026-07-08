import SwiftUI
import SharedLogic

struct SearchView: View {
    let api: EdgeApi
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var results: [StockInfo] = []
    @State private var overseasResults: [OverseasStockInfo] = []
    @State private var loading = false
    @State private var errorText: String?
    @State private var inWatchlist: Set<String> = []

    var body: some View {
        NavigationStack {
            List {
                if let e = errorText {
                    Text(e).font(.footnote).foregroundColor(.secondary)
                }
                if loading {
                    HStack { Spacer(); ProgressView(); Spacer() }
                } else if results.isEmpty && overseasResults.isEmpty && !query.isEmpty {
                    Text("검색 결과가 없어요").font(.footnote).foregroundColor(.secondary)
                }
                if !results.isEmpty {
                    Section("국내") {
                        ForEach(results, id: \.code) { stock in
                            domesticRow(stock)
                        }
                    }
                }
                if !overseasResults.isEmpty {
                    Section("해외") {
                        ForEach(overseasResults, id: \.code) { stock in
                            overseasRow(stock)
                        }
                    }
                }
            }
            .navigationTitle("종목 검색")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("완료") { dismiss() }
                }
            }
            .searchable(text: $query, prompt: "종목명 또는 코드")
            .onSubmit(of: .search) { Task { await runSearch() } }
            .task {
                inWatchlist = Set(Db.watchlist.all().map { $0.code })
            }
        }
    }

    @ViewBuilder
    private func domesticRow(_ stock: StockInfo) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(stock.name).font(.body)
                Text("\(stock.code) · \(stock.market)")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            if inWatchlist.contains(stock.code) {
                Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
            } else {
                Button("추가") { addDomestic(stock) }
                    .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private func overseasRow(_ stock: OverseasStockInfo) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(stock.nameEn.isEmpty ? stock.symb : stock.nameEn).font(.body)
                Text("\(stock.symb) · \(stock.market) · \(stock.currency)")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            if inWatchlist.contains(stock.code) {
                Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
            } else {
                Button("추가") { addOverseas(stock) }
                    .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
    }

    private func runSearch() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { results = []; overseasResults = []; return }
        loading = true
        errorText = nil
        async let dom = api.search(query: q)
        async let ovs = api.searchOverseas(query: q)
        results = (try? await dom) ?? []
        overseasResults = (try? await ovs) ?? []
        loading = false
    }

    private func addDomestic(_ stock: StockInfo) {
        Db.watchlist.add(code: stock.code, name: stock.name)
        inWatchlist.insert(stock.code)
    }

    private func addOverseas(_ stock: OverseasStockInfo) {
        let displayName = stock.nameEn.isEmpty ? stock.symb : stock.nameEn
        Db.watchlist.add(code: stock.code, name: displayName)
        inWatchlist.insert(stock.code)
    }
}
