import SwiftUI
import SharedLogic

// 내 포지션 입력 시트. 평단가/수량/목표가/손절가를 손으로 입력(수동 입력 전제).
// G1: 읽기·쓰기 모두 HoldingRepository. watchlist 포지션 필드는 더 이상 사용하지 않음.
// G2: 계좌가 여러 개이면 계좌 피커를 표시. 계좌마다 독립 포지션을 보유할 수 있다.
struct PositionEditView: View {
    let code: String
    let name: String
    var onSave: (WatchItem) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var avg: String
    @State private var qty: String
    @State private var target: String
    @State private var stop: String
    @State private var thesis: String

    // 계좌 선택 (계좌가 1개=기본만 있으면 피커 숨김)
    @State private var accounts: [AccountInfo] = []
    @State private var selectedAccountId: Int64

    // N1: 리스크 기준 수량 (보유 포지션 있을 때만)
    @State private var showSizing = false
    @State private var sizing: PositionSizing?
    @State private var sizingLoading = false
    @State private var capPct: Double = 15

    private static let thesisMaxChars = 200

    init(item: WatchItem, onSave: @escaping (WatchItem) -> Void, initialAccountId: Int64? = nil) {
        self.code = item.code
        self.name = item.name
        self.onSave = onSave
        let defId = Db.holding.defaultAccountId()  // 상수 1 가정 금지 — 프레시 설치 자가 시드 포함
        let accountId = initialAccountId ?? defId
        _selectedAccountId = State(initialValue: accountId)
        // 지정된 계좌(또는 기본 계좌)의 holding에서 초기값 로드
        let existing = accountId == defId
            ? Db.holding.getDefaultHolding(code: item.code)
            : Db.holding.getHolding(code: item.code, accountId: accountId)
        _avg    = State(initialValue: existing?.avgPrice.map    { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _qty    = State(initialValue: existing?.qty.map         { String($0.int64Value) } ?? "")
        _target = State(initialValue: existing?.targetPrice.map { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _stop   = State(initialValue: existing?.stopPrice.map   { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _thesis = State(initialValue: item.thesis ?? "")
    }

    private var hasCustomAccounts: Bool { accounts.count > 1 }

    var body: some View {
        NavigationStack {
            Form {
                // 계좌가 여러 개일 때만 계좌 피커 노출
                if hasCustomAccounts {
                    Section("계좌") {
                        Picker("계좌", selection: $selectedAccountId) {
                            ForEach(accounts, id: \.id) { account in
                                Text(account.name).tag(account.id)
                            }
                        }
                        .pickerStyle(.menu)
                        .labelsHidden()
                    }
                }
                Section("내 포지션") {
                    priceField("평단가", $avg)
                    plainField("수량", "주", $qty)
                }
                Section("목표 / 손절") {
                    priceField("목표가", $target)
                    priceField("손절가", $stop)
                }
                if hasExistingHoldings {
                    sizingSection
                }
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        TextEditor(text: $thesis)
                            .frame(minHeight: 80)
                            .onChange(of: thesis) { v in
                                // 서버는 Kotlin String.length(UTF-16 단위)로 200자를 검사한다.
                                // Swift .count(grapheme)로 자르면 이모지 포함 시 서버 400 → 분석이 조용히 실패.
                                if v.utf16.count > Self.thesisMaxChars {
                                    var s = v
                                    while s.utf16.count > Self.thesisMaxChars { s.removeLast() }
                                    thesis = s
                                }
                            }
                        HStack {
                            Spacer()
                            Text("\(thesis.utf16.count)/\(Self.thesisMaxChars)")
                                .font(.caption2)
                                .foregroundColor(thesis.utf16.count >= Self.thesisMaxChars ? .red : .secondary)
                        }
                    }
                } header: {
                    Text("투자 논지")
                } footer: {
                    Text("왜 이 종목을 들고 있나(관심 갖나)? — AI가 매 분석에서 논지와 사실의 일치 여부를 점검합니다.")
                        .font(.caption)
                }
            }
            .navigationTitle("포지션 입력")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("저장") { save() }.fontWeight(.semibold)
                }
            }
        }
        .onAppear {
            accounts = Db.account.all() as! [AccountInfo]
        }
        .onChange(of: selectedAccountId) { newId in
            // 계좌 전환 시 해당 계좌의 holding으로 포지션 필드 갱신(논지는 watchlist 기반이라 유지)
            let existing = Db.holding.getHolding(code: code, accountId: newId)
            avg    = existing?.avgPrice.map    { Self.fmtPrice(Int($0.doubleValue)) } ?? ""
            qty    = existing?.qty.map         { String($0.int64Value) } ?? ""
            target = existing?.targetPrice.map { Self.fmtPrice(Int($0.doubleValue)) } ?? ""
            stop   = existing?.stopPrice.map   { Self.fmtPrice(Int($0.doubleValue)) } ?? ""
        }
    }

    // 다계좌 같은 종목은 수량 합산(리스크 엔드포인트 계약). avg·qty 있는 보유만.
    private var sizingEntries: [PositionSizingEntry] {
        var byCode: [String: Int64] = [:]
        for h in Db.holding.all() {
            guard h.avgPrice != nil, let q = h.qty else { continue }
            byCode[h.code, default: 0] += q.int64Value
        }
        return byCode.map { PositionSizingEntry(code: $0.key, qty: $0.value) }
    }

    private var hasExistingHoldings: Bool { !sizingEntries.isEmpty }

    @ViewBuilder
    private var sizingSection: some View {
        Section {
            DisclosureGroup(isExpanded: $showSizing) {
                Picker("리스크 상한", selection: $capPct) {
                    Text("10%").tag(10.0)
                    Text("15%").tag(15.0)
                    Text("20%").tag(20.0)
                    Text("25%").tag(25.0)
                }
                .pickerStyle(.segmented)

                if sizingLoading {
                    HStack { Spacer(); ProgressView(); Spacer() }
                } else if let s = sizing {
                    sizingResult(s)
                } else {
                    Text("리스크 상한을 골라 최대 수량을 계산합니다.")
                        .font(.caption).foregroundColor(.secondary)
                }
            } label: {
                Label("리스크 기준 수량 보기", systemImage: "scalemass")
                    .font(.subheadline.weight(.semibold))
            }
        } footer: {
            Text("이 종목이 포트폴리오 리스크의 상한 %만 차지하도록 하는 최대 수량입니다. 매수 추천이 아니라 리스크 상한 역산입니다.")
                .font(.caption)
        }
        .onChange(of: showSizing) { open in
            if open && sizing == nil { Task { await loadSizing() } }
        }
        .onChange(of: capPct) { _ in
            if showSizing { Task { await loadSizing() } }
        }
    }

    @ViewBuilder
    private func sizingResult(_ s: PositionSizing) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("최대 \(s.maxShares)주")
                    .font(.title3.weight(.bold))
                Spacer()
                Text("≈ \(Self.fmtPrice(Int(s.maxAmount)))원")
                    .font(.subheadline).foregroundColor(.secondary)
            }
            HStack(spacing: 6) {
                sizingChip("비중 \(String(format: "%.1f", s.targetWeightPct))%")
                sizingChip("리스크 기여 \(String(format: "%.0f", s.atRiskContributionPct))%")
                sizingChip("변동성 \(String(format: "%.0f", s.sigmaPct))%")
            }
            if s.approxByPeer {
                Text("⚠︎ 관측 부족 — 섹터 유사 종목 변동성으로 근사")
                    .font(.caption2).foregroundColor(.orange)
            }
            Text(s.caveat)
                .font(.caption2).foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func sizingChip(_ text: String) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Color.secondary.opacity(0.12))
            .cornerRadius(8)
    }

    private func loadSizing() async {
        await MainActor.run { sizingLoading = true }
        let entries = sizingEntries
        let result = try? await Db.api.postPositionSizing(
            positions: entries, candidateCode: code, riskCapPct: capPct)
        await MainActor.run {
            sizing = result
            sizingLoading = false
        }
    }

    private func priceField(_ label: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            TextField("미입력", text: text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .onChange(of: text.wrappedValue) { newValue in
                    let digits = newValue.filter { $0.isNumber }
                    let formatted = digits.isEmpty ? "" : (Int(digits).map { Self.fmtPrice($0) } ?? digits)
                    if formatted != newValue { text.wrappedValue = formatted }
                }
            Text("원").foregroundColor(.secondary)
        }
    }

    private func plainField(_ label: String, _ unit: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            TextField("미입력", text: text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
            Text(unit).foregroundColor(.secondary)
        }
    }

    private func save() {
        let avgD    = parseDouble(avg)
        let qtyL    = parseLong(qty)
        let targetD = parseDouble(target)
        let stopD   = parseDouble(stop)
        let thesisTrimmed = thesis.trimmingCharacters(in: .whitespacesAndNewlines)

        Db.holding.savePositionForAccount(
            code: code,
            name: name,
            accountId: selectedAccountId,
            avgPrice:    avgD.map    { KotlinDouble(double: $0) },
            qty:         qtyL.map    { KotlinLong(longLong: $0) },
            targetPrice: targetD.map { KotlinDouble(double: $0) },
            stopPrice:   stopD.map   { KotlinDouble(double: $0) }
        )
        Db.watchlist.updateThesis(code: code, name: name, thesis: thesisTrimmed.isEmpty ? nil : thesisTrimmed)
        // StockDetailView @State item 갱신용 WatchItem
        let updated = WatchItem(
            code: code, name: name,
            avgPrice:    avgD.map    { KotlinDouble(double: $0) },
            qty:         qtyL.map    { KotlinLong(longLong: $0) },
            targetPrice: targetD.map { KotlinDouble(double: $0) },
            stopPrice:   stopD.map   { KotlinDouble(double: $0) },
            thesis:      thesisTrimmed.isEmpty ? nil : thesisTrimmed
        )
        onSave(updated)
        dismiss()
    }

    private static func fmtPrice(_ n: Int) -> String {
        let fmt = NumberFormatter()
        fmt.numberStyle = .decimal
        return fmt.string(from: NSNumber(value: n)) ?? "\(n)"
    }

    private func parseDouble(_ s: String) -> Double? {
        Double(s.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces))
    }
    private func parseLong(_ s: String) -> Int64? {
        Int64(s.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces))
    }
}
