import Foundation

// What the iPhone app and its home-screen widgets (the WidgetKit extension ios/BinarioWidgets) share
// through their App Group, on this iPhone only: the bundled timetable index the app installs, the
// «Dati in tempo reale» switch and «I miei treni» as the app keeps them, and what each widget last
// loaded (its rows, when, the direction chosen with its button, the station «📍 Più vicina» chose).
// Nothing here is sent anywhere.

/// The App Group of the iPhone app and its widget extension.
public enum WidgetAppGroup {
    public static let identifier = "group.it.binario.ios"

    /// The group's preferences; nil when the build has no App Group (a test host, an unsigned build).
    public static func defaults() -> UserDefaults? {
        UserDefaults(suiteName: identifier)
    }

    /// The folder the app installs the bundled timetable index into, so that the widgets read the same
    /// file; nil without the App Group.
    public static func scheduleDirectory() -> URL? {
        #if os(Linux)
        return nil
        #else
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)?
            .appendingPathComponent("Binario", isDirectory: true)
            .appendingPathComponent("GTFS", isDirectory: true)
        #endif
    }
}

/// The live values a widget row shows, as a source published them when the widget was refreshed.
public struct WidgetLiveValues: Codable, Equatable, Sendable {
    public let expectedDeparture: Date?
    public let delayMinutes: Int?
    public let cancelled: Bool
    /// The bare «RITARDO» of a board: late, without minutes.
    public let lateWithoutMinutes: Bool
    public let platform: String?
    /// The timetable platform the same source publishes next to `platform` («BIN 7 era 5»).
    public let scheduledPlatform: String?
    public let source: String

    public init(
        expectedDeparture: Date?, delayMinutes: Int?, cancelled: Bool, lateWithoutMinutes: Bool,
        platform: String?, scheduledPlatform: String?, source: String
    ) {
        self.expectedDeparture = expectedDeparture
        self.delayMinutes = delayMinutes
        self.cancelled = cancelled
        self.lateWithoutMinutes = lateWithoutMinutes
        self.platform = platform
        self.scheduledPlatform = scheduledPlatform
        self.source = source
    }

    public init(_ status: StationBoardLiveStatus, scheduledPlatform: String?) {
        self.init(
            expectedDeparture: status.expectedDeparture, delayMinutes: status.delayMinutes, cancelled: status.cancelled,
            lateWithoutMinutes: status.lateWithoutMinutes, platform: status.platform,
            scheduledPlatform: status.platform == nil ? nil : scheduledPlatform, source: status.sourceLabel
        )
    }
}

/// Why a widget has no rows from the timetable.
public enum WidgetFailure: String, Codable, Sendable {
    /// No train between the two stations (or at the station) in the bundled timetable.
    case noJourney
    /// The included timetables end before today.
    case scheduleExpired
    /// The timetable index cannot be read yet (the app has not installed it in the App Group).
    case unavailable
}

/// One run of the route widget.
public struct WidgetRouteRow: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let number: String
    public let category: String
    public let operatorName: String
    public let destination: String
    /// "HH:mm" as the timetable publishes it at the origin.
    public let scheduledTime: String
    public let departure: Date?
    /// The scheduled arrival at the searched destination, when the timetable gives it.
    public let arrival: Date?
    public let isBus: Bool
    public let group: WidgetTrainTypeGroup?
    public let live: WidgetLiveValues?

    public init(
        id: String, number: String, category: String, operatorName: String, destination: String, scheduledTime: String,
        departure: Date?, arrival: Date?, isBus: Bool, group: WidgetTrainTypeGroup?, live: WidgetLiveValues?
    ) {
        self.id = id
        self.number = number
        self.category = category
        self.operatorName = operatorName
        self.destination = destination
        self.scheduledTime = scheduledTime
        self.departure = departure
        self.arrival = arrival
        self.isBus = isBus
        self.group = group
        self.live = live
    }

    /// The departure the list counts (expected when a source published it, as Android's row keeps), and
    /// the arrival moved by the published delay: the run stays «In viaggio» while it may still travel.
    public var run: WidgetRouteRun {
        let delay = TimeInterval(max(live?.delayMinutes ?? 0, 0) * 60)
        return WidgetRouteRun(
            departure: live?.expectedDeparture ?? departure,
            arrival: arrival.map { $0.addingTimeInterval(delay) },
            cancelled: live?.cancelled == true,
            group: group
        )
    }
}

