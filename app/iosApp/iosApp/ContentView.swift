import SwiftUI
import SharedLogic

// 1.1c — 백엔드 /quote 를 호출해 삼성전기(009150) 현재가를 띄우는 첫 화면.
// sharedLogic(EdgeApi)이 백엔드를 호출하고, 여기선 그 결과만 표시한다.
struct ContentView: View {
    @State private var quote: Quote?
    @State private var errorText: String?
    @State private var loading = false

    // iOS 시뮬레이터는 localhost 가 맥의 백엔드를 그대로 가리킨다.
    private let api = EdgeApi(baseUrl: "http://localhost:8080")
    private let code = "009150" // 삼성전기 (지금은 하드코딩, 1.3에서 관심종목으로 확장)

    var body: some View {
        VStack(spacing: 16) {
            Text("삼성전기 \(code)")
                .font(.headline)
                .foregroundColor(.secondary)

            if loading {
                ProgressView()
            } else if let q = quote {
                Text("\(q.price) 원")
                    .font(.system(size: 44, weight: .bold))
                // 한국 관례: 상승=빨강, 하락=파랑
                Text("\(q.change >= 0 ? "▲" : "▼") \(abs(q.change))  (\(String(format: "%.2f", q.changeRate))%)")
                    .font(.title3)
                    .foregroundColor(q.change >= 0 ? .red : .blue)
            } else if let e = errorText {
                Text(e)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }

            Button("새로고침") {
                Task { await load() }
            }
            .padding(.top, 8)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await load() } // 화면 진입 시 자동 로드
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
