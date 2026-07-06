import SwiftUI
import SharedLogic

// 앱 전역 싱글톤. DB는 한 번만 열고, api 인스턴스도 공유한다.
enum Db {
    static let watchlist: WatchlistRepository = {
        // 첫 실행은 빈 관심종목으로 시작한다(시드 없음). 사용자가 검색으로 직접 추가.
        WatchlistRepository(driverFactory: DriverFactory())
    }()
    static let actionLog = ActionLogRepository(driverFactory: DriverFactory())
    static let holding = HoldingRepository(driverFactory: DriverFactory())
    // 백엔드 주소·토큰. Debug(Xcode 실행)는 항상 로컬 백엔드, Release(Archive/TestFlight)는 운영(Cloud Run).
    // → 개발 중엔 재배포 없이 `cd backend && ./run.sh` 만 띄우면 시뮬레이터가 그 로컬 서버를 본다.
    // 운영 URL·토큰은 Secrets.xcconfig → Info.plist(EDGE_BASE_URL/EDGE_API_TOKEN)에서 읽는다.
    static let api: EdgeApi = {
        let info = Bundle.main.infoDictionary
        let url = (info?["EDGE_BASE_URL"] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        let token = (info?["EDGE_API_TOKEN"] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        // 시뮬레이터(Debug)만 로컬 백엔드(localhost=맥) → run.sh 로 백엔드 반복개발.
        // 실기기·Release 는 운영(Cloud Run HTTPS) → 어느 폰·WiFi 에서도 그냥 동작(LAN/mDNS 불필요).
        #if DEBUG && targetEnvironment(simulator)
        let base = "http://localhost:8080"
        #else
        let base = url.isEmpty ? "http://localhost:8080" : url
        #endif
        return EdgeApi(baseUrl: base, apiToken: token)
    }()
}

// 관심종목 탭. DB에서 종목 목록을 읽고 백엔드 /quotes로 라이브 시세를 합쳐 표시.
struct WatchlistView: View {
    @State private var watchlist: [WatchItem] = []
    @State private var quotes: [String: Quote] = [:]
    @State private var supplyBadges: [String: [String]] = [:]  // code → ["외인 3일↑", ...]
    @State private var sparklines: [String: [Double]] = [:]   // code → 7일 종가(오래된→최신)
    @State private var errorText: String?
    @State private var loading = false
    @State private var showSearch = false
    @State private var lastUpdated: Date?

    private let api = Db.api

    var body: some View {
        NavigationStack {
            Group {
                if watchlist.isEmpty {
                    emptyState
                } else {
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
                    .contentMargins(.top, 0, for: .scrollContent)
                }
            }
            .navigationTitle("관심종목")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showSearch = true } label: {
                        Image(systemName: "plus")
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
            .task { await load() }          // 첫 진입 시 로드
            .refreshable { await load() }   // 당겨서 새로고침
            // 검색 시트. 닫히면 DB를 다시 읽어 새로 추가한 종목이 리스트에 반영된다.
            .sheet(isPresented: $showSearch, onDismiss: { Task { await load() } }) {
                SearchView(api: api)
            }
        }
    }

    // 관심종목이 하나도 없을 때: 안내 + 추가 버튼.
    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "star").font(.system(size: 40)).foregroundColor(.secondary)
            Text("관심종목이 없어요")
                .font(.headline).foregroundColor(.secondary)
            Text("종목을 검색해 관심종목에 추가하면\n시세·수급·AI 분석을 한눈에 볼 수 있어요")
                .font(.caption).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Button { showSearch = true } label: {
                Label("종목 추가", systemImage: "plus")
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                let isUp = q.changeRate > 0; let isDown = q.changeRate < 0
                let chgColor: Color = isUp ? .red : isDown ? .blue : .secondary
                let symbol = isUp ? "▲" : isDown ? "▼" : "—"
                let pts = sparklines[item.code]
                let hasStreak = (isUp || isDown) && pts != nil && pts!.count >= 2
                let streak = hasStreak ? consecutiveStreak(closes: pts!, todayUp: isUp) : 0
                // 이모지 열(고정 너비) + 가격 열(고정 너비) — 너비 고정이어야 행마다 위치가 동일.
                if hasStreak {
                    VStack(alignment: .center, spacing: 2) {
                        Text(isUp ? "📈" : "📉").font(.system(size: 14))
                        Text("\(streak)일째 \(isUp ? "상승" : "하락")")
                            .font(.system(size: 10))
                            .foregroundColor(chgColor)
                    }
                    .frame(width: 62)
                }
                VStack(alignment: .trailing, spacing: 2) {
                    Text(q.price.formatted()).font(.body.weight(.semibold))
                    Text("\(symbol) \(String(format: "%.2f", abs(q.changeRate)))%")
                        .font(.caption)
                        .foregroundColor(chgColor)
                }
                .frame(width: 90, alignment: .trailing)
            } else {
                Text("—").foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    // 오늘 방향(todayUp)과 같은 방향으로 연속된 거래일 수. 최대 보유 데이터(7일) 한계.
    private func consecutiveStreak(closes: [Double], todayUp: Bool) -> Int {
        var count = 1
        for i in stride(from: closes.count - 1, through: 1, by: -1) {
            if (closes[i] >= closes[i - 1]) == todayUp { count += 1 } else { break }
        }
        return min(count, 7)
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
            lastUpdated = Date()
        } catch {
            errorText = "불러오기 실패: \(error.localizedDescription)\n(백엔드 실행 확인: cd backend && ./run.sh)"
        }
        loading = false
        Task { await loadSupplyBadges(items: items) }
        Task { await loadSparklines(items: items) }
    }

    // 스파크라인: 종목별 7일 종가를 병렬로 가져온다. 실패 종목은 조용히 skip.
    private func loadSparklines(items: [WatchItem]) async {
        var result: [String: [Double]] = [:]
        await withTaskGroup(of: (String, [Double]).self) { group in
            for item in items {
                group.addTask {
                    guard let bars = try? await self.api.getDaily(code: item.code, bars: 8),
                          bars.count >= 2 else { return (item.code, []) }
                    // 오늘 장중 바 제외: KIS는 오늘 날짜 바를 포함해서 내려줌.
                    // 오늘 방향은 실시간 changeRate(todayUp)에서 별도로 쓰므로 여기선 과거 종가만 사용.
                    let todayStr = {
                        let f = DateFormatter(); f.dateFormat = "yyyyMMdd"; return f.string(from: Date())
                    }()
                    let pastBars = bars.filter { $0.date != todayStr }
                    guard pastBars.count >= 2 else { return (item.code, []) }
                    // 최신일이 앞 → reverse해서 오래된→최신(어제) 순으로
                    let closes = Array(pastBars.reversed().suffix(7).map { Double($0.close) })
                    return (item.code, closes)
                }
            }
            for await (code, closes) in group {
                if !closes.isEmpty { result[code] = closes }
            }
        }
        sparklines = result
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

// 갱신 시각 포맷 — "9:32" 형태(오전/오후 생략, 짧게).
func shortTime(_ date: Date) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ko_KR")
    f.dateFormat = "h:mm"
    return f.string(from: date)
}

// 분석 모드 — 전역 설정값. 백엔드로 넘기는 쿼리값과 동일한 문자열을 그대로 저장한다.
// 슬라이스 4는 market-mood 한 곳에만 적용, 슬라이스 5에서 종목상세·macro-impact로 확대 예정.
enum AnalysisMode: String, CaseIterable {
    case defensive   // 방어적: 사실 + 방향만
    case aggressive  // 공격적: 시장 스탠스 의견까지

    var label: String { self == .aggressive ? "공격 ⚔️" : "방어 🛡️" }
}

// @AppStorage 키. SettingsView와 BriefingView가 같은 값을 공유한다.
let analysisModeKey = "analysisMode"

enum ThemeMode: String, CaseIterable {
    case system, light, dark
    var label: String {
        switch self {
        case .system: return "시스템"
        case .light:  return "라이트"
        case .dark:   return "다크"
        }
    }
    var colorScheme: ColorScheme? {
        switch self {
        case .light: return .light
        case .dark:  return .dark
        case .system: return nil
        }
    }
}

let themeModeKey = "themeMode"

struct ContentView: View {
    @AppStorage(themeModeKey) private var themeModeRaw = ThemeMode.system.rawValue
    private var themeMode: ThemeMode { ThemeMode(rawValue: themeModeRaw) ?? .system }

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
            SettingsView()
                .tabItem { Label("설정", systemImage: "gearshape") }
        }
        .preferredColorScheme(themeMode.colorScheme)
    }
}

