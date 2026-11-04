import Foundation
import WidgetKit

enum PhoneRefreshOutcome: Equatable, Sendable {
    /// The iPhone acknowledged the request; the new snapshot follows through the application context.
    case sent
    case unreachable
}

/// What the Watch shows under its refresh button.
enum PhoneRefreshFeedback: Equatable, Sendable {
    case idle
    case sending
    case sent
    case unreachable
}

/// Asks the paired iPhone for the next trains of the route it shows (its last search for now, or
/// the route pinned with «Mostra su Watch»).
@MainActor
protocol PhoneRefreshRequesting: AnyObject {
    func requestRefresh() async -> PhoneRefreshOutcome
}

@MainActor
final class WatchSnapshotStore: ObservableObject {
    /// What the app shows: the stored snapshot without the trains that have already left, and the
    /// unavailable state once it has expired.
    @Published private(set) var snapshot: BinarioWatchSnapshot
    @Published private(set) var refreshFeedback: PhoneRefreshFeedback = .idle

    /// The snapshot as received and cached, before revalidation against the clock.
    private var stored: BinarioWatchSnapshot
    private let refresher: (any PhoneRefreshRequesting)?
    private let clock: @MainActor () -> Date
    private var revalidation: Task<Void, Never>?
    /// The request sent on its own when the app opens with nothing to show.
    private var automaticRequest: Task<Void, Never>?

    init(
        snapshot: BinarioWatchSnapshot = WatchSnapshotStorage.load(),
        refresher: (any PhoneRefreshRequesting)? = nil,
        clock: @escaping @MainActor () -> Date = { Date() }
    ) {
        stored = snapshot
        self.refresher = refresher
        self.clock = clock
        self.snapshot = snapshot.revalidated(at: clock())
        scheduleRevalidation()
    }

    func refreshFromCache() {
        stored = WatchSnapshotStorage.load()
        revalidate()
    }

    /// The app is back on screen: it may have been suspended past a departure or the expiry.
    /// With no train left to show it also asks the iPhone once, without feedback, for the next
    /// trains of its route: Watch Connectivity wakes the iPhone app in the background, which
    /// answers with a new application context (the pinned route when no search is on screen).
    func sceneDidBecomeActive() {
        refreshFeedback = .idle
        refreshFromCache()
        guard snapshot.services.isEmpty, refresher != nil, automaticRequest == nil else { return }
        automaticRequest = Task { [weak self] in
            _ = await self?.refresher?.requestRefresh()
            self?.automaticRequest = nil
        }
    }

    /// Waits for the request `sceneDidBecomeActive` sent on its own, if any.
    func automaticRequestFinished() async {
        await automaticRequest?.value
    }

    /// Drops the trains that have left, switches to unavailable at the expiry and waits for the
    /// next such change.
    func revalidate() {
        snapshot = stored.revalidated(at: clock())
        scheduleRevalidation()
    }

    func accept(snapshot newSnapshot: BinarioWatchSnapshot) {
        guard newSnapshot.isCompatible else {
            replaceStored(with: .unavailable)
            return
        }
        do {
            try WatchSnapshotStorage.save(newSnapshot)
            refreshFeedback = .idle
            replaceStored(with: newSnapshot)
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            replaceStored(with: .unavailable)
        }
    }

    func clear() {
        WatchSnapshotStorage.clear()
        replaceStored(with: .unavailable)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// "Aggiorna": rereads the cache and asks the iPhone for the next trains of its route.
    func requestPhoneRefresh() async {
        refreshFromCache()
        guard let refresher else {
            refreshFeedback = .unreachable
            return
        }
        refreshFeedback = .sending
        let outcome = await refresher.requestRefresh()
        refreshFeedback = outcome == .sent ? .sent : .unreachable
    }

    private func replaceStored(with newSnapshot: BinarioWatchSnapshot) {
        stored = newSnapshot
        revalidate()
    }

    private func scheduleRevalidation() {
        revalidation?.cancel()
        let now = clock()
        guard let next = stored.nextChange(after: now) else {
            revalidation = nil
            return
        }
        // A suspended app does not run this task; sceneDidBecomeActive revalidates on return.
        let delay = max(0, next.timeIntervalSince(now))
        revalidation = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            self?.revalidate()
        }
    }
}
