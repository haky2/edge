import SwiftUI
import SharedLogic

// 앱 전역 싱글톤. DB는 한 번만 열고, api 인스턴스도 공유한다.
enum Db {
    static let watchlist: WatchlistRepository = {
        let repo = WatchlistRepository(driverFactory: DriverFactory())
        repo.ensureSeeded()
        return repo
    }()
    static let actionLog = ActionLogRepository(driverFactory: DriverFactory())
    static let api = EdgeApi(baseUrl: "http://localhost:8080")
}

// 관심종목 탭. DB에서 종목 목록을 읽고 백엔드 /quotes로 라이브 시세를 합쳐 표시.
struct WatchlistView: View {
    @State private var watchlist: [WatchItem] = []     // DB에서 로드
    @State private var quotes: [String: Quote] = [:]   // code → 시세
    @State private var errorText: String?
    @State private var loading = false
    @State private var showSearch = false              // 검색 시트 표시

    private let api = Db.api

    var body: some View {
        NavigationStack {
            List {
                if let e = errorText {
                    Text(e).font(.footnote).foregroundColor(.secondary)
                }
                ForEach(watchlist, id: \.code) { item in
                    // 탭하면 상세 화면으로. 리스트가 받아둔 시세를 넘겨 즉시 표시.
                    NavigationLink {
                        StockDetailView(item: item, quote: quotes[item.code], api: api)
                    } label: {
                        row(item)
                    }
                }
                .onDelete(perform: delete)   // 1.3c — 스와이프 삭제 → DB에서 제거
            }
            .navigationTitle("관심종목")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showSearch = true } label: {
                        Image(systemName: "plus")
                    }
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
            .task { await load() }          // 첫 진입 시 로드
            .refreshable { await load() }   // 당겨서 새로고침
            // 검색 시트. 닫히면 DB를 다시 읽어 새로 추가한 종목이 리스트에 반영된다.
            .sheet(isPresented: $showSearch, onDismiss: { Task { await load() } }) {
                SearchView(api: api)
            }
        }
    }

    // 종목 한 줄: 왼쪽 이름/코드, 오른쪽 현재가/등락(상승 빨강·하락 파랑).
    @ViewBuilder
    private func row(_ item: WatchItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(.body)
                Text(item.code).font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            if let q = quotes[item.code] {
                let up = q.change >= 0
                VStack(alignment: .trailing, spacing: 2) {
                    Text(q.price.formatted()).font(.body.weight(.semibold))
                    Text("\(up ? "▲" : "▼") \(String(format: "%.2f", abs(q.changeRate)))%")
                        .font(.caption)
                        .foregroundColor(up ? .red : .blue)
                }
            } else {
                Text("—").foregroundColor(.secondary) // 아직 로드 전/실패
            }
        }
        .padding(.vertical, 4)
    }

    // 스와이프 삭제: 해당 종목을 DB에서 지우고 로컬 리스트도 즉시 갱신.
    private func delete(at offsets: IndexSet) {
        for index in offsets {
            Db.watchlist.remove(code: watchlist[index].code)
        }
        watchlist.remove(atOffsets: offsets)
    }

    private func load() async {
        loading = true
        errorText = nil
        // 관심종목을 DB에서 다시 읽는다(이후 추가/삭제가 반영되도록).
        let items = Db.watchlist.all()
        watchlist = items
        do {
            let codes = items.map { $0.code }
            let list = try await api.getQuotes(codes: codes)
            // 코드 → Quote 딕셔너리로 변환해 화면에서 빠르게 합친다.
            var map: [String: Quote] = [:]
            for q in list { map[q.code] = q }
            quotes = map
        } catch {
            errorText = "불러오기 실패: \(error.localizedDescription)\n(백엔드 실행 확인: cd backend && ./run.sh)"
        }
        loading = false
    }
}

struct ContentView: View {
    var body: some View {
        TabView {
            WatchlistView()
                .tabItem { Label("관심종목", systemImage: "star") }
            PortfolioView()
                .tabItem { Label("내 자산", systemImage: "chart.pie") }
            BriefingView()
                .tabItem { Label("브리핑", systemImage: "newspaper") }
        }
    }
}
