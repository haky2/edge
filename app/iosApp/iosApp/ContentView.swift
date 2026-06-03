import SwiftUI
import SharedLogic

// 1.2 — Quote 가 가진 시세 상세(거래량·시고저·52주)를 카드로 표시.
// 데이터는 백엔드가 이미 다 내려주므로 여기선 화면(SwiftUI)만 채운다.
struct ContentView: View {
    @State private var quote: Quote?
    @State private var errorText: String?
    @State private var loading = false

    // iOS 시뮬레이터는 localhost 가 맥의 백엔드를 그대로 가리킨다.
    private let api = EdgeApi(baseUrl: "http://localhost:8080")
    private let code = "009150" // 삼성전기 (1.3에서 관심종목 리스트로 확장)

    var body: some View {
        VStack(spacing: 20) {
            // 종목 헤더
            VStack(spacing: 4) {
                Text("삼성전기").font(.title2.bold())
                Text(code).font(.caption).foregroundColor(.secondary)
            }

            if loading && quote == nil {
                ProgressView().padding(.top, 40)
            } else if let q = quote {
                priceHeader(q)   // 현재가 + 등락
                detailCard(q)    // 거래량·시고저·52주
            } else if let e = errorText {
                Text(e)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 20)
            }

            Spacer()

            Button { Task { await load() } } label: {
                Label("새로고침", systemImage: "arrow.clockwise")
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .task { await load() } // 화면 진입 시 자동 로드
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

    // "라벨 ........ 값" 한 줄
    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .padding(.vertical, 8)
    }

    // EdgeApi.getQuote 는 Kotlin suspend → Swift에서 async throws 로 호출.
    private func load() async {
        loading = true
        errorText = nil
        do {
            quote = try await api.getQuote(code: code)
        } catch {
            errorText = "불러오기 실패: \(error.localizedDescription)\n(백엔드가 켜져 있는지 확인: cd backend && ./run.sh)"
        }
        loading = false
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
