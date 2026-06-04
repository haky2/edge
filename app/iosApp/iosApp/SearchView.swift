import SwiftUI
import SharedLogic

// 1.4b — 종목 검색 → 결과 → 관심종목 추가.
// 백엔드 GET /search(q) 결과(StockInfo)를 받아 리스트로 보여주고, "추가" 시 SQLDelight DB(repo.add)에 넣는다(1.3c 경로).
// 시트로 띄우고, 닫으면 ContentView가 DB를 다시 읽어 새 종목이 반영된다.
struct SearchView: View {
    let api: EdgeApi
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var results: [StockInfo] = []
    @State private var loading = false
    @State private var errorText: String?
    @State private var inWatchlist: Set<String> = []   // 이미 관심종목인 코드(중복 추가 방지 표시)

    var body: some View {
        NavigationStack {
            List {
                if let e = errorText {
                    Text(e).font(.footnote).foregroundColor(.secondary)
                }
                if loading {
                    HStack { Spacer(); ProgressView(); Spacer() }
                } else if results.isEmpty && !query.isEmpty {
                    Text("검색 결과가 없어요").font(.footnote).foregroundColor(.secondary)
                }
                ForEach(results, id: \.code) { stock in
                    row(stock)
                }
            }
            .navigationTitle("종목 검색")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("완료") { dismiss() }
                }
            }
            // 검색어 입력 후 키보드 return → 검색 실행(타이핑마다 호출하지 않아 백엔드 부담 적음).
            .searchable(text: $query, prompt: "종목명 또는 코드")
            .onSubmit(of: .search) { Task { await runSearch() } }
            .task {
                // 이미 들어있는 관심종목 코드 미리 확보 → 결과에서 체크 표시.
                inWatchlist = Set(Db.watchlist.all().map { $0.code })
            }
        }
    }

    // 결과 한 줄: 이름/코드·시장 + 오른쪽 추가 버튼(또는 이미 추가됨 체크).
    @ViewBuilder
    private func row(_ stock: StockInfo) -> some View {
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
                Button("추가") { add(stock) }
                    .buttonStyle(.borderless)   // List 행 전체가 아니라 버튼만 눌리게
            }
        }
        .padding(.vertical, 2)
    }

    private func runSearch() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { results = []; return }
        loading = true
        errorText = nil
        do {
            results = try await api.search(query: q)
        } catch {
            errorText = "검색 실패: \(error.localizedDescription)\n(백엔드 실행 확인: cd backend && ./run.sh)"
        }
        loading = false
    }

    private func add(_ stock: StockInfo) {
        Db.watchlist.add(code: stock.code, name: stock.name)
        inWatchlist.insert(stock.code)   // 즉시 체크 표시로 전환
    }
}
