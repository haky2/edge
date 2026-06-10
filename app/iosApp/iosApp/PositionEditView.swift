import SwiftUI
import SharedLogic

// 1.5b — 내 포지션 입력 시트. 평단가/수량/목표가/손절가를 손으로 입력(수동 입력 전제).
// 빈칸은 null(미입력)로 저장 → "되돌리기" 가능. 저장 시 DB(updatePosition) 후 onSave로 상세에 반영.
struct PositionEditView: View {
    let code: String
    var onSave: (WatchItem) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var avg: String
    @State private var qty: String
    @State private var target: String
    @State private var stop: String

    init(item: WatchItem, onSave: @escaping (WatchItem) -> Void) {
        self.code = item.code
        self.onSave = onSave
        // KotlinDouble?/KotlinLong? → 쉼표 포맷 문자열. nil이면 빈칸.
        _avg    = State(initialValue: item.avgPrice.map  { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _qty    = State(initialValue: item.qty.map       { String($0.int64Value) } ?? "")
        _target = State(initialValue: item.targetPrice.map { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
        _stop   = State(initialValue: item.stopPrice.map   { Self.fmtPrice(Int($0.doubleValue)) } ?? "")
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

    // 가격 필드: 숫자만 입력, 입력 즉시 천단위 쉼표 자동 삽입
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

    // 수량 등 단순 숫자 필드 (쉼표 없음)
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
        // 빈칸/파싱 실패 → null(미입력). 쉼표는 replacingOccurrences로 제거.
        let avgD    = parseDouble(avg)
        let qtyL    = parseLong(qty)
        let targetD = parseDouble(target)
        let stopD   = parseDouble(stop)

        Db.watchlist.updatePosition(
            code: code,
            avgPrice:    avgD.map    { KotlinDouble(double: $0) },
            qty:         qtyL.map    { KotlinLong(longLong: $0) },
            targetPrice: targetD.map { KotlinDouble(double: $0) },
            stopPrice:   stopD.map   { KotlinDouble(double: $0) }
        )
        if let updated = Db.watchlist.all().first(where: { $0.code == code }) {
            onSave(updated)
        }
        dismiss()
    }

    // 정수를 천단위 쉼표 문자열로. Int.formatted()는 로케일 의존이라 NumberFormatter 명시.
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
