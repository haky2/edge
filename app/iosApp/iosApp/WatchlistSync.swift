import Foundation
import SharedLogic

/// 관심종목을 백엔드에 동기화한다 — 슬랙 신호·주간회고 스캔 대상(기기별 등록, 활성 합집합).
/// 관심종목 화면 로드 때 호출(진입·추가·삭제 후 모두 여기로 돌아옴). 직전과 같으면 skip(중복 POST 방지).
/// 국내 6자리 코드만 전송(백엔드 신호 스캔은 국내 전용, 해외 US: 접두 제외).
enum WatchlistSync {
    private static var lastSynced: Set<String>? = nil

    /// 기기 UUID — 최초 1회 생성해 로컬 보관(계정/로그인 없음).
    private static func deviceId() -> String {
        let key = "edge_device_id"
        let d = UserDefaults.standard
        if let id = d.string(forKey: key) { return id }
        let id = UUID().uuidString
        d.set(id, forKey: key)
        return id
    }

    static func push(api: EdgeApi, codes: [String]) {
        let domestic = codes.filter { !$0.hasPrefix("US:") }
        let set = Set(domestic)
        if set == lastSynced { return }   // 변화 없으면 skip
        lastSynced = set
        let id = deviceId()
        Task { try? await api.syncWatchlist(deviceId: id, codes: domestic) }
    }
}