/// What a route widget last loaded.
public struct WidgetRouteSnapshot: Codable, Equatable, Sendable {
    public let originID: String
    public let destinationID: String
    public let direction: WidgetDirection
    public let timeRange: WidgetTimeRange
    public let rows: [WidgetRouteRow]
    public let retrievedAt: Date
    /// The live sources whose values the rows carry; empty: the timetable alone.
    public let liveSources: [String]
    /// The live sources were asked and none answered.
    public let liveUnavailable: Bool
    public let failure: WidgetFailure?

    public init(
        originID: String, destinationID: String, direction: WidgetDirection, timeRange: WidgetTimeRange,
        rows: [WidgetRouteRow], retrievedAt: Date, liveSources: [String], liveUnavailable: Bool, failure: WidgetFailure?
    ) {
        self.originID = originID
        self.destinationID = destinationID
        self.direction = direction
        self.timeRange = timeRange
        self.rows = rows
        self.retrievedAt = retrievedAt
        self.liveSources = liveSources
        self.liveUnavailable = liveUnavailable
        self.failure = failure
    }
}

/// One departure of the station widget.
public struct WidgetBoardRow: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    /// Nil for a run without a train number (a city line, a bus line).
    public let number: String?
    public let category: String
    public let operatorName: String
    public let destination: String
    public let scheduled: Date
    public let isBus: Bool
    public let group: WidgetTrainTypeGroup?
    public let live: WidgetLiveValues?

    public init(
        id: String, number: String?, category: String, operatorName: String, destination: String, scheduled: Date,
        isBus: Bool, group: WidgetTrainTypeGroup?, live: WidgetLiveValues?
    ) {
        self.id = id
        self.number = number
        self.category = category
        self.operatorName = operatorName
        self.destination = destination
        self.scheduled = scheduled
        self.isBus = isBus
        self.group = group
        self.live = live
    }

    public var departure: WidgetBoardDeparture {
        WidgetBoardDeparture(scheduled: scheduled, expected: live?.expectedDeparture, observed: live != nil, group: group)
    }
}

/// What a station widget last loaded.
public struct WidgetStationSnapshot: Codable, Equatable, Sendable {
    public let stationID: String
    public let stationName: String
    public let rows: [WidgetBoardRow]
    public let retrievedAt: Date
    public let liveSources: [String]
    public let liveUnavailable: Bool
    public let failure: WidgetFailure?

    public init(
        stationID: String, stationName: String, rows: [WidgetBoardRow], retrievedAt: Date, liveSources: [String],
        liveUnavailable: Bool, failure: WidgetFailure?
    ) {
        self.stationID = stationID
        self.stationName = stationName
        self.rows = rows
        self.retrievedAt = retrievedAt
        self.liveSources = liveSources
        self.liveUnavailable = liveUnavailable
        self.failure = failure
    }
}

/// The last known state of one favourite train, shared by every «I miei treni» widget (Android's
/// `FavouriteTrainStatus`). When a lookup fails the previous values stay, marked `stale`.
public struct WidgetTrainStatus: Codable, Equatable, Sendable {
    public let retrievedAt: Date
    public let category: String
    public let operatorName: String
    public let origin: String
    public let destination: String
    /// "HH:mm" at the boarding station.
    public let scheduledDeparture: String
    /// The run's scheduled departure from its origin.
    public let departure: Date?
    /// The arrival at the last stop: expected when verified, else scheduled.
    public let arrival: Date?
    /// Minutes late (negative when early); nil without a verified observation.
    public let delayMinutes: Int?
    public let cancelled: Bool
    /// A route change, or skipped or added stops.
    public let variation: Bool
    public let nextStation: String?
    public let nextStationTime: String?
    public let lastDetectionStation: String?
    public let lastDetectionTime: String?
    public let platform: String?
    public let scheduledPlatform: String?
    public let departed: Bool?
    public let arrived: Bool?
    public let operationalVerified: Bool
    public let providerName: String
    public let stale: Bool

    public init(
        retrievedAt: Date, category: String, operatorName: String, origin: String, destination: String,
        scheduledDeparture: String, departure: Date?, arrival: Date?, delayMinutes: Int?, cancelled: Bool,
        variation: Bool, nextStation: String?, nextStationTime: String?, lastDetectionStation: String?,
        lastDetectionTime: String?, platform: String?, scheduledPlatform: String?, departed: Bool?, arrived: Bool?,
        operationalVerified: Bool, providerName: String, stale: Bool
    ) {
        self.retrievedAt = retrievedAt
        self.category = category
        self.operatorName = operatorName
        self.origin = origin
        self.destination = destination
        self.scheduledDeparture = scheduledDeparture
        self.departure = departure
        self.arrival = arrival
        self.delayMinutes = delayMinutes
        self.cancelled = cancelled
        self.variation = variation
        self.nextStation = nextStation
        self.nextStationTime = nextStationTime
        self.lastDetectionStation = lastDetectionStation
        self.lastDetectionTime = lastDetectionTime
        self.platform = platform
        self.scheduledPlatform = scheduledPlatform
        self.departed = departed
        self.arrived = arrived
        self.operationalVerified = operationalVerified
        self.providerName = providerName
        self.stale = stale
    }

