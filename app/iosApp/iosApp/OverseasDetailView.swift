import SwiftUI
import SharedLogic

struct OverseasDetailView: View {
    let item: WatchItem
    let api: EdgeApi

    @State private var quote: OverseasQuote?
    @State private var loading = false
    @State private var errorText: String?
    @State private var analysis: Analysis?
    @State private var analyzing = false

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
                aiCommentCard()
            }
            .padding(.horizontal)
            .padding(.top, 12)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .task { await loadAnalysis() }
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

    // AI 코멘트 — 시세(15분 지연)+뉴스만 근거. 백엔드가 당일 공유 캐시라 재진입은 즉시.
    private func loadAnalysis() async {
        analyzing = true
        analysis = (try? await api.getOverseasAnalysis(code: item.code))
        analyzing = false
    }

    @ViewBuilder
    private func aiCommentCard() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "sparkles").foregroundColor(.purple)
                Text("AI 코멘트").font(.subheadline.weight(.semibold))
                Text("시세·뉴스 기반")
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.12))
                    .foregroundColor(.secondary)
                    .clipShape(Capsule())
                Spacer()
                if analyzing { ProgressView().scaleEffect(0.8) }
            }

            if let a = analysis {
                if let summary = a.summary, !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 4) {
                            Image(systemName: "pin.fill").font(.caption2)
                            Text("핵심 요약").font(.caption.weight(.bold))
                        }
                        .foregroundColor(.purple)
                        Text(markdown(summary))
                            .font(.callout)
                            .lineSpacing(5)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.purple.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .padding(.bottom, 4)
                }

                HStack(alignment: .top, spacing: 10) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.purple.opacity(0.35))
                        .frame(width: 3)
                    VStack(alignment: .leading, spacing: 12) {
                        let paragraphs = a.comment
                            .components(separatedBy: "\n\n")
                            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                            .filter { !$0.isEmpty }
                        ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, p in
                            Text(markdown(p))
                                .font(.callout)
                                .lineSpacing(5)
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("참고용 · \(a.date) \(a.generatedAt) 생성 · 국내 종목과 달리 수급·공시 근거 없음")
                        .font(.caption2).foregroundColor(.secondary)
                    Text("투자 판단과 책임은 본인에게 있습니다")
                        .font(.caption2).foregroundColor(.secondary)
                }
                .padding(.top, 2)
            } else if analyzing {
                Text("시세·뉴스를 종합해 코멘트를 생성하고 있어요…")
                    .font(.footnote).foregroundColor(.secondary)
            } else {
                Text("코멘트를 불러오지 못했어요.")
                    .font(.footnote).foregroundColor(.secondary)
                Button {
                    Task { await loadAnalysis() }
                } label: {
                    Label("다시 시도", systemImage: "arrow.clockwise")
                        .font(.caption)
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // StockDetailView.markdown과 동일 — SwiftUI 기본 마크다운 파서는 **굵게** 뒤에
    // 한글이 붙으면 별표를 남기는 버그가 있어 직접 파싱한다.
    private func markdown(_ s: String) -> AttributedString {
        var text = s.replacingOccurrences(of: #"~~(.+?)~~"#, with: "$1", options: .regularExpression)
        text = text.replacingOccurrences(of: "~~", with: "")

        guard let regex = try? NSRegularExpression(pattern: #"\*\*(.+?)\*\*"#) else {
            return AttributedString(text.replacingOccurrences(of: "**", with: ""))
        }
        let ns = text as NSString
        var out = AttributedString()
        var cursor = 0
        for m in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            if m.range.location > cursor {
                let plain = ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor))
                out += AttributedString(plain.replacingOccurrences(of: "**", with: ""))
            }
            var bold = AttributedString(ns.substring(with: m.range(at: 1)))
            bold.inlinePresentationIntent = .stronglyEmphasized
            out += bold
            cursor = m.range.location + m.range.length
        }
        if cursor < ns.length {
            out += AttributedString(ns.substring(from: cursor).replacingOccurrences(of: "**", with: ""))
        }
        return out
    }
}