// 설정 탭. 전역 @AppStorage라 어느 화면에서든 같은 값을 읽는다.
struct SettingsView: View {
    @AppStorage(analysisModeKey) private var modeRaw = AnalysisMode.defensive.rawValue
    @AppStorage(themeModeKey) private var themeModeRaw = ThemeMode.system.rawValue

    private var mode: AnalysisMode { AnalysisMode(rawValue: modeRaw) ?? .defensive }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("분석 모드", selection: $modeRaw) {
                        ForEach(AnalysisMode.allCases, id: \.rawValue) { m in
                            Text(m.label).tag(m.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("분석 모드")
                } footer: {
                    if mode == .aggressive {
                        Text("⚔️ 공격적 모드는 계산된 지표에 근거한 단호한 의견을 제시해요. 브리핑에서는 포트폴리오 스탠스(비중 조절·현금 확보 등), 종목상세에서는 평단 손익·신호·밸류 위치를 근거로 개별 종목 매매 판단까지 포함돼요. 참고용이며 투자 책임은 본인에게 있어요.")
                    } else {
                        Text("🛡️ 방어적 모드는 사실과 방향만 담백하게 전달해요. 적극적인 시장 스탠스 의견을 보려면 공격으로 바꿔보세요.")
                    }
                }

                Section("테마") {
                    Picker("테마", selection: $themeModeRaw) {
                        ForEach(ThemeMode.allCases, id: \.rawValue) { m in
                            Text(m.label).tag(m.rawValue)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("앱 정보") {
                    LabeledContent("버전", value: "1.0")
                    LabeledContent("데이터 출처", value: "한투 API · DART · 네이버 뉴스")
                }
            }
            .contentMargins(.top, 0, for: .scrollContent)
            .navigationTitle("설정")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
