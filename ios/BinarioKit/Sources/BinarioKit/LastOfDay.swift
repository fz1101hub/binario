import Foundation

/// A scheduled departure of the static timetable (Android's `StaticDeparture`).
public struct StaticDeparture: Equatable, Sendable {
    public let departure: Date
    public let publicNumber: String?
    /// Where the run ends (or the requested destination).
    public let terminus: String

    public init(departure: Date, publicNumber: String?, terminus: String) {
        self.departure = departure
        self.publicNumber = publicNumber
        self.terminus = terminus
    }
}

/// «Ultimo di oggi» per terminus and «primo domani alle …» of one station or one origin and destination
/// (Android's `StaticDayDepartures`).
public struct StaticDayDepartures: Equatable, Sendable {
    public let lastByTerminus: [StaticDeparture]
    public let firstNextDay: StaticDeparture?

    public init(lastByTerminus: [StaticDeparture], firstNextDay: StaticDeparture?) {
        self.lastByTerminus = lastByTerminus
        self.firstNextDay = firstNextDay
    }
}

/// «Ultimo di oggi» and «Non ci sono più treni oggi» on the boards, from the static timetable (Android's
/// `LastOfDayMarks`; contracts/shared-rules/v1/last-of-day-v1.json). A board row, live or scheduled, is the last of
/// the day when its number and scheduled minute are those of a last departure: delays never move the mark.
public enum LastOfDayMarks {
    /// The day a board may mark: today, when the board follows the current time or lists a search of today. A board
    /// of another day would say «oggi» of a day that is not on screen.
    public static func markedDay(today: PlannedLocalDate, followsCurrentTime: Bool, searchDate: PlannedLocalDate) -> PlannedLocalDate? {
        followsCurrentTime || searchDate == today ? today : nil
    }

    public static func isLast(_ day: StaticDayDepartures?, publicNumber: String?, scheduledDeparture: Date?) -> Bool {
        guard let day, let scheduledDeparture, let number = publicNumber.flatMap(JourneyPlannerRules.runNumberKey) else { return false }
        let minute = minuteOf(scheduledDeparture)
        return day.lastByTerminus.contains { last in
            minuteOf(last.departure) == minute && last.publicNumber.flatMap(JourneyPlannerRules.runNumberKey) == number
        }
    }

    /// The first departure of the next day once every last departure of `day` has left (or the day has none):
    /// «Non ci sono più treni oggi: primo domani alle …». Nil while a train of the day is still to leave.
    public static func firstNextDay(_ day: StaticDayDepartures?, now: Date) -> StaticDeparture? {
        guard let day, let first = day.firstNextDay else { return nil }
        return day.lastByTerminus.contains { $0.departure >= now } ? nil : first
    }

    private static func minuteOf(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 / 60).rounded(.down))
    }
}
