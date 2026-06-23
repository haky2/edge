import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
            #if DEBUG
                // 개발(Debug) 빌드 = 로컬 백엔드를 보고 있다는 표식. 운영(Release) 빌드엔 안 나온다.
                .overlay(alignment: .topTrailing) {
                    Text("LOCAL")
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundColor(.white)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.orange.opacity(0.9), in: Capsule())
                        .padding(.top, 2).padding(.trailing, 8)
                        .allowsHitTesting(false)   // 탭/버튼 동작을 막지 않게 통과
                }
            #endif
        }
    }
}