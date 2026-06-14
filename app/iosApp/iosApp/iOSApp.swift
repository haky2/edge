import SwiftUI

@main
struct iOSApp: App {
    init() {
        // inline 타이틀 모드에서 List 첫 섹션 앞에 생기는 여백 제거
        UITableView.appearance().sectionHeaderTopPadding = 0
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}