    public var run: WidgetTrainRun {
        WidgetTrainRun(departure: departure, arrival: arrival, departed: departed, arrived: arrived, cancelled: cancelled)
    }

    /// The same values, left over by a failed lookup.
    public var markedStale: WidgetTrainStatus {
        WidgetTrainStatus(
            retrievedAt: retrievedAt, category: category, operatorName: operatorName, origin: origin, destination: destination,
            scheduledDeparture: scheduledDeparture, departure: departure, arrival: arrival, delayMinutes: delayMinutes,
            cancelled: cancelled, variation: variation, nextStation: nextStation, nextStationTime: nextStationTime,
            lastDetectionStation: lastDetectionStation, lastDetectionTime: lastDetectionTime, platform: platform,
            scheduledPlatform: scheduledPlatform, departed: departed, arrived: arrived,
            operationalVerified: operationalVerified, providerName: providerName, stale: true
        )
    }

    /// Android's `TrainsWidgetPresenter.forToday`: the run of an earlier day that is over keeps only its
    /// timetable departure, marked with the date of the data, and never counts as running.
    public func forToday(now: Date) -> WidgetTrainStatus {
        guard WidgetTrainsRules.isEarlierRunOver(run, now: now) else { return self }
        return WidgetTrainStatus(
            retrievedAt: retrievedAt, category: category, operatorName: operatorName, origin: origin, destination: destination,
            scheduledDeparture: scheduledDeparture, departure: departure, arrival: arrival, delayMinutes: nil,
            cancelled: false, variation: false, nextStation: nil, nextStationTime: nil, lastDetectionStation: nil,
            lastDetectionTime: nil, platform: scheduledPlatform ?? platform, scheduledPlatform: nil, departed: nil,
            arrived: nil, operationalVerified: false, providerName: providerName, stale: true
        )
    }
}

/// The kinds of widget, for the refresh a tap asks for.
public enum WidgetKindName: String, CaseIterable, Sendable {
    case route = "it.binario.ios.widget.route"
    case station = "it.binario.ios.widget.station"
    case trains = "it.binario.ios.widget.trains"
}

/// The App Group preferences of the widgets. Each value is a small JSON document under its own key;
/// an unreadable one reads as absent.
public struct WidgetSharedStore {
    public static let liveDataKey = "it.binario.ios.widgets.live-data-enabled.v1"
    public static let scheduleFileKey = "it.binario.ios.widgets.schedule-file.v1"
    private static let prefix = "it.binario.ios.widgets."
    /// A snapshot holds at most a day of runs: anything larger was not written by the app.
    private static let maximumBytes = 256 * 1_024

    private let defaults: UserDefaults

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    /// The App Group's store; nil without the App Group.
    public static func shared() -> WidgetSharedStore? {
        WidgetAppGroup.defaults().map { WidgetSharedStore(defaults: $0) }
    }

    // MARK: What the app shares

    /// «Dati in tempo reale», as the app's switch says (on unless the app wrote false).
    public var liveDataEnabled: Bool {
        (defaults.object(forKey: Self.liveDataKey) as? Bool) ?? true
    }

