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
        // KotlinDouble?/KotlinLong? → 표시 문자열. nil이면 빈칸.
        _avg = State(initialValue: item.avgPrice.map { String(Int($0.doubleValue)) } ?? "")
        _qty = State(initialValue: item.qty.map { String($0.int64Value) } ?? "")
        _target = State(initialValue: item.targetPrice.map { String(Int($0.doubleValue)) } ?? "")
        _stop = State(initialValue: item.stopPrice.map { String(Int($0.doubleValue)) } ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("내 포지션") {
                    field("평단가", "원", $avg)
                    field("수량", "주", $qty)
                }
                Section("목표 / 손절") {
                    field("목표가", "원", $target)
                    field("손절가", "원", $stop)
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

    private func field(_ label: String, _ unit: String, _ text: Binding<String>) -> some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            TextField("미입력", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
            Text(unit).foregroundColor(.secondary)
        }
    }

    private func save() {
        // 빈칸/파싱 실패 → null(미입력). 천단위 콤마가 들어와도 무시하고 숫자만.
        let avgD = parseDouble(avg)
        let qtyL = parseLong(qty)
        let targetD = parseDouble(target)
        let stopD = parseDouble(stop)

        Db.watchlist.updatePosition(
            code: code,
            avgPrice: avgD.map { KotlinDouble(double: $0) },
            qty: qtyL.map { KotlinLong(longLong: $0) },
            targetPrice: targetD.map { KotlinDouble(double: $0) },
            stopPrice: stopD.map { KotlinDouble(double: $0) }
        )
        // 저장 직후 DB에서 갱신된 항목을 다시 읽어 상세에 넘긴다(보통 존재).
        if let updated = Db.watchlist.all().first(where: { $0.code == code }) {
            onSave(updated)
        }
        dismiss()
    }

    private func parseDouble(_ s: String) -> Double? {
        Double(s.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces))
    }
    private func parseLong(_ s: String) -> Int64? {
        Int64(s.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces))
    }
}
