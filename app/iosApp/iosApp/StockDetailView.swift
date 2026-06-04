import SwiftUI
import SharedLogic

// 관심종목 리스트에서 종목을 탭하면 들어오는 상세 화면.
// 리스트가 이미 받아둔 Quote(스냅샷)로 즉시 표시하고, 진입 시 한 번 최신가로 갱신한다.
// 1.5: 내 포지션(평단가·수량·목표·손절)을 표시하고 현재가로 수익률을 계산한다(편집은 시트).
struct StockDetailView: View {
    @State private var item: WatchItem          // 포지션 편집 결과를 반영하려 가변
    private let api: EdgeApi
    @State private var quote: Quote?
    @State private var loading = false
    @State private var showEdit = false

    init(item: WatchItem, quote: Quote?, api: EdgeApi) {
        _item = State(initialValue: item)
        self.api = api
        _quote = State(initialValue: quote) // 리스트가 받아둔 시세로 초기화(바로 보이게)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text(item.code).font(.caption).foregroundColor(.secondary)

                if let q = quote {
                    priceHeader(q)   // 현재가 + 등락
                    detailCard(q)    // 거래량·시고저·52주
                    positionCard(q)  // 내 포지션 + 수익률(1.5)
                } else if loading {
                    ProgressView().padding(.top, 40)
                }
            }
            .padding()
        }
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showEdit = true } label: { Image(systemName: "square.and.pencil") }
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
        .task { await load() } // 진입 시 최신가로 갱신
        .sheet(isPresented: $showEdit) {
            PositionEditView(item: item) { updated in item = updated }  // 저장 시 화면 즉시 반영
        }
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
        .cardStyle()
    }

    // 내 포지션 카드(1.5b/c). 평단가·수량이 있으면 현재가로 수익률/평가손익 계산, 목표·손절은 거리(%)와 도달 여부.
    @ViewBuilder
    private func positionCard(_ q: Quote) -> some View {
        let price = Double(q.price)
        VStack(spacing: 0) {
            HStack {
                Text("내 포지션").font(.subheadline.weight(.semibold))
                Spacer()
                Button { showEdit = true } label: {
                    Text(item.avgPrice == nil ? "입력" : "수정").font(.caption)
                }
            }
            .padding(.vertical, 8)

            if let avgNum = item.avgPrice, let qtyNum = item.qty {
                Divider()
                let avg = avgNum.doubleValue
                let qty = Double(qtyNum.int64Value)
                let pnl = (price - avg) * qty                  // 평가손익
                let rate = avg == 0 ? 0 : (price - avg) / avg * 100   // 수익률 %
                let up = pnl >= 0
                row("평단가", "\(Int(avg).formatted()) 원")
                row("수량", "\(qtyNum.int64Value.formatted()) 주")
                row("평가금액", "\(Int(price * qty).formatted()) 원")
                coloredRow("평가손익", "\(up ? "+" : "")\(Int(pnl).formatted()) 원", up)
                coloredRow("수익률", "\(up ? "+" : "")\(String(format: "%.2f", rate))%", up)
            } else {
                Text("평단가·수량을 입력하면 내 수익률을 보여줘요")
                    .font(.footnote).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            }

            if let t = item.targetPrice?.doubleValue {
                Divider()
                let gap = (t - price) / price * 100   // 현재가 대비 남은 거리
                let reached = price >= t
                row("목표가", "\(Int(t).formatted()) 원  " + (reached ? "🎯 도달" : String(format: "(%+.1f%%)", gap)))
            }
            if let s = item.stopPrice?.doubleValue {
                let gap = (s - price) / price * 100
                let reached = price <= s
                row("손절가", "\(Int(s).formatted()) 원  " + (reached ? "⚠️ 도달" : String(format: "(%+.1f%%)", gap)))
            }
        }
        .cardStyle()
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .padding(.vertical, 8)
    }

    // 손익/수익률처럼 부호에 따라 색이 바뀌는 행(상승=빨강·하락=파랑).
    private func coloredRow(_ label: String, _ value: String, _ up: Bool) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.semibold).foregroundColor(up ? .red : .blue)
        }
        .padding(.vertical, 8)
    }

    private func load() async {
        loading = true
        if let q = try? await api.getQuote(code: item.code) { quote = q }
        loading = false
    }
}

// 카드 공통 스타일(상세·포지션 카드 공유).
private extension View {
    func cardStyle() -> some View {
        self
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(Color(.secondarySystemBackground))
            .cornerRadius(12)
    }
}
