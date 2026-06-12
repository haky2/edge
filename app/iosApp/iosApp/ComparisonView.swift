import SwiftUI
import SharedLogic

// 두 종목 나란히 비교 화면.
// 핵심 지표 테이블 + Claude 비교 코멘트(어느 쪽이 더 나아 보이는지 결론).
struct ComparisonView: View {
    let itemA: WatchItem
    let itemB: WatchItem
    private let api: EdgeApi

    @State private var comparison: Comparison?
    @State private var loading = false
    @State private var errorText: String?
    @AppStorage(analysisModeKey) private var modeRaw = AnalysisMode.defensive.rawValue
    private var mode: AnalysisMode { AnalysisMode(rawValue: modeRaw) ?? .defensive }

    init(itemA: WatchItem, itemB: WatchItem, api: EdgeApi = Db.api) {
        self.itemA = itemA
        self.itemB = itemB
        self.api = api
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // ── 헤더: 두 종목 이름 ──
                HStack {
                    Text(itemA.name).font(.headline).frame(maxWidth: .infinity)
                    Text("vs").font(.caption).foregroundColor(.secondary)
                    Text(itemB.name).font(.headline).frame(maxWidth: .infinity)
                }
                .padding(.top, 8)

                if loading {
                    ProgressView("비교 분석 중...")
                        .padding(.top, 40)
                } else if let c = comparison {
                    metricsTable(c)
                    commentCard(c)
                } else if let e = errorText {
                    Text(e).font(.footnote).foregroundColor(.secondary).padding()
                }
            }
            .padding()
        }
        .navigationTitle("종목 비교")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { Task { await load(force: true) } } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .help("비교 재생성")
                .disabled(loading)
            }
        }
        .task { await load() }
    }

    // ── 핵심 지표 나란히 테이블 ──
    private func metricsTable(_ c: Comparison) -> some View {
        VStack(spacing: 0) {
            headerRow(c.a.name, c.b.name)
            Divider()
            metricRow("현재가",
                      left: "\(formatPrice(c.a.price))원",
                      right: "\(formatPrice(c.b.price))원",
                      leftSub: rateText(c.a.changeRate),
                      rightSub: rateText(c.b.changeRate),
                      leftColor: rateColor(c.a.changeRate),
                      rightColor: rateColor(c.b.changeRate))
            Divider()
            metricRow("52주 위치",
                      left: "\(Int(c.a.week52PosPct))%",
                      right: "\(Int(c.b.week52PosPct))%",
                      leftSub: week52Label(c.a.week52PosPct),
                      rightSub: week52Label(c.b.week52PosPct))
            Divider()
            if c.a.per > 0 || c.b.per > 0 {
                metricRow("PER / PBR",
                          left: c.a.per > 0 ? "\(c.a.per)배 / \(c.b.per > 0 ? c.b.per : 0)배" : "-",
                          right: c.b.per > 0 ? "\(c.b.per)배 / \(c.b.pbr > 0 ? c.b.pbr : 0)배" : "-",
                          leftSub: c.a.valuationLabel,
                          rightSub: c.b.valuationLabel,
                          leftColor: valuationColor(c.a.valuationLabel),
                          rightColor: valuationColor(c.b.valuationLabel))
                Divider()
            }
            if c.a.upsidePct != nil || c.b.upsidePct != nil {
                metricRow("목표가 괴리",
                          left: upsideText(c.a.upsidePct?.doubleValue),
                          right: upsideText(c.b.upsidePct?.doubleValue),
                          leftColor: upsideColor(c.a.upsidePct?.doubleValue),
                          rightColor: upsideColor(c.b.upsidePct?.doubleValue))
                Divider()
            }
            metricRow("외인 3일",
                      left: flowText(c.a.foreignNet3d),
                      right: flowText(c.b.foreignNet3d),
                      leftColor: flowColor(c.a.foreignNet3d),
                      rightColor: flowColor(c.b.foreignNet3d))
            Divider()
            metricRow("기관 3일",
                      left: flowText(c.a.institutionNet3d),
                      right: flowText(c.b.institutionNet3d),
                      leftColor: flowColor(c.a.institutionNet3d),
                      rightColor: flowColor(c.b.institutionNet3d))
            if c.a.quarterlyYoy != nil || c.b.quarterlyYoy != nil {
                Divider()
                metricRow("분기순익 YoY",
                          left: yoyText(c.a.quarterlyYoy?.doubleValue),
                          right: yoyText(c.b.quarterlyYoy?.doubleValue),
                          leftColor: yoyColor(c.a.quarterlyYoy?.doubleValue),
                          rightColor: yoyColor(c.b.quarterlyYoy?.doubleValue))
            }
        }
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func headerRow(_ nameA: String, _ nameB: String) -> some View {
        HStack {
            Text(nameA)
                .font(.caption.weight(.semibold))
                .frame(maxWidth: .infinity)
            Text("지표").font(.caption2).foregroundColor(.secondary).frame(width: 70)
            Text(nameB)
                .font(.caption.weight(.semibold))
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private func metricRow(
        _ label: String,
        left: String,
        right: String,
        leftSub: String? = nil,
        rightSub: String? = nil,
        leftColor: Color = .primary,
        rightColor: Color = .primary
    ) -> some View {
        HStack(alignment: .center) {
            VStack(spacing: 2) {
                Text(left).font(.callout).foregroundColor(leftColor).frame(maxWidth: .infinity)
                if let s = leftSub { Text(s).font(.caption2).foregroundColor(.secondary).frame(maxWidth: .infinity) }
            }
            Text(label).font(.caption2).foregroundColor(.secondary).frame(width: 70).multilineTextAlignment(.center)
            VStack(spacing: 2) {
                Text(right).font(.callout).foregroundColor(rightColor).frame(maxWidth: .infinity)
                if let s = rightSub { Text(s).font(.caption2).foregroundColor(.secondary).frame(maxWidth: .infinity) }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 7)
    }

    // ── Claude 비교 코멘트 카드 ──
    private func commentCard(_ c: Comparison) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "sparkles").foregroundColor(.purple)
                Text("비교 분석").font(.headline)
                if mode == .aggressive {
                    Text("⚔️ 공격적 모드").font(.caption).foregroundColor(.orange)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.orange.opacity(0.12)).clipShape(Capsule())
                }
                Spacer()
                if !c.generatedAt.isEmpty {
                    Text("오늘 \(c.generatedAt) 생성").font(.caption2).foregroundColor(.secondary)
                }
            }
            Divider()
            proseBlock(c.comment)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func proseBlock(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 2)
                .fill(Color.purple.opacity(0.35))
                .frame(width: 3)
            VStack(alignment: .leading, spacing: 12) {
                ForEach(text.components(separatedBy: "\n\n"), id: \.self) { para in
                    let t = para.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty && t != "---" {
                        Text(markdown(t))
                            .font(.callout)
                            .lineSpacing(5)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }

    // ── 데이터 로드 ──
    private func load(force: Bool = false) async {
        loading = true
        errorText = nil
        do {
            comparison = try await api.getComparison(
                codeA: itemA.code,
                codeB: itemB.code,
                mode: mode.rawValue,
                refresh: force
            )
        } catch {
            errorText = "불러오기 실패: \(error.localizedDescription)"
        }
        loading = false
    }

    // ── 포맷 헬퍼 ──
    private func formatPrice(_ p: Int64) -> String {
        let n = NumberFormatter()
        n.numberStyle = .decimal
        return n.string(from: NSNumber(value: p)) ?? "\(p)"
    }

    private func rateText(_ r: Double) -> String {
        "\(r >= 0 ? "+" : "")\(String(format: "%.2f", r))%"
    }

    private func rateColor(_ r: Double) -> Color {
        r > 0 ? .red : r < 0 ? .blue : .secondary
    }

    private func week52Label(_ pct: Double) -> String {
        switch pct {
        case ..<20: "저점권"
        case ..<40: "저중간"
        case ..<60: "중간"
        case ..<80: "고중간"
        default: "고점권"
        }
    }

    private func valuationColor(_ label: String?) -> Color {
        guard let l = label else { return .primary }
        if l.contains("저평가") { return .blue }
        if l.contains("고평가") { return .red }
        return .primary
    }

    private func upsideText(_ pct: Double?) -> String {
        guard let p = pct else { return "-" }
        return "\(p >= 0 ? "+" : "")\(String(format: "%.1f", p))%"
    }

    private func upsideColor(_ pct: Double?) -> Color {
        guard let p = pct else { return .secondary }
        return p >= 5 ? .red : p <= -5 ? .blue : .primary
    }

    private func flowText(_ v: Int64) -> String {
        if v == 0 { return "보합" }
        let abs = Swift.abs(v)
        let sign = v > 0 ? "+" : "-"
        if abs >= 1_000_000 { return "\(sign)\(String(format: "%.0f", Double(abs) / 10000))만" }
        if abs >= 10_000 { return "\(sign)\(abs / 10000)만" }
        return "\(sign)\(abs)"
    }

    private func flowColor(_ v: Int64) -> Color {
        v > 0 ? .red : v < 0 ? .blue : .secondary
    }

    private func yoyText(_ pct: Double?) -> String {
        guard let p = pct else { return "-" }
        return "\(p >= 0 ? "+" : "")\(String(format: "%.1f", p))%"
    }

    private func yoyColor(_ pct: Double?) -> Color {
        guard let p = pct else { return .secondary }
        return p > 10 ? .red : p < -10 ? .blue : .primary
    }

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

// 종목 비교 대상을 관심종목에서 선택하는 시트.
struct ComparePickerView: View {
    let currentCode: String
    let watchlist: [WatchItem]
    var onSelect: (WatchItem) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(watchlist.filter { $0.code != currentCode }, id: \.code) { item in
                Button {
                    onSelect(item)
                    dismiss()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name).foregroundColor(.primary)
                        Text(item.code).font(.caption2).foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("비교할 종목 선택")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
            }
        }
    }
}
