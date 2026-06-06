import SwiftUI
import SharedLogic

// Phase 4 — 내 투자 패턴 통계.
// action_log(로컬 SQLDelight)를 전부 읽어 앱에서 집계. 백엔드는 현재가·일봉 조회에만 사용.
struct StatsView: View {
    private let logRepo   = Db.actionLog
    private let watchRepo = Db.watchlist
    private let api       = Db.api

    @State private var entries: [ActionLogEntry] = []
    @State private var nameMap: [String: String] = [:]    // code → 종목명
    @State private var missedRows: [MissedRow] = []
    @State private var missedLoading = false

    var body: some View {
        NavigationStack {
            Group {
                if entries.isEmpty {
                    emptyView
                } else {
                    contentList
                }
            }
            .navigationTitle("내 패턴")
            .onAppear { reload() }
        }
    }

    // MARK: - 메인 리스트

    private var contentList: some View {
        List {
            summarySection
            if avgHoldDays != nil || !pairRows.isEmpty { holdSection }
            missedSection
            if !reasonRows.isEmpty { reasonSection }
            codeSection
            recentSection
        }
    }

    // MARK: - 섹션: 요약

    private var summarySection: some View {
        Section {
            HStack(spacing: 0) {
                summaryCell(count: buyCount,      label: "매수",  color: .red)
                Divider().frame(height: 36)
                summaryCell(count: sellCount,     label: "매도",  color: .blue)
                Divider().frame(height: 36)
                summaryCell(count: interestCount, label: "관심", color: .orange)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
        } header: {
            Text("전체 \(entries.count)건")
        }
    }

    private func summaryCell(count: Int, label: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(count)").font(.title2.weight(.semibold)).foregroundColor(color)
            Text(label).font(.caption).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - 섹션: 보유기간

    private var holdSection: some View {
        Section("매수 → 매도 보유기간") {
            if let avg = avgHoldDays {
                HStack {
                    Text("평균 보유기간")
                    Spacer()
                    Text(holdLabel(avg)).fontWeight(.semibold)
                    Text("(\(pairRows.count)쌍 기준)").font(.caption).foregroundColor(.secondary)
                }
            }
            ForEach(pairRows, id: \.id) { row in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(nameMap[row.code] ?? row.code).font(.body)
                        Text("\(shortDate(row.buyAt)) → \(shortDate(row.sellAt))")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    Text(holdLabel(Double(row.days))).font(.caption.weight(.semibold))
                        .foregroundColor(row.days <= 7 ? .orange : .secondary)
                }
                .padding(.vertical, 2)
            }
        }
    }

    // MARK: - 섹션: 놓친 종목

    @ViewBuilder
    private var missedSection: some View {
        Section {
            if missedLoading {
                HStack {
                    ProgressView().scaleEffect(0.8)
                    Text("현재가 확인 중…").font(.footnote).foregroundColor(.secondary)
                }
            } else if missedRows.isEmpty {
                Text("없음 (관심 후 미매수 종목 없어요)").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(missedRows) { row in
                    missedRow(row)
                }
            }
        } header: {
            Text("놓친 종목 (관심 후 미매수 \(missedRows.count)개)")
        } footer: {
            Text("관심 기록은 있지만 매수 로그가 없는 종목.")
                .font(.caption2)
        }
    }

    private func missedRow(_ row: MissedRow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(row.name).font(.body)
                Spacer()
                if let ret = row.hypotheticalReturn {
                    Text((ret >= 0 ? "+" : "") + String(format: "%.1f%%", ret))
                        .font(.body.weight(.semibold))
                        .foregroundColor(ret >= 0 ? .red : .blue)
                }
            }
            HStack(spacing: 6) {
                Text("관심 \(shortDate(row.lastInterestAt))")
                    .font(.caption2).foregroundColor(.secondary)
                if let then = row.thenPrice {
                    Text("·").font(.caption2).foregroundColor(.secondary)
                    Text("당시 \(then.formatted())원")
                        .font(.caption2).foregroundColor(.secondary)
                } else {
                    Text("· 가격 미기록").font(.caption2).foregroundColor(.secondary)
                }
                if let now = row.currentPrice {
                    Text("→ 현재 \(now.formatted())원")
                        .font(.caption2).foregroundColor(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 섹션: 사유 분포

    private var reasonSection: some View {
        Section("사유 태그 (Top \(min(reasonRows.count, 8))개)") {
            ForEach(reasonRows.prefix(8), id: \.reason) { row in
                HStack {
                    Text(row.reason).font(.body)
                    Spacer()
                    Text("\(row.count)회").font(.caption.weight(.semibold)).foregroundColor(.secondary)
                    if let max = reasonRows.first?.count, max > 0 {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.purple.opacity(0.5))
                            .frame(width: CGFloat(row.count) / CGFloat(max) * 60, height: 6)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    // MARK: - 섹션: 종목별 활동

    private var codeSection: some View {
        Section("종목별 활동") {
            ForEach(codeRows, id: \.code) { row in
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(nameMap[row.code] ?? row.code).font(.body)
                        Text(row.code).font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    if row.buys > 0  { actionPill("\(row.buys)매수",  .red) }
                    if row.sells > 0 { actionPill("\(row.sells)매도", .blue) }
                    if row.interests > 0 { actionPill("\(row.interests)관심", .orange) }
                }
                .padding(.vertical, 2)
            }
        }
    }

    // MARK: - 섹션: 최근 활동

    private var recentSection: some View {
        Section("최근 활동") {
            ForEach(entries.prefix(20), id: \.id) { e in
                HStack(spacing: 8) {
                    actionPill(actionLabel(e.action), actionColor(e.action))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(nameMap[e.code] ?? e.code).font(.body)
                        if let r = e.reason { Text(r).font(.caption2).foregroundColor(.secondary) }
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(shortTs(e.createdAt)).font(.caption2).foregroundColor(.secondary)
                        if let p = e.price {
                            Text("\(p.int64Value.formatted())원")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    // MARK: - 빈 화면

    private var emptyView: some View {
        VStack(spacing: 12) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 48)).foregroundColor(.secondary.opacity(0.4))
            Text("기록이 없어요")
                .font(.headline).foregroundColor(.secondary)
            Text("종목 상세 화면에서 관심·매수·매도를\n기록하면 여기서 패턴을 볼 수 있어요.")
                .font(.callout).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }

    // MARK: - 집계 연산

    private var buyCount:      Int { entries.filter { $0.action == "buy" }.count }
    private var sellCount:     Int { entries.filter { $0.action == "sell" }.count }
    private var interestCount: Int { entries.filter { $0.action == "interest" }.count }

    private var reasonRows: [(reason: String, count: Int)] {
        var counts: [String: Int] = [:]
        for e in entries {
            guard let r = e.reason, !r.isEmpty else { continue }
            counts[r, default: 0] += 1
        }
        return counts.map { ($0.key, $0.value) }.sorted { $0.count > $1.count }
    }

    private var codeRows: [(code: String, buys: Int, sells: Int, interests: Int)] {
        var map: [String: (buy: Int, sell: Int, interest: Int)] = [:]
        for e in entries {
            var c = map[e.code] ?? (0, 0, 0)
            switch e.action {
            case "buy":  c.buy += 1
            case "sell": c.sell += 1
            default:     c.interest += 1
            }
            map[e.code] = c
        }
        return map
            .map { (code: $0.key, buys: $0.value.buy, sells: $0.value.sell, interests: $0.value.interest) }
            .sorted { ($0.buys + $0.sells + $0.interests) > ($1.buys + $1.sells + $1.interests) }
    }

    private struct HoldPair: Identifiable {
        let id: String
        let code: String
        let buyAt: Int64
        let sellAt: Int64
        var days: Int { Int((sellAt - buyAt) / (1000 * 60 * 60 * 24)) }
    }

    private var pairRows: [HoldPair] {
        var result: [HoldPair] = []
        let codes = Set(entries.map { $0.code })
        for code in codes {
            let codeEntries = entries.filter { $0.code == code }
            let buys  = codeEntries.filter { $0.action == "buy"  }.sorted { $0.createdAt < $1.createdAt }
            let sells = codeEntries.filter { $0.action == "sell" }.sorted { $0.createdAt < $1.createdAt }
            var si = 0
            for buy in buys {
                while si < sells.count && sells[si].createdAt <= buy.createdAt { si += 1 }
                guard si < sells.count else { break }
                result.append(HoldPair(id: "\(code)_\(buy.createdAt)", code: code,
                                       buyAt: buy.createdAt, sellAt: sells[si].createdAt))
                si += 1
            }
        }
        return result.sorted { $0.sellAt > $1.sellAt }
    }

    private var avgHoldDays: Double? {
        guard !pairRows.isEmpty else { return nil }
        return Double(pairRows.map { $0.days }.reduce(0, +)) / Double(pairRows.count)
    }

    // MARK: - 놓친 종목 모델

    struct MissedRow: Identifiable {
        let id: String           // code
        let code: String
        let name: String
        let lastInterestAt: Int64
        let loggedPrice: Int64?      // 로그에 저장된 당시 가격 (v3 이후)
        var lookbackPrice: Int64?    // 일봉 소급 가격 (구버전 로그, 로드 후 채워짐)
        var currentPrice: Int64?

        var thenPrice: Int64? { loggedPrice ?? lookbackPrice }

        var hypotheticalReturn: Double? {
            guard let then = thenPrice.map(Double.init),
                  let now = currentPrice.map(Double.init),
                  then > 0 else { return nil }
            return (now - then) / then * 100
        }
    }

    // MARK: - 로드

    private func reload() {
        entries = logRepo.getAll()
        let watchItems = watchRepo.all()
        nameMap = Dictionary(uniqueKeysWithValues: watchItems.map { ($0.code, $0.name) })
        Task { await loadMissed() }
    }

    private func loadMissed() async {
        missedLoading = true
        defer { missedLoading = false }

        // 관심 있고 매수 로그 없는 코드
        let interestCodes = Set(entries.filter { $0.action == "interest" }.map { $0.code })
        let buyCodes      = Set(entries.filter { $0.action == "buy" }.map { $0.code })
        let missed        = interestCodes.subtracting(buyCodes)
        guard !missed.isEmpty else { missedRows = []; return }

        // 종목별 가장 최근 interest 엔트리 추출
        var rows: [MissedRow] = []
        for code in missed {
            let latest = entries
                .filter { $0.code == code && $0.action == "interest" }
                .max(by: { $0.createdAt < $1.createdAt })
            guard let e = latest else { continue }
            let loggedPrice = e.price.map { $0.int64Value }
            rows.append(MissedRow(
                id: code, code: code,
                name: nameMap[code] ?? code,
                lastInterestAt: e.createdAt,
                loggedPrice: loggedPrice
            ))
        }

        // 현재가 일괄 조회
        let codes = rows.map { $0.code }
        if let quotes = try? await api.getQuotes(codes: codes) {
            let qmap = Dictionary(uniqueKeysWithValues: quotes.map { ($0.code, $0.price) })
            for i in rows.indices { rows[i].currentPrice = qmap[rows[i].code] }
        }

        // 구버전 로그(price 없음): 일봉 소급으로 당시 가격 추정
        await withTaskGroup(of: (String, Int64?).self) { group in
            for row in rows where row.loggedPrice == nil {
                group.addTask {
                    guard let bars = try? await self.api.getDaily(code: row.code, bars: 120)
                    else { return (row.code, nil) }
                    let targetDate = self.epochToYYYYMMDD(row.lastInterestAt)
                    // 최신일이 앞 → 관심 등록일 이하인 첫 번째 바 = 당일 또는 직전 거래일
                    let match = bars.first { $0.date <= targetDate }
                    return (row.code, match?.close)
                }
            }
            for await (code, price) in group {
                if let idx = rows.firstIndex(where: { $0.code == code }) {
                    rows[idx].lookbackPrice = price
                }
            }
        }

        missedRows = rows.sorted { $0.lastInterestAt > $1.lastInterestAt }
    }

    // MARK: - 포맷 헬퍼

    private func epochToYYYYMMDD(_ millis: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(millis) / 1000)
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd"
        f.timeZone = TimeZone(identifier: "Asia/Seoul")
        return f.string(from: d)
    }

    private func actionLabel(_ action: String) -> String {
        switch action {
        case "buy":  return "매수"
        case "sell": return "매도"
        default:     return "관심"
        }
    }

    private func actionColor(_ action: String) -> Color {
        switch action {
        case "buy":  return .red
        case "sell": return .blue
        default:     return .orange
        }
    }

    private func actionPill(_ label: String, _ color: Color) -> some View {
        Text(label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
    }

    private func holdLabel(_ days: Double) -> String {
        if days < 1   { return "당일" }
        if days < 30  { return "\(Int(days))일" }
        if days < 365 { return "\(Int(days / 30))개월" }
        return "\(String(format: "%.1f", days / 365))년"
    }

    private func shortDate(_ millis: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(millis) / 1000)
        let f = DateFormatter(); f.dateFormat = "MM/dd"
        return f.string(from: d)
    }

    private func shortTs(_ millis: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(millis) / 1000)
        let f = DateFormatter(); f.dateFormat = "MM/dd HH:mm"
        return f.string(from: d)
    }
}
