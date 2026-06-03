import SwiftUI
import SharedLogic

// 관심종목 리스트에서 종목을 탭하면 들어오는 상세 화면.
// 리스트가 이미 받아둔 Quote(스냅샷)로 즉시 표시하고, 진입 시 한 번 최신가로 갱신한다.
struct StockDetailView: View {
    let item: WatchItem
    private let api: EdgeApi
    @State private var quote: Quote?
    @State private var loading = false

    init(item: WatchItem, quote: Quote?, api: EdgeApi) {
        self.item = item
        self.api = api
        _quote = State(initialValue: quote) // 리스트가 받아둔 시세로 초기화(바로 보이게)
    }

    var body: some View {
        VStack(spacing: 20) {
            Text(item.code).font(.caption).foregroundColor(.secondary)

            if let q = quote {
                priceHeader(q)   // 현재가 + 등락
                detailCard(q)    // 거래량·시고저·52주
            } else if loading {
                ProgressView().padding(.top, 40)
            }
            Spacer()
        }
        .padding()
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
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
        .task { await load() } // 진입 시 최신가로 갱신
    }

    // 현재가 + 전일대비. 한국 관례: 상승=빨강, 하락=파랑.
    private func priceHeader(_ q: Quote) -> some View {
        let up = q.change >= 0
        return VStack(spacing: 6) {
            Text("\(q.price.formatted()) 원")
                .font(.system(size: 40, weight: .bold))
            Text("\(up ? "▲" : "▼") \(abs(q.change).formatted())  \(String(format: "%.2f", abs(q.changeRate)))%")
                .font(.headline)
                .foregroundColor(up ? .red : .blue)
        }
    }

    // 상세 지표 카드
    private func detailCard(_ q: Quote) -> some View {
        VStack(spacing: 0) {
            row("거래량", q.volume.formatted())
            Divider()
            row("시가", q.open.formatted())
            row("고가", q.high.formatted())
            row("저가", q.low.formatted())
            Divider()
            row("52주 최고", q.high52w.formatted())
            row("52주 최저", q.low52w.formatted())
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .padding(.vertical, 8)
    }

    private func load() async {
        loading = true
        if let q = try? await api.getQuote(code: item.code) { quote = q }
        loading = false
    }
}
