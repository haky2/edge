import SwiftUI
import SharedLogic

// SQLDelight DB는 앱 전체에서 한 번만 연다(드라이버 중복 오픈 방지). 첫 접근 시 기본 11종목 시드.
enum Db {
    static let watchlist: WatchlistRepository = {
        let repo = WatchlistRepository(driverFactory: DriverFactory())
        repo.ensureSeeded()
        return repo
    }()
}

// 1.3b — 관심종목을 SQLDelight 로컬 DB에서 읽어 라이브 시세와 함께 리스트로 표시.
// 종목 목록(코드+이름)은 이제 DB(watchlist 테이블)가 정본, 시세는 백엔드 /quotes로 합친다.
struct ContentView: View {
    @State private var watchlist: [WatchItem] = []     // DB에서 로드
    @State private var quotes: [String: Quote] = [:]   // code → 시세
    @State private var errorText: String?
    @State private var loading = false

    // iOS 시뮬레이터는 localhost 가 맥의 백엔드를 그대로 가리킨다.
    private let api = EdgeApi(baseUrl: "http://localhost:8080")

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
            }
            .navigationTitle("관심종목")
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
            .task { await load() }          // 첫 진입 시 로드
            .refreshable { await load() }   // 당겨서 새로고침
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

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
