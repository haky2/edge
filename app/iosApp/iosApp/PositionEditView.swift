import SwiftUI
import SharedLogic

// 내 포지션 입력 시트. 평단가/수량/목표가/손절가를 손으로 입력(수동 입력 전제).
// G1: 읽기·쓰기 모두 HoldingRepository(기본 계좌). watchlist 포지션 필드는 더 이상 사용하지 않음.
// 빈칸으로 저장 시 holding 행을 삭제 → "포지션 없음" 상태로 되돌리기 가능.
struct PositionEditView: View {
    let code: String
    let name: String
    var onSave: (WatchItem) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var avg: String
    @State private var qty: String
    @State private var target: String
    @State private var stop: String

    init(item: WatchItem, onSave: @escaping (WatchItem) -> Void) {
        self.code = item.code
        self.name = item.name
        self.onSave = onSave
        // 기존 holding에서 초기값 로드 (watchlist.avgPrice 는 G1 migration 이후 항상 null)
        let existing = Db.holding.getDefaultHolding(code: item.code)
        _avg    = State(initialValue: existing?.avgPrice.map    { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _qty    = State(initialValue: existing?.qty.map         { String($0.int64Value) } ?? "")
        _target = State(initialValue: existing?.targetPrice.map { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _stop   = State(initialValue: existing?.stopPrice.map   { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
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

        Db.holding.savePosition(
            code: code,
            name: name,
            avgPrice:    avgD.map    { KotlinDouble(double: $0) },
            qty:         qtyL.map    { KotlinLong(longLong: $0) },
            targetPrice: targetD.map { KotlinDouble(double: $0) },
            stopPrice:   stopD.map   { KotlinDouble(double: $0) }
        )
        // StockDetailView 의 @State item 갱신용 — holding 저장값으로 WatchItem 구성
        let updated = WatchItem(
            code: code,
            name: name,
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
