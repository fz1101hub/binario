import Foundation

/// The share of the ticket price Regulation (EU) 2021/782, art. 19, sets for a delay at the final
/// destination (Android's `CompensationShare`); the shares are the shared table
/// (contracts/shared-data/v1/delay-compensation-v1.json).
public enum CompensationShare: Equatable, Sendable {
    /// 60 to 119 minutes.
    case quarter
    /// 120 minutes or more.
    case half

    public var percent: Int {
        switch self {
        case .quarter: return SharedData.DelayCompensation.quarterPercent
        case .half: return SharedData.DelayCompensation.halfPercent
        }
    }
}

/// A run that reached `stationName` `delayMinutes` late, enough for the compensation notice. The
/// notice names the station: after a train-number search the last stop shown is the terminus, not
/// necessarily where the traveller left.
public struct DelayCompensation: Equatable, Sendable {
    public let stationName: String
    public let delayMinutes: Int
    public let share: CompensationShare

    public init(stationName: String, delayMinutes: Int, share: CompensationShare) {
        self.stationName = stationName
        self.delayMinutes = delayMinutes
        self.share = share
    }
}

/// Android's `DelayCompensationRule`: the notice is shown only on the actual arrival a source
/// verified at the last stop of the run shown, at least 60 minutes late. Never on scheduled-only or
/// estimated data, a cancelled train or a stop it skipped. It states the rule and nothing about the
/// ticket, which the app does not know. The cases are contracts/shared-rules/v1/delay-compensation-v1.json.
public enum DelayCompensationRule {
    public static let quarterFromMinutes = SharedData.DelayCompensation.quarterFromMinutes
    public static let halfFromMinutes = SharedData.DelayCompensation.halfFromMinutes

    /// The last stop shown, as the rule reads it.
    public struct Arrival: Equatable, Sendable {
        public let stationName: String
        public let delayMinutes: Int
        /// The source published an actual arrival there.
        public let arrived: Bool
        public let skipped: Bool
        /// The arrival is the source's own (Android's `operationalStatusVerified` of the stop, else of the run).
        public let verified: Bool

        public init(stationName: String, delayMinutes: Int, arrived: Bool, skipped: Bool, verified: Bool) {
            self.stationName = stationName
            self.delayMinutes = delayMinutes
            self.arrived = arrived
            self.skipped = skipped
            self.verified = verified
        }
    }

    public static func of(_ last: Arrival?, cancelled: Bool, isDemonstration: Bool) -> DelayCompensation? {
        guard !isDemonstration, !cancelled, let last else { return nil }
        guard last.arrived, !last.skipped, last.verified, last.delayMinutes >= quarterFromMinutes else { return nil }
        return DelayCompensation(
            stationName: last.stationName,
            delayMinutes: last.delayMinutes,
            share: last.delayMinutes >= halfFromMinutes ? .half : .quarter
        )
    }

    /// The notice of a run the live sources published: its last stop, with an actual arrival
    /// (`actual`, the stop passed) whose delay the source verified. The iPhone shows no demonstration data.
    public static func of(_ run: LiveRunStatus) -> DelayCompensation? {
        let arrival = run.stops.last.map { stop in
            Arrival(
                stationName: stop.stationName,
                delayMinutes: stop.delayMinutes,
                arrived: stop.passed && stop.actual != nil,
                skipped: stop.skipped,
                verified: stop.operationalStatusVerified
            )
        }
        return of(arrival, cancelled: run.state == .cancelled || run.cancellation == .full, isDemonstration: false)
    }
}
