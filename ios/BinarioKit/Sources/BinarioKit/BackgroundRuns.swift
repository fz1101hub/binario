import Foundation

/// When a background check runs next, as «Prossimi controlli in background» says it (Android's
/// `BackgroundRunTime`), on the Italian calendar. iOS reports no running state to the app, so the
/// iPhone has no «in corso».
public enum BackgroundRunTime: Equatable, Sendable {
    /// Already due: waiting for iOS to start the background task.
    case soon
    case today(PlannedLocalTime)
    case tomorrow(PlannedLocalTime)
    case later(PlannedLocalDate, PlannedLocalTime)

    /// Android's `BackgroundRuns.nextRun` for one planned date: due is «a breve», then today,
    /// tomorrow or a later day, the time without seconds.
    public static func of(_ at: Date, now: Date) -> BackgroundRunTime? {
        if at <= now { return .soon }
        guard let today = ItalianWallClock.date(of: now), let day = ItalianWallClock.date(of: at),
              let time = ItalianWallClock.time(of: at) else { return nil }
        if day == today { return .today(time) }
        if day == today.adding(days: 1) { return .tomorrow(time) }
        return .later(day, time)
    }
}
