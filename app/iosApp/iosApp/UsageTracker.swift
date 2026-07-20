import Foundation
import SharedLogic

/// M1 카드 사용량 트래커 — **단일 사용자 전제**.
/// 화면 진입(view)·카드 펼침(expand)을 로컬 큐에 모아 포그라운드 진입 시 배치 flush.
/// 개인 도구라 즉시 전송 불필요 → 앱 재실행에도 안 잃도록 UserDefaults에 큐를 영속한다.
/// 서버가 (screen,card,action,at)로 디듀프하므로 flush 재시도는 멱등.
final class Usage {
    static let shared = Usage()

    private let queueKey = "usage_event_queue_v1"
    private let defaults = UserDefaults.standard
    private let lock = NSLock()
    private var api: EdgeApi?
    private var flushing = false
    private static let maxQueue = 1000

    /// 큐에 담는 내부 표현(Swift Codable). flush 시점에만 SharedLogic UsageEvent로 변환.
    private struct Ev: Codable {
        let screen: String
        let card: String
        let action: String
        let at: String
    }

    /// KST 로컬 시각 "yyyy-MM-dd'T'HH:mm:ss" — 백엔드가 at.take(10)으로 날짜를 뽑아 최근 사용일/보존을 계산.
    private let fmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Asia/Seoul")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f
    }()

    private init() {}

    func configure(api: EdgeApi) {
        lock.lock(); self.api = api; lock.unlock()
    }

    /// 화면 진입(카드 노출). card는 빈 문자열.
    func view(_ screen: String) { enqueue(Ev(screen: screen, card: "", action: "view", at: fmt.string(from: Date()))) }

    /// 접이식 카드 펼침. card = 카드 표시 제목.
    func expand(_ screen: String, _ card: String) { enqueue(Ev(screen: screen, card: card, action: "expand", at: fmt.string(from: Date()))) }

    private func enqueue(_ e: Ev) {
        lock.lock()
        var q = loadLocked()
        q.append(e)
        if q.count > Self.maxQueue { q.removeFirst(q.count - Self.maxQueue) }
        saveLocked(q)
        lock.unlock()
    }

    /// 포그라운드/백그라운드 전환 시 호출. 큐 스냅샷을 전송하고 성공하면 그만큼만 앞에서 제거.
    func flush() {
        lock.lock()
        guard let api = api, !flushing else { lock.unlock(); return }
        let batch = loadLocked()
        if batch.isEmpty { lock.unlock(); return }
        flushing = true
        lock.unlock()

        Task {
            defer { lock.lock(); flushing = false; lock.unlock() }
            let events = batch.map { UsageEvent(screen: $0.screen, card: $0.card, action: $0.action, at: $0.at) }
            do {
                _ = try await api.postUsageEvents(events: events)
                lock.lock()
                var q = loadLocked()
                // flush 사이 새로 들어온 이벤트는 큐 끝에 붙으므로, 보낸 개수만큼 앞에서 제거.
                if q.count >= batch.count { q.removeFirst(batch.count) } else { q.removeAll() }
                saveLocked(q)
                lock.unlock()
            } catch {
                // 실패 시 큐 유지 — 다음 포그라운드에서 재시도(서버 디듀프로 멱등).
            }
        }
    }

    // MARK: - 영속(lock 보유 상태에서만 호출)

    private func loadLocked() -> [Ev] {
        guard let data = defaults.data(forKey: queueKey),
              let q = try? JSONDecoder().decode([Ev].self, from: data) else { return [] }
        return q
    }

    private func saveLocked(_ q: [Ev]) {
        if let data = try? JSONEncoder().encode(q) { defaults.set(data, forKey: queueKey) }
    }
}
