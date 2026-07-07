import SwiftUI
import SharedLogic

private let presets = ["ISA", "IRP개인연금", "퇴직연금", "일반"]

struct AccountManagementView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var accounts: [AccountInfo] = []
    @State private var showAddSheet = false
    @State private var customName = ""
    @State private var migrationAlert: MigrationAlert? = nil

    var body: some View {
        List {
            Section {
                ForEach(accounts, id: \.id) { account in
                    HStack {
                        Text(account.name)
                        if account.isDefault == 1 {
                            Spacer()
                            Text("기본")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 8).padding(.vertical, 2)
                                .background(.quaternary, in: Capsule())
                        }
                    }
                }
                .onDelete(perform: deleteAccounts)
            } footer: {
                Text("기본 계좌는 삭제할 수 없습니다. 계좌 삭제 시 보유 종목은 기본 계좌로 이전됩니다.")
                    .font(.caption)
            }
        }
        .navigationTitle("계좌 관리")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddSheet = true } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .onAppear { reload() }
        .sheet(isPresented: $showAddSheet) {
            addSheet
        }
        .alert(item: $migrationAlert) { alert in
            Alert(
                title: Text("보유 종목 이전"),
                message: Text("기본 계좌의 보유 종목 \(alert.count)개를 '\(alert.accountName)'으로 이동하시겠습니까?"),
                primaryButton: .default(Text("이동")) {
                    Db.holding.moveToAccount(fromAccountId: Db.holding.defaultAccountId(), toAccountId: alert.accountId)
                },
                secondaryButton: .cancel(Text("유지"))
            )
        }
    }

    private var addSheet: some View {
        NavigationStack {
            Form {
                Section("프리셋") {
                    ForEach(presets, id: \.self) { preset in
                        Button(preset) { addAccount(name: preset) }
                            .foregroundStyle(.primary)
                    }
                }
                Section("직접 입력") {
                    TextField("계좌 이름", text: $customName)
                    Button("추가") { addAccount(name: customName) }
                        .disabled(customName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("계좌 추가")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("취소") { showAddSheet = false; customName = "" }
                }
            }
        }
    }

    private func reload() {
        accounts = Db.account.all() as! [AccountInfo]
    }

    private func addAccount(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let wasFirstCustom = Db.account.countCustom() == 0
        let newAccount = Db.account.insertAndGet(name: trimmed)
        showAddSheet = false
        customName = ""
        reload()
        // 첫 계좌 추가 시 기존 보유 이전 제안
        if wasFirstCustom {
            let count = Db.account.countInDefault()
            if count > 0 {
                migrationAlert = MigrationAlert(
                    accountId: newAccount.id,
                    accountName: newAccount.name,
                    count: Int(count)
                )
            }
        }
    }

    private func deleteAccounts(at offsets: IndexSet) {
        for i in offsets {
            let account = accounts[i]
            guard account.isDefault == 0 else { continue }
            Db.account.deleteById(id: account.id)
        }
        reload()
    }

    private struct MigrationAlert: Identifiable {
        let id = UUID()
        let accountId: Int64
        let accountName: String
        let count: Int
    }
}
