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
    @State private var positionMap: [String: WatchItem] = [:]  // code → stop/target
    @State private var missedRows: [MissedRow] = []
    @State private var missedLoading = false
    @State private var moodAccuracy: MoodAccuracyReport? = nil

    // 접기/펼치기 상태 (앱 재시작 시 유지)
    @AppStorage("statsRecentExpanded") private var recentExpanded = false
    @AppStorage("statsCodeExpanded")   private var codeExpanded   = false
    @AppStorage("statsHoldExpanded")   private var holdExpanded   = false
    @AppStorage("statsReasonExpanded") private var reasonExpanded = false

    var body: some View {
        NavigationStack {
            List {
                moodAccuracySection
                if entries.isEmpty {
                    Section {
                        emptyPlaceholder
                    }
                } else {
                    summarySection
                    disciplineSection
                    winRateSection
                    missedSection
                    reasonSection
                    recentSection
                    codeSection
                    if avgHoldDays != nil || !pairRows.isEmpty { holdSection }
                }
            }
            .navigationTitle("내 패턴")
            .onAppear { reload() }
        }
    }

    // MARK: - 메인 리스트 (하위 호환, 현재는 body에 인라인)

    private var contentList: some View {
        List {
            summarySection
            disciplineSection
            winRateSection
            missedSection
            reasonSection
            recentSection
            codeSection
            if avgHoldDays != nil || !pairRows.isEmpty { holdSection }
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
        } footer: {
            Text("종목 상세 화면에서 관심·매수·매도를 기록할 때마다 쌓여요.")
                .font(.caption2)
        }
    }

    private func summaryCell(count: Int, label: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(count)").font(.title2.weight(.semibold)).foregroundColor(color)
            Text(label).font(.caption).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - 섹션: 코스피 방향 선행 신호

    private var moodAccuracySection: some View {
        Section {
            if let acc = moodAccuracy {
                // 오늘 예측 카드 — PENDING(장 전·장 중)일 때만 상단에 띄움
                let todayEntry = acc.recentEntries.first
                let todayPending = todayEntry?.isCorrect == nil
                if todayPending, let entry = todayEntry {
                    todayPredictionCard(entry.direction)
                }

                // 적중률 요약
                if acc.total > 0 {
                    HStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(acc.correct)/\(acc.total)회 적중")
                                .font(.headline)
                            let rate = Int(Double(acc.correct) / Double(acc.total) * 100)
                            Text("\(rate)% 정확도")
                                .font(.subheadline)
                                .foregroundColor(rate >= 60 ? .red : rate >= 40 ? .orange : .blue)
                        }
                        Spacer()
                        if acc.pending > 0 {
                            VStack(alignment: .trailing, spacing: 2) {
                                Text("대기 \(acc.pending)회")
                                    .font(.caption).foregroundColor(.secondary)
                                Text("장 마감 후 자동 채점")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }
                    .padding(.vertical, 2)
                } else if !todayPending {
                    Text("아직 채점된 기록 없어요")
                        .font(.subheadline).foregroundColor(.secondary)
                }

                // 히스토리 — 오늘 PENDING 카드로 이미 표시한 경우 첫 항목 제외
                let historyEntries = todayPending
                    ? Array(acc.recentEntries.dropFirst().prefix(7))
                    : Array(acc.recentEntries.prefix(7))
                ForEach(historyEntries, id: \.date) { entry in
                    moodLogRow(entry)
                }
            } else {
                HStack {
                    ProgressView().padding(.trailing, 4)
                    Text("불러오는 중…").foregroundColor(.secondary)
                }
            }
        } header: {
            Text("코스피 방향 선행 신호")
        } footer: {
            Text("나스닥·S&P500·EWY 등 미국 지수와 달러 지표 가중합으로 당일 코스피 방향을 예측해요. 아침에 확인하면 오늘 장 방향을 미리 가늠할 수 있고, 장 마감 후 재조회하면 자동으로 채점돼요.")
                .font(.caption2)
        }
    }

    private func todayPredictionCard(_ direction: String) -> some View {
        let (label, color, icon): (String, Color, String) = switch direction {
            case "BULLISH": ("강세 예상 ↑", Color.red,       "arrow.up.circle.fill")
            case "BEARISH": ("약세 예상 ↓", Color.blue,      "arrow.down.circle.fill")
            default:        ("보합 예상",   Color.secondary,  "minus.circle.fill")
        }
        return HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(color)
            VStack(alignment: .leading, spacing: 2) {
                Text("오늘 코스피")
                    .font(.caption).foregroundColor(.secondary)
                Text(label)
                    .font(.headline).foregroundColor(color)
            }
            Spacer()
            Text("미국 지수·달러 기반")
                .font(.caption2).foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func moodLogRow(_ entry: MoodLogEntry) -> some View {
        HStack(spacing: 8) {
            Text(entry.date.suffix(5).replacingOccurrences(of: "-", with: "/"))
                .font(.caption).foregroundColor(.secondary).frame(width: 40, alignment: .leading)

            directionBadge(entry.direction, isActual: false)

            Image(systemName: "arrow.right")
                .font(.caption2).foregroundColor(.secondary)

            if let actual = entry.actualDirection {
                directionBadge(actual, isActual: true)
            } else {
                Text("대기").font(.caption2).foregroundColor(.secondary)
            }

            Spacer()

            if let correct = entry.isCorrect {
                let isOk = correct.boolValue
                Image(systemName: isOk ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .foregroundColor(isOk ? .green : .red)
                    .font(.body)
            }

            if let kChange = entry.kospiChange {
                let v = kChange.doubleValue
                let sign = v >= 0 ? "+" : ""
                Text("코스피 \(sign)\(String(format: "%.1f", v))%")
                    .font(.caption2).foregroundColor(v >= 0 ? .red : .blue)
            }
        }
        .padding(.vertical, 1)
    }

    private func directionBadge(_ direction: String, isActual: Bool) -> some View {
        let (label, color): (String, Color) = switch direction {
            case "BULLISH": ("강세↑", .red)
            case "BEARISH": ("약세↓", .blue)
            default:        ("보합", .secondary)
        }
        return Text(label)
            .font(.caption2.weight(.semibold))
            .foregroundColor(isActual ? .primary : color)
            .padding(.horizontal, 5).padding(.vertical, 2)
            .background(isActual ? Color.secondary.opacity(0.12) : color.opacity(0.12))
            .clipShape(Capsule())
    }

    private var emptyPlaceholder: some View {
        VStack(spacing: 12) {
            Image(systemName: "chart.line.uptrend.xyaxis")
                .font(.system(size: 48)).foregroundColor(.secondary.opacity(0.4))
            Text("기록이 없어요")
                .font(.headline).foregroundColor(.secondary)
            Text("종목 상세 화면에서 관심·매수·매도를\n기록하면 여기서 패턴을 볼 수 있어요.")
                .font(.callout).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }

    // MARK: - 섹션: 보유기간 (접기/펼치기)

    private var holdSection: some View {
        Section {
            DisclosureGroup(isExpanded: $holdExpanded) {
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
            } label: {
                disclosureLabel("매수 → 매도 보유기간", sub: pairRows.isEmpty ? nil : "\(pairRows.count)쌍")
            }
        } footer: {
            Text("매수 기록 후 같은 종목을 매도하기까지 걸린 시간. 7일 이하는 주황색으로 강조돼요.")
                .font(.caption2)
        }
    }

    // MARK: - 섹션: 신호별 승률

    @ViewBuilder
    private var winRateSection: some View {
        let totalPairs = winRateRows.reduce(0) { $0 + $1.total }
        Section {
            if winRateRows.isEmpty {
                Text("아직 계산할 수 없어요\n(가격이 기록된 매수→매도 쌍이 필요해요)")
                    .font(.footnote).foregroundColor(.secondary)
                    .padding(.vertical, 4)
            } else {
                ForEach(winRateRows) { row in
                    winRateRowView(row)
                }
            }
        } header: {
            if winRateRows.isEmpty {
                Text("신호별 승률")
            } else {
                Text("신호별 승률 (\(winRateRows.count)개 신호 · \(totalPairs)쌍)")
            }
        } footer: {
            Text("매수할 때 입력한 사유 태그가 '신호'예요. 예: '외인 순매수', '52주 저점'. 어떤 신호로 산 종목이 수익으로 이어졌는지 볼 수 있어요. 쌍이 5개 미만(n=N 표시)인 신호는 표본이 부족해 숫자를 신뢰하기 어려워요.")
                .font(.caption2)
        }
    }

    private func winRateRowView(_ row: WinRateRow) -> some View {
        let rateColor: Color = row.isReliable
            ? (row.rate >= 60 ? .red : row.rate >= 40 ? .primary : .blue)
            : .secondary
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(row.reason).font(.body)
                    .foregroundColor(row.isReliable ? .primary : .secondary)
                Spacer()
                if !row.isReliable {
                    Text("n=\(row.total)").font(.caption2).foregroundColor(.secondary)
                }
                Text(String(format: "%.0f%%", row.rate))
                    .font(.body.weight(.semibold))
                    .foregroundColor(rateColor)
                Text("\(row.wins)승 \(row.losses)패")
                    .font(.caption).foregroundColor(.secondary)
            }
            GeometryReader { geo in
                HStack(spacing: 1) {
                    if row.wins > 0 {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.red.opacity(row.isReliable ? 0.65 : 0.25))
                            .frame(width: geo.size.width * CGFloat(row.wins) / CGFloat(row.total) - 1)
                    }
                    if row.losses > 0 {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.blue.opacity(row.isReliable ? 0.4 : 0.15))
                            .frame(width: geo.size.width * CGFloat(row.losses) / CGFloat(row.total) - 1)
                    }
                }
            }
            .frame(height: 6)
        }
        .padding(.vertical, 4)
    }

    // MARK: - 섹션: 손절/익절 규율

    @ViewBuilder
    private var disciplineSection: some View {
        let rows = disciplineRows
        let violations = rows.filter { $0.status == .stopViolated }
        let targets    = rows.filter { $0.status == .targetReached }
        Section {
            if rows.isEmpty {
                Text("기준가(목표가·손절가)가 설정된\n매수→매도 쌍이 필요해요")
                    .font(.footnote).foregroundColor(.secondary)
                    .padding(.vertical, 4)
            } else {
                HStack(spacing: 0) {
                    discCell(count: violations.count, label: "손절 어김", color: .blue)
                    Divider().frame(height: 36)
                    discCell(count: targets.count,    label: "목표 달성", color: .red)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)

                if !violations.isEmpty {
                    let overshoots = violations.compactMap { $0.stopOvershootPct }
                    let avg = overshoots.reduce(0, +) / Double(overshoots.count)
                    HStack {
                        Text("평균 손절선 초과")
                        Spacer()
                        Text(String(format: "%.1f%%p", avg))
                            .font(.body.weight(.semibold))
                            .foregroundColor(.blue)
                        Text("더 손실 후 매도")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }

                ForEach(rows) { row in discRow(row) }
            }
        } header: {
            Text(rows.isEmpty ? "손절/익절 규율" : "손절/익절 규율 (\(rows.count)쌍)")
        } footer: {
            Text("종목 상세에서 설정한 손절가·목표가 기준으로 실제 매도가 규율을 지켰는지 확인해요.")
                .font(.caption2)
        }
    }

    private func discCell(count: Int, label: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(count)")
                .font(.title2.weight(.semibold))
                .foregroundColor(count > 0 ? color : .secondary)
            Text(label).font(.caption).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func discRow(_ row: DisciplineRow) -> some View {
        HStack(spacing: 8) {
            discBadge(row.status)
            VStack(alignment: .leading, spacing: 2) {
                Text(nameMap[row.code] ?? row.code).font(.body)
                Text("\(shortDate(row.buyAt)) → \(shortDate(row.sellAt))")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                let ret = row.actualReturnPct
                Text((ret >= 0 ? "+" : "") + String(format: "%.1f%%", ret))
                    .font(.body.weight(.semibold))
                    .foregroundColor(ret >= 0 ? .red : .blue)
                if let ov = row.stopOvershootPct {
                    Text(String(format: "손절선 대비 %.1f%%p", ov))
                        .font(.caption2).foregroundColor(.blue.opacity(0.8))
                } else if row.status == .stopRespected, let s = row.stopPrice {
                    Text("손절선 \(s.formatted())원 위")
                        .font(.caption2).foregroundColor(.secondary)
                } else if row.status == .targetReached, let t = row.targetPrice {
                    Text("목표 \(t.formatted())원 달성")
                        .font(.caption2).foregroundColor(.red.opacity(0.7))
                }
            }
        }
        .padding(.vertical, 3)
    }

    private func discBadge(_ status: DisciplineRow.Status) -> some View {
        let label: String
        let color: Color
        switch status {
        case .stopViolated:  label = "손절 어김"; color = .blue
        case .stopRespected: label = "손절 지킴"; color = .teal
        case .targetReached: label = "목표 달성"; color = .red
        case .profitExit:    label = "수익 청산"; color = .orange
        }
        return Text(label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
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
                Text("관심 기록 후 매수하지 않은 종목이 없어요").font(.footnote).foregroundColor(.secondary)
            } else {
                ForEach(missedRows) { row in
                    missedRow(row)
                }
            }
        } header: {
            Text("놓친 종목 (관심 후 미매수 \(missedRows.count)개)")
        } footer: {
            Text("종목 상세 화면 하단의 '관심 기록' 버튼을 눌러야 여기 집계돼요. 그 종목을 매수하지 않았다면, 그때 샀을 경우 지금 수익률이 얼마였는지 보여줘요.")
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

    // MARK: - 섹션: 사유 태그 (접기/펼치기)

    private var reasonSection: some View {
        Section {
            DisclosureGroup(isExpanded: $reasonExpanded) {
                if reasonRows.isEmpty {
                    Text("아직 사유 태그 기록이 없어요")
                        .font(.footnote).foregroundColor(.secondary)
                } else {
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
            } label: {
                disclosureLabel("사유 태그", sub: reasonRows.isEmpty ? nil : "Top \(min(reasonRows.count, 8))개")
            }
        } footer: {
            Text("관심·매수·매도 기록 시 입력한 사유 태그 빈도. 내가 어떤 이유로 행동하는지 패턴을 볼 수 있어요.")
                .font(.caption2)
        }
    }

    // MARK: - 섹션: 종목별 활동 (접기/펼치기)

    private var codeSection: some View {
        Section {
            DisclosureGroup(isExpanded: $codeExpanded) {
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
            } label: {
                disclosureLabel("종목별 활동", sub: "\(codeRows.count)종목")
            }
        } footer: {
            Text("종목마다 관심·매수·매도를 몇 번 기록했는지. 자주 들여다본 종목이 위에 나와요.")
                .font(.caption2)
        }
    }

    // MARK: - 섹션: 최근 활동 (접기/펼치기)

    private var recentSection: some View {
        Section {
            DisclosureGroup(isExpanded: $recentExpanded) {
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
            } label: {
                disclosureLabel("최근 활동", sub: "최근 \(min(entries.count, 20))건")
            }
        } footer: {
            Text("가장 최근 기록 20건. 상세 화면에서 관심·매수·매도 버튼을 누를 때마다 쌓여요.")
                .font(.caption2)
        }
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
        return counts.map { (reason: $0.key, count: $0.value) }
            .sorted { $0.count != $1.count ? $0.count > $1.count : $0.reason < $1.reason }
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
        let buyPrice: Int64?
        let sellPrice: Int64?
        let buyReason: String?
        var days: Int { Int((sellAt - buyAt) / (1000 * 60 * 60 * 24)) }
        var isWin: Bool? {
            guard let b = buyPrice.map(Double.init),
                  let s = sellPrice.map(Double.init),
                  b > 0 else { return nil }
            return s > b
        }
    }

    private struct WinRateRow: Identifiable {
        var id: String { reason }
        let reason: String
        let wins: Int
        let losses: Int
        var total: Int { wins + losses }
        var rate: Double { total > 0 ? Double(wins) / Double(total) * 100 : 0 }
        var isReliable: Bool { total >= 5 }
    }

    private struct DisciplineRow: Identifiable {
        let id: String
        let code: String
        let buyAt: Int64
        let sellAt: Int64
        let buyPrice: Int64
        let sellPrice: Int64
        let stopPrice: Int64?
        let targetPrice: Int64?

        var actualReturnPct: Double {
            (Double(sellPrice) - Double(buyPrice)) / Double(buyPrice) * 100
        }

        enum Status {
            case stopViolated   // 손절선 이하로 매도
            case stopRespected  // 손실 매도이나 손절선 위
            case targetReached  // 목표가 달성
            case profitExit     // 수익 매도 (목표 미달)
        }

        var status: Status {
            if let t = targetPrice, sellPrice >= t { return .targetReached }
            if let s = stopPrice, sellPrice < s    { return .stopViolated }
            if stopPrice != nil, sellPrice < buyPrice { return .stopRespected }
            return .profitExit
        }

        // 손절선 대비 초과 손실 %p (stopViolated일 때만)
        var stopOvershootPct: Double? {
            guard status == .stopViolated, let s = stopPrice else { return nil }
            return (Double(sellPrice) - Double(s)) / Double(buyPrice) * 100
        }
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
                let sell = sells[si]
                result.append(HoldPair(
                    id: "\(code)_\(buy.createdAt)",
                    code: code,
                    buyAt: buy.createdAt,
                    sellAt: sell.createdAt,
                    buyPrice: buy.price.map { $0.int64Value },
                    sellPrice: sell.price.map { $0.int64Value },
                    buyReason: buy.reason
                ))
                si += 1
            }
        }
        return result.sorted { $0.sellAt > $1.sellAt }
    }

    private var winRateRows: [WinRateRow] {
        var map: [String: (wins: Int, losses: Int)] = [:]
        for pair in pairRows {
            guard let win = pair.isWin,
                  let reason = pair.buyReason, !reason.isEmpty else { continue }
            var c = map[reason] ?? (wins: 0, losses: 0)
            if win { c.wins += 1 } else { c.losses += 1 }
            map[reason] = c
        }
        return map
            .map { WinRateRow(reason: $0.key, wins: $0.value.wins, losses: $0.value.losses) }
            .sorted { $0.total > $1.total }
    }

    private var avgHoldDays: Double? {
        guard !pairRows.isEmpty else { return nil }
        return Double(pairRows.map { $0.days }.reduce(0, +)) / Double(pairRows.count)
    }

    private var disciplineRows: [DisciplineRow] {
        pairRows.compactMap { pair in
            guard let bp = pair.buyPrice, let sp = pair.sellPrice else { return nil }
            let item = positionMap[pair.code]
            let stop   = item.flatMap { w in w.stopPrice.map   { Int64($0.doubleValue) } }
            let target = item.flatMap { w in w.targetPrice.map { Int64($0.doubleValue) } }
            guard stop != nil || target != nil else { return nil }
            return DisciplineRow(
                id: pair.id, code: pair.code,
                buyAt: pair.buyAt, sellAt: pair.sellAt,
                buyPrice: bp, sellPrice: sp,
                stopPrice: stop, targetPrice: target
            )
        }
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
        positionMap = Dictionary(uniqueKeysWithValues: watchItems.map { ($0.code, $0) })
        Task { await loadMissed() }
        Task { await loadMoodAccuracy() }
    }

    private func loadMoodAccuracy() async {
        moodAccuracy = try? await api.getMoodAccuracy()
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

    // MARK: - DisclosureGroup 라벨

    private func disclosureLabel(_ title: String, sub: String?) -> some View {
        HStack(spacing: 4) {
            Text(title).foregroundColor(.primary)
            if let sub = sub {
                Text(sub).font(.caption).foregroundColor(.secondary)
            }
        }
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
