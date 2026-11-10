import Foundation

/// The place of a train in «Partenze»: its scheduled departure, then its number, then its
/// identifier, as the planner orders its results. A page continues strictly after the cursor of
/// the last train already listed.
public struct StationBoardCursor: Comparable, Hashable, Sendable {
    public let departure: Date
    public let number: String
    public let id: String

    public init(departure: Date, number: String, id: String) {
        self.departure = departure
        self.number = number
        self.id = id
    }

    public static func < (lhs: StationBoardCursor, rhs: StationBoardCursor) -> Bool {
        if lhs.departure != rhs.departure { return lhs.departure < rhs.departure }
        if lhs.number != rhs.number { return lhs.number < rhs.number }
        return lhs.id < rhs.id
    }
}

/// What «Partenze» asks the bundled timetable: the trains leaving `station` from `from`, at most
/// `limit` of them, after `after` when the page continues a list («Più tardi»).
public struct StationBoardQuery: Equatable, Sendable {
    /// A page never lists more trains than this, whatever is asked.
    public static let maximumPageSize = 50

    public let station: StationIdentity
    public let from: Date
    /// Between 1 and `maximumPageSize`.
    public let limit: Int
    public let after: StationBoardCursor?

    /// Throws for a station identity the planner would refuse (empty, too long, not normalised).
    public init(station: StationIdentity, from: Date, limit: Int, after: StationBoardCursor? = nil) throws {
        try JourneyModelValidator.identity(station)
        self.station = station
        self.from = from
        self.limit = min(max(limit, 1), Self.maximumPageSize)
        self.after = after
    }

    /// The start of the minute of `date`: the board lists the trains of the current minute, which
    /// can be taken until a minute after their scheduled departure
    /// (`ReliableAlternatives.departedGraceSeconds`). Rome's offset is whole minutes.
    public static func minuteStart(of date: Date) -> Date {
        let seconds = date.timeIntervalSince1970
        return Date(timeIntervalSince1970: (seconds / 60).rounded(.down) * 60)
    }
}

/// What a live source observed of a listed train, kept apart from its scheduled data. The bundled
/// timetable never sets it; a live provider (ViaggiaTreno or RFI boards, a later step) does, for the
/// trains it has observed, and leaves the others scheduled.
public struct StationBoardLiveStatus: Equatable, Sendable {
    /// The expected departure, when the source publishes one.
    public let expectedDeparture: Date?
    /// The delay at the station in whole minutes, when the source publishes it.
    public let delayMinutes: Int?
    public let cancelled: Bool
    /// The actual platform, when the source publishes it.
    public let platform: String?
    /// The source as the board names it.
    public let sourceLabel: String
    /// When the source observed the train.
    public let observedAt: Date

    public init(
        expectedDeparture: Date?,
        delayMinutes: Int?,
        cancelled: Bool,
        platform: String?,
        sourceLabel: String,
        observedAt: Date
    ) {
        self.expectedDeparture = expectedDeparture
        self.delayMinutes = delayMinutes
        self.cancelled = cancelled
        self.platform = platform
        self.sourceLabel = sourceLabel
        self.observedAt = observedAt
    }
}

/// One train of «Partenze»: when it leaves the board's station, the train as the planner rows show
/// it (number, category, operator, the trip's last stop as destination, scheduled "HH:mm", bikes;
/// never a delay, a platform the timetable does not publish or a live state), the identity of its
/// last stop, where the train detail measures it, and what a live source observed of it, if any.
public struct StationBoardDeparture: Equatable, Identifiable, Sendable {
    /// The scheduled departure from the board's station.
    public let departure: Date
    public let service: TrainService
    /// The catalogue station of the trip's last stop or, for a stop no catalogue station is bound
    /// to, an identity made of its pack and its own stop identifier ("gtfs:<pack>:<stop>").
    public let terminus: StationIdentity
    /// Nil for a row of the scheduled timetable: the only kind the bundled provider makes.
    public let live: StationBoardLiveStatus?

