import SwiftUI
import SharedLogic

struct OverseasDetailView: View {
    let item: WatchItem
    let api: EdgeApi

    @State private var quote: OverseasQuote?
    @State private var loading = false
    @State private var errorText: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if let e = errorText {
                    Text(e).font(.footnote).foregroundColor(.secondary)
                        .padding()
                }
                if let q = quote {
                    priceHeader(q)
                    statsCard(q)
                } else if loading {
                    ProgressView().padding(.top, 40)
                }
            }
            .padding(.horizontal)
            .padding(.top, 12)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    // 가격 헤더 카드 — 현재가·등락·배지
    @ViewBuilder
    private func priceHeader(_ q: OverseasQuote) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 6) {
                Text(priceText(q))
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                if q.delayed {
                    Text("15분 지연")
                        .font(.system(size: 11, weight: .medium))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.orange.opacity(0.15))
                        .foregroundColor(.orange)
                        .clipShape(Capsule())
                }
                Text(q.currency)
                    .font(.system(size: 11, weight: .medium))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.12))
                    .foregroundColor(.secondary)
                    .clipShape(Capsule())
                Spacer()
            }
            let isUp = q.changeRate > 0; let isDown = q.changeRate < 0
            let chgColor: Color = isUp ? .red : isDown ? .blue : .secondary
            let arrow = isUp ? "▲" : isDown ? "▼" : "—"
            HStack {
                Text("\(arrow) \(String(format: "%.2f", abs(q.change))) (\(String(format: "%.2f", abs(q.changeRate)))%)")
                    .font(.body)
                    .foregroundColor(chgColor)
                Spacer()
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // 52주 통계 카드
    @ViewBuilder
    private func statsCard(_ q: OverseasQuote) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("주요 지표").font(.headline)
            Divider()
            stat("시가", priceText(q, price: q.open))
            stat("고가", priceText(q, price: q.high))
            stat("저가", priceText(q, price: q.low))
            Divider()
            stat("52주 고점", priceText(q, price: q.high52w))
            stat("52주 저점", priceText(q, price: q.low52w))
            stat("거래량", volumeText(q.volume))
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    @ViewBuilder
    private func stat(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .font(.subheadline)
    }

    private func priceText(_ q: OverseasQuote, price: Double? = nil) -> String {
        let p = price ?? q.price
        let sym = q.currency == "USD" ? "$" : "\(q.currency) "
        let digits = p < 10 ? 4 : p < 100 ? 3 : 2
        return "\(sym)\(String(format: "%.\(digits)f", p))"
    }

    private func volumeText(_ vol: Int64) -> String {
        if vol >= 1_000_000 { return String(format: "%.1fM", Double(vol) / 1_000_000) }
        if vol >= 1_000 { return String(format: "%.1fK", Double(vol) / 1_000) }
        return "\(vol)"
    }

    private func load() async {
        loading = true
        errorText = nil
        quote = (try? await api.getOverseasQuote(code: item.code))
        if quote == nil {
            errorText = "불러오기 실패 — 백엔드 확인: cd backend && ./run.sh"
        }
        loading = false
    }
}
