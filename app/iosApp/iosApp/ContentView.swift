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
    @State private var watchlist: [WatchItem] = []
    @State private var quotes: [String: Quote] = [:]
    @State private var supplyBadges: [String: [String]] = [:]  // code → ["외인 3일↑", ...]
    @State private var errorText: String?
    @State private var loading = false
    @State private var showSearch = false

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

    // 종목 한 줄: 이름/코드+수급배지, 현재가/등락.
    @ViewBuilder
    private func row(_ item: WatchItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).font(.body)
                HStack(spacing: 4) {
                    Text(item.code).font(.caption2).foregroundColor(.secondary)
                    if let badges = supplyBadges[item.code] {
                        ForEach(badges, id: \.self) { badge in
                            Text(badge)
                                .font(.system(size: 9, weight: .semibold))
                                .padding(.horizontal, 5).padding(.vertical, 2)
                                .background(Color.orange.opacity(0.15))
                                .foregroundColor(.orange)
                                .clipShape(Capsule())
                        }
                    }
                }
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
                Text("—").foregroundColor(.secondary)
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
        Task { await loadSupplyBadges(items: items) }
    }

    // 수급 배지: 외인/기관 3일 연속 순매수 여부를 백그라운드에서 확인.
    private func loadSupplyBadges(items: [WatchItem]) async {
        var result: [String: [String]] = [:]
        await withTaskGroup(of: (String, [String]).self) { group in
            for item in items {
                group.addTask {
                    guard let flows = try? await self.api.getInvestorFlow(code: item.code, days: 3),
                          flows.count >= 3 else { return (item.code, []) }
                    var labels: [String] = []
                    if flows[0].foreign > 0 && flows[1].foreign > 0 && flows[2].foreign > 0 { labels.append("외인 3일↑") }
                    if flows[0].institution > 0 && flows[1].institution > 0 && flows[2].institution > 0 { labels.append("기관 3일↑") }
                    return (item.code, labels)
                }
            }
            for await (code, labels) in group { if !labels.isEmpty { result[code] = labels } }
        }
        supplyBadges = result
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
            StatsView()
                .tabItem { Label("내 패턴", systemImage: "chart.line.uptrend.xyaxis") }
        }
    }
}