    public init(departure: Date, service: TrainService, terminus: StationIdentity, live: StationBoardLiveStatus? = nil) {
        self.departure = departure
        self.service = service
        self.terminus = terminus
        self.live = live
    }

    public var id: String { service.id }

    /// The row comes from the scheduled timetable: the board labels it «orario programmato».
    public var isScheduled: Bool { live == nil }

    /// Still listed at `now`: until a minute after its scheduled departure, as the planner counts a
    /// train still to be taken (`ReliableAlternatives.stillToDepart`).
    public func stillToLeave(at now: Date) -> Bool {
        ReliableAlternatives.stillToDepart(departure, now: now)
    }

    public var cursor: StationBoardCursor {
        StationBoardCursor(departure: departure, number: service.number, id: service.id)
    }
}

/// One page of «Partenze», in departure order.
public struct StationBoardPage: Equatable, Sendable {
    public let query: StationBoardQuery
    public let departures: [StationBoardDeparture]
    /// Further trains of the same service days follow the last one («Più tardi»).
    public let hasMore: Bool
    /// On a first page without any train left today: the first train of the next service day
    /// («Non ci sono più treni oggi: primo domani alle …»); nil otherwise.
    public let firstDepartureNextDay: StationBoardDeparture?
    /// The attribution of the feeds of the listed trains, each once, in order of appearance.
    public let attributions: [String]

    public init(
        query: StationBoardQuery,
        departures: [StationBoardDeparture],
        hasMore: Bool,
        firstDepartureNextDay: StationBoardDeparture?,
        attributions: [String]
    ) {
        self.query = query
        self.departures = departures
        self.hasMore = hasMore
        self.firstDepartureNextDay = firstDepartureNextDay
        self.attributions = attributions
    }
}

/// A page, or why the board cannot be shown: `noJourney` for a station no stop of the index is
/// bound to, `scheduleExpired` when the timetables of the station end before today, and
/// `noAuthorizedProvider` / `providerInvalid` without a valid index.
public enum StationBoardResult: Equatable, Sendable {
    case available(StationBoardPage)
    case unavailable(JourneyUnavailableReason)
}

/// Where «Partenze» gets its trains. The first provider is the bundled timetable
/// (`SQLiteGTFSJourneyRepository`), which stays the fallback of any live provider added later: such
/// a provider returns the same pages, with `StationBoardDeparture.live` set on the trains it
/// observed. Implementations do their work off the main actor.
public protocol StationBoardProvider: Sendable {
    func departures(for query: StationBoardQuery) async -> StationBoardResult
}

/// Safe fallback while no validated index is installed: it never synthesizes trains.
public struct FailClosedStationBoardProvider: StationBoardProvider {
    public init() {}

    public func departures(for query: StationBoardQuery) async -> StationBoardResult {
        .unavailable(.noAuthorizedProvider)
    }
}

/// The station last shown in «Partenze», in the app's `UserDefaults` on this iPhone only, so the
/// board opens on it again: its catalogue identity (identifier, name, railway code) as JSON under
/// `storageKey`. Nothing is synchronised or sent; Apple system backups may include it. A value
/// that is not a valid station identity, or larger than any the app writes, reads as none.
public final class StationBoardStationStore {
    public static let storageKey = "it.binario.ios.departures-station.v1"
    /// One identity takes a few hundred bytes.
    public static let maximumStoredBytes = 2 * 1_024

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func load() -> StationIdentity? {
        guard let data = defaults.data(forKey: Self.storageKey), data.count <= Self.maximumStoredBytes,
              let station = try? JSONDecoder().decode(StationIdentity.self, from: data),
              Self.isValid(station)
        else { return nil }
        return station
    }

    /// Keeps `station` in place of the previous one; an invalid identity is not written.
    public func save(_ station: StationIdentity) {
        guard Self.isValid(station), let data = try? JSONEncoder().encode(station) else { return }
        defaults.set(data, forKey: Self.storageKey)
    }

    static func isValid(_ station: StationIdentity) -> Bool {
        do {
            try JourneyModelValidator.identity(station)
            return true
        } catch {
            return false
        }
    }
}
