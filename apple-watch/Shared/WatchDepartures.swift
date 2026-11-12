import Foundation

/// The v1 contract carries departures as "HH:mm" wall-clock strings without a date or zone.
/// The iPhone producer formats them in the agency zone of the bundled timetables, all of which
/// keep Central European time (Rome, Berlin, Paris and Vienna share its offset and EU summer-time
/// rules), and lists only trains that depart between the snapshot's generation and the end of its
/// timetable window, at most 12 hours later. A listed time is therefore the next occurrence of
/// that wall-clock time in Europe/Rome after the generation instant.
enum WatchDepartureClock {
    /// "HH:mm" drops the seconds: a train due at 08:05:40 reads "08:05", so the search starts one
    /// minute before generation, as the iPhone producer does.
    static let resolutionLeeway: TimeInterval = 60

    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? TimeZone(secondsFromGMT: 3_600) ?? .current
        return calendar
    }

    /// The instant of each "HH:mm" time in list order, resolved after `start`; nil for a time that
    /// cannot be read. The list is sorted by departure, so each time is searched after the previous.
    static func departures(of times: [String], after start: Date) -> [Date?] {
        let calendar = Self.calendar
        var cursor = start
        return times.map { time in
            guard let departure = nextOccurrence(of: time, after: cursor, calendar: calendar) else { return nil }
            // Two trains in the same minute resolve to the same day.
            cursor = departure.addingTimeInterval(-1)
            return departure
        }
    }

    static func nextOccurrence(of time: String, after start: Date, calendar: Calendar) -> Date? {
        let parts = time.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2,
              parts.allSatisfy({ $0.count == 2 && $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
              let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0..<24).contains(hour), (0..<60).contains(minute)
        else { return nil }
        return calendar.nextDate(
            after: start,
            matching: DateComponents(hour: hour, minute: minute),
            matchingPolicy: .nextTime
        )
    }
}

/// A listed service with the instant it departs, when its time can be read.
struct WatchUpcomingService: Identifiable, Equatable {
    let service: WatchService
    let departure: Date?

    var id: String { service.id }
}

extension BinarioWatchSnapshot {
    var expiresAt: Date { Date(timeIntervalSince1970: TimeInterval(expiresAtEpochMillis) / 1_000) }

    /// The departure instant of each listed service, in list order.
    var serviceDepartures: [Date?] {
        WatchDepartureClock.departures(
            of: services.map(\.expectedDeparture),
            after: retrievedAt.addingTimeInterval(-WatchDepartureClock.resolutionLeeway)
        )
    }

    var upcomingServices: [WatchUpcomingService] {
        zip(services, serviceDepartures).map { WatchUpcomingService(service: $0, departure: $1) }
    }

    /// When the first listed train departs: the countdown of the app and the complication.
    var nextDeparture: Date? {
        serviceDepartures.first ?? nil
    }

    /// The snapshot as it reads at `date`: trains that have left are dropped, and once the
    /// snapshot has expired (or is otherwise no longer valid) it is the unavailable state. A time
    /// that cannot be read keeps its train listed until the snapshot expires.
    func revalidated(at date: Date) -> BinarioWatchSnapshot {
        guard isCompatible(at: Int64((date.timeIntervalSince1970 * 1_000).rounded(.down))) else {
            return .unavailable
        }
        let remaining = zip(services, serviceDepartures).compactMap { service, departure -> WatchService? in
            guard let departure else { return service }
            return departure > date ? service : nil
        }
        guard remaining.count != services.count else { return self }
        return replacingServices(remaining)
    }

    /// The instants at which what the snapshot shows changes, starting at `now`: each later
    /// departure before the expiry, then the expiry itself, when it switches to unavailable.
    func timelineDates(from now: Date) -> [Date] {
        let expiry = expiresAt
        guard expiry > now else { return [now] }
        let departures = Set(serviceDepartures.compactMap { $0 }.filter { $0 > now && $0 < expiry })
        return [now] + departures.sorted() + [expiry]
    }

    /// The next instant after `now` at which `revalidated(at:)` changes, if any.
    func nextChange(after now: Date) -> Date? {
        timelineDates(from: now).first { $0 > now }
    }

    /// One snapshot per change, for a widget timeline that ends when the snapshot expires.
    func timeline(from now: Date) -> [(date: Date, snapshot: BinarioWatchSnapshot)] {
        timelineDates(from: now).map { date in (date: date, snapshot: revalidated(at: date)) }
    }

    private func replacingServices(_ services: [WatchService]) -> BinarioWatchSnapshot {
        BinarioWatchSnapshot(
            schemaVersion: schemaVersion,
            generatedAtEpochMillis: generatedAtEpochMillis,
            expiresAtEpochMillis: expiresAtEpochMillis,
            ttlMillis: ttlMillis,
            provenanceProducer: provenanceProducer,
            provenanceDataNature: provenanceDataNature,
            provenanceSourceLabel: provenanceSourceLabel,
            provenanceObservedAtEpochMillis: provenanceObservedAtEpochMillis,
            operationalState: operationalState,
            freshness: freshness,
            confidence: confidence,
            routeId: routeId,
            routeLabel: routeLabel,
            direction: direction,
            notice: notice,
            services: services
        )
    }
}

/// The widget's timeline for what the Watch holds (`WatchSnapshotStorage.loadStored`). With a
/// snapshot: an entry at each change (`timeline(from:)`) and a new timeline asked at its end, in
/// case a newer snapshot arrived while WidgetKit deferred its reload. Without one: the unavailable
/// state alone and no reload, since only a snapshot from the iPhone changes it and its arrival
/// reloads the timelines (`WatchSnapshotStore.accept`); asking every 15 minutes for nothing would
/// spend the widget's daily budget.
enum WatchWidgetTimeline {
    static func plan(
        for stored: BinarioWatchSnapshot?, from now: Date
    ) -> (entries: [(date: Date, snapshot: BinarioWatchSnapshot)], reloadsAtEnd: Bool) {
        guard let stored else {
            return (entries: [(date: now, snapshot: BinarioWatchSnapshot.unavailable)], reloadsAtEnd: false)
        }
        return (entries: stored.timeline(from: now), reloadsAtEnd: true)
    }
}
