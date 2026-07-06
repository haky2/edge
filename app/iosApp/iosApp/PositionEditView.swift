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

    // 계좌 선택 (계좌가 1개=기본만 있으면 피커 숨김)
    @State private var accounts: [AccountInfo] = []
    @State private var selectedAccountId: Int64

    init(item: WatchItem, onSave: @escaping (WatchItem) -> Void) {
        self.code = item.code
        self.name = item.name
        self.onSave = onSave
        let defId = HoldingRepository.companion.DEFAULT_ACCOUNT_ID
        _selectedAccountId = State(initialValue: defId)
        // 기존 기본 계좌 holding에서 초기값 로드
        let existing = Db.holding.getDefaultHolding(code: item.code)
        _avg    = State(initialValue: existing?.avgPrice.map    { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _qty    = State(initialValue: existing?.qty.map         { String($0.int64Value) } ?? "")
        _target = State(initialValue: existing?.targetPrice.map { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _stop   = State(initialValue: existing?.stopPrice.map   { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
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
            // 계좌 전환 시 해당 계좌의 holding으로 필드 갱신
            let existing = Db.holding.getHolding(code: code, accountId: newId)
            avg    = existing?.avgPrice.map    { Self.fmtPrice(Int($0.doubleValue)) } ?? ""
            qty    = existing?.qty.map         { String($0.int64Value) } ?? ""
            target = existing?.targetPrice.map { Self.fmtPrice(Int($0.doubleValue)) } ?? ""
            stop   = existing?.stopPrice.map   { Self.fmtPrice(Int($0.doubleValue)) } ?? ""
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

        Db.holding.savePositionForAccount(
            code: code,
            name: name,
            accountId: selectedAccountId,
            avgPrice:    avgD.map    { KotlinDouble(double: $0) },
            qty:         qtyL.map    { KotlinLong(longLong: $0) },
            targetPrice: targetD.map { KotlinDouble(double: $0) },
            stopPrice:   stopD.map   { KotlinDouble(double: $0) }
        )
        // StockDetailView @State item 갱신용 WatchItem
        let updated = WatchItem(
            code: code, name: name,
            avgPrice:    avgD.map    { KotlinDouble(double: $0) },
            qty:         qtyL.map    { KotlinLong(longLong: $0) },
            targetPrice: targetD.map { KotlinDouble(double: $0) },
            stopPrice:   stopD.map   { KotlinDouble(double: $0) }
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
