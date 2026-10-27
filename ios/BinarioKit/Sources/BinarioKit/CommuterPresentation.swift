import Foundation

public enum SnapshotAvailability: Equatable, Sendable {
    case live, scheduled, stale, unavailable
}

extension JourneySnapshot {
    /// A successful fetch of a static timetable must never be described as live circulation.
    public func availability(at now: Date) -> SnapshotAvailability {
        guard expiresAt > now, generatedAt <= now.addingTimeInterval(60),
              provenance.observedAt <= now.addingTimeInterval(60),
              provenance.freshness != .stale else { return .stale }
        guard operationalState != .unavailable, provenance.nature != .unavailable,
              provenance.freshness != .unavailable else { return .unavailable }
        if operationalState == .programmed, provenance.nature == .programmed { return .scheduled }
        if operationalState == .operational, provenance.nature == .operational,
           provenance.confidence != .staticOnly, provenance.confidence != .localPlanned,
           provenance.confidence != .unknown,
           provenance.freshness == .fresh || provenance.freshness == .aging { return .live }
        return .unavailable
    }
}

public enum CommuterRefreshPolicy {
    public static let liveInterval: TimeInterval = 60
    public static let retryInterval: TimeInterval = 120

    public static func shouldRefresh(
        loading: Bool,
        lastAttempt: Date?,
        now: Date,
        failed: Bool = false
    ) -> Bool {
        guard !loading else { return false }
        guard let lastAttempt else { return true }
        let interval = failed ? retryInterval : liveInterval
        return now.timeIntervalSince(lastAttempt) >= interval
    }
}