    public func setLiveDataEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.liveDataKey)
    }

    /// The file name of the timetable index the app installed in `WidgetAppGroup.scheduleDirectory()`.
    public var scheduleFileName: String? {
        guard let name = defaults.string(forKey: Self.scheduleFileKey),
              !name.isEmpty, !name.contains("/"), name.hasSuffix(".sqlite") else { return nil }
        return name
    }

    public func setScheduleFileName(_ name: String?) {
        defaults.set(name, forKey: Self.scheduleFileKey)
    }

    /// «I miei treni» as the app keeps them, in the group (`FavouriteTrainStore` under its own key).
    public var favouriteTrains: FavouriteTrainStore { FavouriteTrainStore(defaults: defaults) }

    // MARK: What the widgets keep

    public func routeSnapshot(key: String) -> WidgetRouteSnapshot? { value(WidgetRouteSnapshot.self, "route." + key) }
    public func saveRouteSnapshot(_ snapshot: WidgetRouteSnapshot, key: String) { save(snapshot, "route." + key) }

    public func stationSnapshot(key: String) -> WidgetStationSnapshot? { value(WidgetStationSnapshot.self, "station." + key) }
    public func saveStationSnapshot(_ snapshot: WidgetStationSnapshot, key: String) { save(snapshot, "station." + key) }

    /// The last known state of each favourite train, by its identifier.
    public var trainStatuses: [String: WidgetTrainStatus] { value([String: WidgetTrainStatus].self, "trains.statuses") ?? [:] }
    public func saveTrainStatuses(_ statuses: [String: WidgetTrainStatus]) { save(statuses, "trains.statuses") }

    /// The last refresh of a widget's data, a tap or an automatic one, successful or not.
    public func lastAttempt(key: String) -> Date? { value(Stamp.self, "attempt." + key)?.at }
    public func recordAttempt(key: String, at date: Date) { save(Stamp(at: date), "attempt." + key) }

    /// When the refresh button of a kind of widget was last tapped (Android refreshes every widget of the kind).
    public func refreshRequested(_ kind: WidgetKindName) -> Date? { value(Stamp.self, "refresh." + kind.rawValue)?.at }
    public func requestRefresh(_ kind: WidgetKindName, at date: Date) { save(Stamp(at: date), "refresh." + kind.rawValue) }

    /// The retries of a refresh a tap asked for that failed for a temporary reason: run number and when it failed.
    public func retry(key: String) -> WidgetRetryState? { value(WidgetRetryState.self, "retry." + key) }
    public func saveRetry(_ state: WidgetRetryState?, key: String) {
        guard let state else {
            defaults.removeObject(forKey: Self.prefix + "retry." + key)
            return
        }
        save(state, "retry." + key)
    }

    /// The direction of a route widget: the one shown and a manual choice with when it ends.
    public func direction(routeKey: String) -> WidgetDirectionState {
        value(WidgetDirectionState.self, "direction." + routeKey) ?? WidgetDirectionState(shown: .outbound, manual: nil, manualUntil: nil)
    }

    public func saveDirection(_ state: WidgetDirectionState, routeKey: String) { save(state, "direction." + routeKey) }

    /// The first row a paged widget shows (Android's row offset, kept per widget).
    public func rowOffset(key: String) -> Int { value(Offset.self, "offset." + key)?.rows ?? 0 }
    public func setRowOffset(_ offset: Int, key: String) { save(Offset(rows: max(offset, 0)), "offset." + key) }

    /// The station «📍 Più vicina» chose for the widgets configured with `configuredStationID`, and why a tap kept it.
    public func nearest(configuredStationID: String) -> WidgetNearestState? {
        value(WidgetNearestState.self, "nearest." + configuredStationID)
    }

    public func saveNearest(_ state: WidgetNearestState?, configuredStationID: String) {
        guard let state else {
            defaults.removeObject(forKey: Self.prefix + "nearest." + configuredStationID)
            return
        }
        save(state, "nearest." + configuredStationID)
    }

    /// Scalars are kept inside an object: a JSON document is never a bare number.
    private struct Stamp: Codable {
        let at: Date
    }

    private struct Offset: Codable {
        let rows: Int
    }

    private func value<Value: Decodable>(_ type: Value.Type, _ key: String) -> Value? {
        guard let data = defaults.data(forKey: Self.prefix + key), data.count <= Self.maximumBytes else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return try? decoder.decode(Value.self, from: data)
    }

    private func save<Value: Encodable>(_ value: Value, _ key: String) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        guard let data = try? encoder.encode(value), data.count <= Self.maximumBytes else { return }
        defaults.set(data, forKey: Self.prefix + key)
    }
}

/// The direction a route widget shows, and a manual choice made with its button.
public struct WidgetDirectionState: Codable, Equatable, Sendable {
    public let shown: WidgetDirection
    public let manual: WidgetDirection?
    public let manualUntil: Date?

    public init(shown: WidgetDirection, manual: WidgetDirection?, manualUntil: Date?) {
        self.shown = shown
        self.manual = manual
        self.manualUntil = manualUntil
    }
}

/// A refresh a tap asked for that failed for a temporary reason.
public struct WidgetRetryState: Codable, Equatable, Sendable {
    public let attempt: Int
    public let failedAt: Date

    public init(attempt: Int, failedAt: Date) {
        self.attempt = attempt
        self.failedAt = failedAt
    }
}

/// «📍 Più vicina»: the station chosen, or why the last tap kept the configured one.
public struct WidgetNearestState: Codable, Equatable, Sendable {
    public enum Note: String, Codable, Sendable {
        case permissionDenied
        case locationUnavailable
        case noneNearby
    }

    public let station: StationIdentity?
    public let note: Note?
    public let at: Date

    public init(station: StationIdentity?, note: Note?, at: Date) {
        self.station = station
        self.note = note
        self.at = at
    }
}
