import Foundation

// Live rail data on the iPhone: the same public sources as the Android app, asked in Android's
// order (`LocalOnlyRailDataPolicy`), with the bundled timetable as the fallback of every answer.
// Nothing here is shown or stored by itself: the app overlays these values on its timetable rows.

/// A public source of live rail data, with Android's identifier (`RailSourceSelectionPolicy`) and
/// the name its provider descriptor gives it.
public enum LiveSource: String, CaseIterable, Codable, Hashable, Sendable {
    case rfiStationBoard = "rfi-station-board"
    case viaggiaTreno = "viaggiatreno"
    case italo = "italo-status"
    /// The swiss boards of transport.opendata.ch (`SwissOpenTransportStationBoardAdapter`).
    case swissOpenTransport = "ch-transport-opendata"
    /// The open Trip Updates feeds joined to the bundled timetable (`SharedData.RealtimeFeeds.tripUpdates`).
    case staRealtime = "alto-adige-sta-realtime"
    case sncfRealtime = "sncf-gtfs-rt"
    case trenitaliaFranceRealtime = "trenitalia-france-gtfs-rt"
    /// The community Trip Updates of European Sleeper, joined to the archive downloaded at runtime (remote-gtfs-v1.json).
    case europeanSleeperRealtime = "european-sleeper-gtfs-rt"

    public var displayName: String {
        switch self {
        case .rfiStationBoard: return "RFI — Monitor Arrivi/Partenze"
        case .viaggiaTreno: return "Trenitalia — ViaggiaTreno realtime"
        case .italo: return "Italo in Viaggio"
        case .swissOpenTransport: return "transport.opendata.ch — tabelloni svizzeri"
        case .staRealtime: return "Open Data Hub Alto Adige — GTFS-Realtime"
        case .sncfRealtime: return "SNCF Open Data — GTFS-Realtime"
        case .trenitaliaFranceRealtime: return "Trenitalia France — GTFS-Realtime"
        case .europeanSleeperRealtime: return "European Sleeper / JBB — GTFS-Realtime best-effort"
        }
    }
}

/// Where a live value comes from and how old it is.
public struct LiveObservation: Equatable, Hashable, Sendable {
    public let source: LiveSource
    /// When Binario received the answer.
    public let fetchedAt: Date
    /// When the source says it observed the value, or `fetchedAt` when it does not say.
    public let observedAt: Date
    /// After this the value is no longer shown as live (Android's `SourceRef.expiresAt`).
    public let expiresAt: Date

    public init(source: LiveSource, fetchedAt: Date, observedAt: Date, expiresAt: Date) {
        self.source = source
        self.fetchedAt = fetchedAt
        self.observedAt = observedAt
        self.expiresAt = expiresAt
    }

    /// Android's freshness rule (`RfiLiveRouteDataSource.fresh`): observed in the last five minutes,
    /// not more than a minute ahead of `now`, and not expired.
    public func isFresh(at now: Date) -> Bool {
        observedAt >= now.addingTimeInterval(-LiveRailTime.recentObservationSeconds)
            && observedAt <= now.addingTimeInterval(LiveRailTime.futureToleranceSeconds)
            && expiresAt > now
    }
}

/// A live value with the observation it comes from.
public struct LiveSourced<Value: Equatable & Sendable>: Equatable, Sendable {
    public let value: Value
    public let observation: LiveObservation

    public init(_ value: Value, observation: LiveObservation) {
        self.value = value
        self.observation = observation
    }
}

/// The ViaggiaTreno identity of one run: its origin code, number and the midnight of its
/// departure day in epoch milliseconds (`andamentoTreno/{origin}/{number}/{date}`).
public struct ViaggiaTrenoRunReference: Equatable, Hashable, Sendable {
    public let originCode: String
    public let number: String
    public let departureDateMillis: Int64

    /// Nil unless the values have ViaggiaTreno's shapes: "S" and five digits, one to six digits,
    /// a date of 10 to 16 digits.
    public init?(originCode: String, number: String, departureDateMillis: Int64) {
        guard NetworkReliability.isStationCode(originCode),
              LiveText.isDigits(number, count: 1...6),
              (1_000_000_000...9_999_999_999_999_999).contains(departureDateMillis) else { return nil }
        self.originCode = originCode
        self.number = number
        self.departureDateMillis = departureDateMillis
    }

    /// "S08409:9558:1793746800000", the form Android keeps in `nativeRunIds`.
    public var nativeID: String { "\(originCode):\(number):\(departureDateMillis)" }
}

public enum LiveRunState: String, Equatable, Hashable, Sendable {
    case onTime
    case delayed
    case cancelled
}

/// A run cancelled from origin to destination, or only at some stops (ViaggiaTreno's
/// `actualFermataType` 2 or 3 at a stop, Android's `StopRouteChange.SKIPPED`).
public enum LiveCancellation: String, Equatable, Hashable, Sendable {
    case none
    case partial
    case full
}

/// The last place and time the source observed the train («ultimo rilevamento»).
public struct LiveRunObservationPoint: Equatable, Sendable {
    public let stationName: String?
    public let at: Date
    /// Within Android's five minutes: only then does the published delay count as verified.
    public let isRecent: Bool

    public init(stationName: String?, at: Date, isRecent: Bool) {
        self.stationName = stationName
        self.at = at
        self.isRecent = isRecent
    }
}

/// One stop of a run as the source published it.
public struct LiveStopStatus: Equatable, Sendable {
    /// ViaggiaTreno station code ("S01700"); nil for a source without codes (Italo).
    public let stationCode: String?
    public let stationName: String
    /// The scheduled departure at the first stop, the scheduled arrival at every other one.
    public let scheduled: Date
    /// The scheduled departure at an intermediate stop, when the source publishes it.
    public let scheduledDeparture: Date?
    /// The actual time if any, else `scheduled` plus the delay when the delay is verified, else `scheduled`.
    public let expected: Date
    /// The actual departure at the first stop, the actual arrival at every other one.
    public let actual: Date?
    /// The actual departure at an intermediate stop, when the source publishes it.
    public let actualDeparture: Date?
    public let platform: String?
    /// The timetable platform the source publishes next to `platform`.
    public let scheduledPlatform: String?
    /// Minutes between `scheduled` and `expected` (Android's `TrainStop.delayMinutes`).
    public let delayMinutes: Int
    /// The train has passed the stop: the source published an actual time.
    public let passed: Bool
    /// The source cancelled the stop.
    public let skipped: Bool
    /// `expected` is the source's (an actual time or a verified delay), not the timetable's.
    public let operationalStatusVerified: Bool
    /// An extraordinary stop the timetable does not have (Android's `StopRouteChange.ADDED`): the
    /// train calls there, it is never a cancelled stop.
    public let added: Bool

    public init(
        stationCode: String?, stationName: String, scheduled: Date, scheduledDeparture: Date?, expected: Date,
        actual: Date?, actualDeparture: Date?, platform: String?, scheduledPlatform: String?, delayMinutes: Int,
        passed: Bool, skipped: Bool, operationalStatusVerified: Bool, added: Bool = false
    ) {
        self.stationCode = stationCode
        self.stationName = stationName
        self.scheduled = scheduled
        self.scheduledDeparture = scheduledDeparture
        self.expected = expected
        self.actual = actual
        self.actualDeparture = actualDeparture
        self.platform = platform
        self.scheduledPlatform = scheduledPlatform
        self.delayMinutes = delayMinutes
        self.passed = passed
        self.skipped = skipped
        self.operationalStatusVerified = operationalStatusVerified
        self.added = added
    }
}

/// The status of one run, from the train number lookup (Android's `TrainNumberMatch.service`).
public struct LiveRunStatus: Equatable, Identifiable, Sendable {
    /// Android's identifier: "viaggiatreno:S08409:9558:1793746800000", "italo:9920:<millis>".
    public let id: String
    public let number: String
    /// The published category ("FR", "REG", "Italo"); empty when the source gives none.
    public let category: String
    public let operatorName: String
    /// Android's operator identifier: "trenitalia", "trenord" or "italo".
    public let operatorID: String
    public let originCode: String?
    public let originName: String
    public let destinationName: String
    public let scheduledDeparture: Date
    public let expectedDeparture: Date
    public let platform: String?
    public let scheduledPlatform: String?
    public let state: LiveRunState
    public let cancellation: LiveCancellation
    /// The published delay in minutes, only when `operationalStatusVerified` (Android's
    /// `officialDelayMinutes`): a missing or old observation never becomes «0 minutes».
    public let delayMinutes: Int?
    /// Android's `operationalStatusVerified`: cancelled, or a delay with a recent observation of a
    /// train that has left.
    public let operationalStatusVerified: Bool
    public let lastObservation: LiveRunObservationPoint?
    public let stops: [LiveStopStatus]
    public let observation: LiveObservation
    /// The ViaggiaTreno identity of the run, to ask it again without the number search.
    public let viaggiaTrenoRun: ViaggiaTrenoRunReference?

    public init(
        id: String, number: String, category: String, operatorName: String, operatorID: String,
        originCode: String?, originName: String, destinationName: String, scheduledDeparture: Date,
        expectedDeparture: Date, platform: String?, scheduledPlatform: String?, state: LiveRunState,
        cancellation: LiveCancellation, delayMinutes: Int?, operationalStatusVerified: Bool,
        lastObservation: LiveRunObservationPoint?, stops: [LiveStopStatus], observation: LiveObservation,
        viaggiaTrenoRun: ViaggiaTrenoRunReference?
    ) {
        self.id = id
        self.number = number
        self.category = category
        self.operatorName = operatorName
        self.operatorID = operatorID
        self.originCode = originCode
        self.originName = originName
        self.destinationName = destinationName
        self.scheduledDeparture = scheduledDeparture
        self.expectedDeparture = expectedDeparture
        self.platform = platform
        self.scheduledPlatform = scheduledPlatform
        self.state = state
        self.cancellation = cancellation
        self.delayMinutes = delayMinutes
        self.operationalStatusVerified = operationalStatusVerified
        self.lastObservation = lastObservation
        self.stops = stops
        self.observation = observation
        self.viaggiaTrenoRun = viaggiaTrenoRun
    }

    /// The key the bundled timetable rows are matched with ("2153" for "RE_11 - 2153").
    public var numberKey: String { NetworkReliability.numberKey(number) }
}

public enum LiveDepartureStatus: String, Equatable, Hashable, Sendable {
    case onTime
    case delayed
    case cancelled
}

/// Which board of a station: the trains leaving it or the trains arriving (Android's `BoardKind`).
public enum LiveBoardKind: String, CaseIterable, Hashable, Sendable {
    case departures
    case arrivals
}

/// One train of a live departure or arrivals board, from one source or merged from several.
public struct LiveDeparture: Equatable, Identifiable, Sendable {
    /// "<source>:<number>:<yyyy-MM-dd>", Android's `StationBoardEntry.runId`.
    public let id: String
    public let number: String
    /// Android's operator identifier ("trenitalia", "trenord", "italo"), nil when unknown.
    public let operatorID: String?
    /// The category as the source publishes it ("Frecciarossa", "REG"), possibly empty.
    public let category: String
    /// The train's destination as the source publishes it; on an arrivals board, its origin
    /// (Android's `destinationOrOrigin`).
    public let destination: String
    /// The scheduled departure; on an arrivals board, the scheduled arrival.
    public let scheduled: Date
    public let expected: LiveSourced<Date>?
    public let platform: LiveSourced<String>?
    /// The timetable platform published by the same source as `platform`, never on its own.
    public let scheduledPlatform: String?
    public let status: LiveSourced<LiveDepartureStatus>?
    /// The sources the row was built from, in Android's provider order.
    public let sources: [LiveSource]
    /// The ViaggiaTreno identity of the run, when ViaggiaTreno listed it.
    public let viaggiaTrenoRun: ViaggiaTrenoRunReference?
    /// The board the row comes from (for a merged row, the source chosen for the row itself).
    public let observation: LiveObservation

    public init(
        id: String, number: String, operatorID: String?, category: String, destination: String, scheduled: Date,
        expected: LiveSourced<Date>?, platform: LiveSourced<String>?, scheduledPlatform: String?,
        status: LiveSourced<LiveDepartureStatus>?, sources: [LiveSource], viaggiaTrenoRun: ViaggiaTrenoRunReference?,
        observation: LiveObservation
    ) {
        self.id = id
        self.number = number
        self.operatorID = operatorID
        self.category = category
        self.destination = destination
        self.scheduled = scheduled
        self.expected = expected
        self.platform = platform
        self.scheduledPlatform = scheduledPlatform
        self.status = status
        self.sources = sources
        self.viaggiaTrenoRun = viaggiaTrenoRun
        self.observation = observation
    }

    /// The key the bundled timetable rows are matched with ("2153" for "RE_11 - 2153").
    public var numberKey: String { NetworkReliability.numberKey(number) }

    /// Minutes between the scheduled and the expected time, nil without an expected time.
    public var delayMinutes: Int? {
        guard let expected else { return nil }
        return LiveRailTime.wholeMinutes(from: scheduled, to: expected.value)
    }

    /// «RITARDO» without minutes (RFI boards): late, with no expected time; never on time.
    public var isLateWithoutMinutes: Bool { expected == nil && status?.value == .delayed }
}

/// Why a source, or every source, gave no live value. Every failure ends as one of these.
public enum LiveUnavailableReason: Equatable, Hashable, Sendable {
    /// The number or the station is not one the sources can be asked about.
    case invalidRequest
    /// No source covers the station or the train (no code, no catalogue entry, not today).
    case notCovered
    /// The sources answered and know no such train.
    case notFound
    /// The source refused (HTTP 403 or 429) or failed repeatedly: it is not asked again until then.
    case paused(until: Date)
    /// The source refused the request (HTTP 403 or 429).
    case refused
    case sourceUnavailable
    case unreadable
    case cancelled
}

/// What one source answered: values, possibly incomplete, or why there are none.
public enum LiveSourceAnswer<Value: Equatable & Sendable>: Equatable, Sendable {
    case answered(Value, complete: Bool)
    case unavailable(LiveUnavailableReason)
}

/// A train status lookup: the number, the service day and, when known, a station of the run.
public struct LiveTrainStatusRequest: Equatable, Sendable {
    public let number: String
    /// Any instant of the service day, read in Italian time.
    public let serviceDate: Date
    /// The railway code of a station of the run (its origin or the boarding station), to pick
    /// one run among several with the same number and to ask ViaggiaTreno only for that origin.
    public let stationCode: String?
    /// The name of the run's origin, Android's `ServiceMonitor.origin`, used when no code decides.
    public let originName: String?
    /// A run already identified (a ViaggiaTreno board row): asked directly, without the search.
    public let viaggiaTrenoRun: ViaggiaTrenoRunReference?

    public init(
        number: String, serviceDate: Date, stationCode: String? = nil, originName: String? = nil,
        viaggiaTrenoRun: ViaggiaTrenoRunReference? = nil
    ) {
        self.number = number
        self.serviceDate = serviceDate
        self.stationCode = NetworkReliability.isStationCode(stationCode) ? stationCode : nil
        self.originName = originName
        self.viaggiaTrenoRun = viaggiaTrenoRun
    }
}

/// Every run found for a number, in Android's order (`TrainNumberSearchService`).
public struct LiveTrainStatusLookup: Equatable, Sendable {
    public let runs: [LiveRunStatus]
    /// The sources that were asked and gave nothing usable.
    public let unavailableSources: [LiveSource: LiveUnavailableReason]
    /// False when a source answered only in part (a run detail failed or ran out of time).
    public let isComplete: Bool

    public init(runs: [LiveRunStatus], unavailableSources: [LiveSource: LiveUnavailableReason], isComplete: Bool) {
        self.runs = runs
        self.unavailableSources = unavailableSources
        self.isComplete = isComplete
    }
}

public enum LiveTrainStatusResult: Equatable, Sendable {
    case available(LiveTrainStatusLookup)
    case unavailable(LiveUnavailableReason)
}

/// A departure board request for a catalogue station: its railway code (ViaggiaTreno), its name
/// and aliases (RFI and Italo catalogues).
public struct LiveDeparturesRequest: Equatable, Sendable {
    public let station: Station
    /// The board starts here (Android: ten minutes earlier, and two hours earlier for late trains).
    public let from: Date
    /// The departures, or the arrivals (RFI and ViaggiaTreno; Italo lists departures only).
    public let kind: LiveBoardKind

    public init(station: Station, from: Date, kind: LiveBoardKind = .departures) {
        self.station = station
        self.from = from
        self.kind = kind
    }
}

/// The window one source is asked for: Android's `StationBoardRequest`.
public struct LiveBoardWindow: Equatable, Sendable {
    public let station: Station
    public let from: Date
    public let to: Date
    /// A late train scheduled from here stays while its expected time is still ahead.
    public let lateSince: Date?
    public let kind: LiveBoardKind

    public init(station: Station, from: Date, to: Date, lateSince: Date?, kind: LiveBoardKind = .departures) {
        self.station = station
        self.from = from
        self.to = to
        self.lateSince = lateSince
        self.kind = kind
    }

    /// Android's `boardWindowKeeps`.
    public func keeps(scheduled: Date, expected: Date?) -> Bool {
        if scheduled > to { return false }
        if scheduled >= from { return true }
        guard let lateSince else { return false }
        return scheduled >= lateSince && expected.map { $0 >= from } == true
    }
}

public enum LiveBoardWarning: String, Equatable, Hashable, Sendable {
    /// A source failed: some rows may lack their live values.
    case realtimeUnavailable
    /// Two rows have the same number, time and destination but different operators or runs.
    case identityAmbiguous
    /// The sources disagree on an expected time, a platform or a status.
    case sourceConflict
}

/// A merged live departure board.
public struct LiveDepartureBoard: Equatable, Sendable {
    public let stationID: String
    public let from: Date
    public let to: Date
    /// Sorted by expected time, else scheduled time.
    public let departures: [LiveDeparture]
    /// The sources whose boards were used, in Android's provider order.
    public let sources: [LiveSource]
    public let unavailableSources: [LiveSource: LiveUnavailableReason]
    public let warnings: Set<LiveBoardWarning>
    public let fetchedAt: Date
    /// The departures board, or the arrivals board (its rows name the trains' origins).
    public let kind: LiveBoardKind

    public init(
        stationID: String, from: Date, to: Date, departures: [LiveDeparture], sources: [LiveSource],
        unavailableSources: [LiveSource: LiveUnavailableReason], warnings: Set<LiveBoardWarning>, fetchedAt: Date,
        kind: LiveBoardKind = .departures
    ) {
        self.stationID = stationID
        self.from = from
        self.to = to
        self.departures = departures
        self.sources = sources
        self.unavailableSources = unavailableSources
        self.warnings = warnings
        self.fetchedAt = fetchedAt
        self.kind = kind
    }
}

public enum LiveDeparturesResult: Equatable, Sendable {
    case available(LiveDepartureBoard)
    case unavailable(LiveUnavailableReason)
}

/// A row of a live board to open, Android's `StationBoardRunDetailProvider` request: the board's
/// station and kind, the row's number, scheduled time and operator, and the ViaggiaTreno identity
/// of its run when ViaggiaTreno listed it.
public struct LiveBoardRunRequest: Equatable, Sendable {
    public let station: Station
    public let kind: LiveBoardKind
    public let number: String
    /// The scheduled departure, or arrival on an arrivals board, at `station`.
    public let scheduled: Date
    public let operatorID: String?
    public let viaggiaTrenoRun: ViaggiaTrenoRunReference?

    public init(
        station: Station, kind: LiveBoardKind, number: String, scheduled: Date, operatorID: String? = nil,
        viaggiaTrenoRun: ViaggiaTrenoRunReference? = nil
    ) {
        self.station = station
        self.kind = kind
        self.number = number
        self.scheduled = scheduled
        self.operatorID = operatorID
        self.viaggiaTrenoRun = viaggiaTrenoRun
    }

    /// The request of a board row at `station`.
    public init(_ row: LiveDeparture, station: Station, kind: LiveBoardKind) {
        self.init(
            station: station, kind: kind, number: row.number, scheduled: row.scheduled, operatorID: row.operatorID,
            viaggiaTrenoRun: row.viaggiaTrenoRun
        )
    }
}

/// The run of a board row (Android's `StationBoardRunDetails`): every stop as the source published
/// it, and the stop at the board's station, where the row's expected time, platform and state are.
public struct LiveBoardRunDetails: Equatable, Sendable {
    public let kind: LiveBoardKind
    public let run: LiveRunStatus
    public let stop: LiveStopStatus

    public init(kind: LiveBoardKind, run: LiveRunStatus, stop: LiveStopStatus) {
        self.kind = kind
        self.run = run
        self.stop = stop
    }
}

public enum LiveBoardRunResult: Equatable, Sendable {
    case available(LiveBoardRunDetails)
    case unavailable(LiveUnavailableReason)
}

/// The notices of one train (Android's `TrainNoticeRequest`): its number, service day and, when
/// known, the ViaggiaTreno code of its origin.
public struct LiveTrainNoticesRequest: Equatable, Sendable {
    public let number: String
    /// Any instant of the service day, read in Italian time.
    public let serviceDate: Date
    public let originCode: String?

    public init(number: String, serviceDate: Date, originCode: String? = nil) {
        self.number = number
        self.serviceDate = serviceDate
        self.originCode = originCode
    }
}

/// A notice ViaggiaTreno publishes for one train («smart caring», Android's `TrainNotice`).
public struct LiveTrainNotice: Equatable, Identifiable, Sendable {
    /// "viaggiatreno-train-notices:<id>".
    public let id: String
    public let text: String
    public let englishText: String?
    /// The Italian days the notice is valid on, from the start of the first to the start of the last.
    public let validFrom: Date
    public let validUntil: Date
    public let publishedAt: Date?
    /// The strike badge: a text of the notice mentions «sciopero» or «scioperi».
    public let mentionsStrike: Bool

    public init(
        id: String, text: String, englishText: String?, validFrom: Date, validUntil: Date, publishedAt: Date?,
        mentionsStrike: Bool = false
    ) {
        self.id = id
        self.text = text
        self.englishText = englishText
        self.validFrom = validFrom
        self.validUntil = validUntil
        self.publishedAt = publishedAt
        self.mentionsStrike = mentionsStrike
    }

    /// The publisher's English text for an app language other than Italian when it has one, else
    /// the Italian text (Android's `textFor`).
    public func text(for languageCode: String) -> String {
        guard languageCode.lowercased() != "it", let english = englishText,
              !english.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return text }
        return english
    }
}

/// The notices of one train, newest first, and whether they are an earlier copy kept after a failed read.
public struct LiveTrainNotices: Equatable, Sendable {
    public let notices: [LiveTrainNotice]
    public let stale: Bool

    public init(notices: [LiveTrainNotice], stale: Bool) {
        self.notices = notices
        self.stale = stale
    }
}

public enum LiveTrainNoticesResult: Equatable, Sendable {
    case available(LiveTrainNotices)
    case unavailable(LiveUnavailableReason)
}

/// Train status by number from one source.
public protocol LiveTrainStatusSource: Sendable {
    var source: LiveSource { get }
    func runs(for request: LiveTrainStatusRequest) async -> LiveSourceAnswer<[LiveRunStatus]>
}

/// Departures of a station from one source.
public protocol LiveDepartureBoardSource: Sendable {
    var source: LiveSource { get }
    func departures(for window: LiveBoardWindow) async -> LiveSourceAnswer<[LiveDeparture]>
}

/// The run of a board row from one source (Android's `StationBoardRunDetailProvider`).
public protocol LiveBoardRunSource: Sendable {
    var source: LiveSource { get }
    func boardRun(_ request: LiveBoardRunRequest) async -> LiveSourceAnswer<LiveBoardRunDetails>
}

/// The notices of a train from one source (Android's `TrainNoticeProvider`).
public protocol LiveTrainNoticeSource: Sendable {
    func trainNotices(_ request: LiveTrainNoticesRequest) async -> LiveSourceAnswer<LiveTrainNotices>
}

/// Train status by number, all sources together, and the notices published for a train. Never
/// throws; cancellable.
public protocol LiveTrainStatusProviding: Sendable {
    func trainStatus(_ request: LiveTrainStatusRequest) async -> LiveTrainStatusResult
    func trainNotices(_ request: LiveTrainNoticesRequest) async -> LiveTrainNoticesResult
}

extension LiveTrainStatusProviding {
    /// No notice source: nothing is asked.
    public func trainNotices(_ request: LiveTrainNoticesRequest) async -> LiveTrainNoticesResult {
        .unavailable(.notCovered)
    }
}

/// Departures or arrivals of a station, all sources together, and the run of one of its rows.
/// Never throws; cancellable.
public protocol LiveDeparturesProviding: Sendable {
    func departures(_ request: LiveDeparturesRequest) async -> LiveDeparturesResult
    func boardRun(_ request: LiveBoardRunRequest) async -> LiveBoardRunResult
    /// The live route of the direct trains of a search of today (LiveRoute.swift).
    func liveRoute(_ request: LiveRouteRequest) async -> LiveRouteResult
}

extension LiveDeparturesProviding {
    /// No source of run details: nothing is asked.
    public func boardRun(_ request: LiveBoardRunRequest) async -> LiveBoardRunResult {
        .unavailable(.notCovered)
    }

    /// No live route source: nothing is asked.
    public func liveRoute(_ request: LiveRouteRequest) async -> LiveRouteResult {
        .unavailable(.notCovered)
    }
}

/// Italian railway time: Europe/Rome days and Android's recency limits.
enum LiveRailTime {
    /// An observation older than this is not a live one (Android: 300 seconds).
    static let recentObservationSeconds: TimeInterval = 300
    /// An observation this far ahead of the clock is still accepted (Android: 60 seconds).
    static let futureToleranceSeconds: TimeInterval = 60

    static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? TimeZone(secondsFromGMT: 3_600) ?? TimeZone.current
        return calendar
    }()

    /// Android's `recent`: from five minutes before `fetched` to a minute after it.
    static func isRecent(_ observed: Date, fetched: Date) -> Bool {
        observed >= fetched.addingTimeInterval(-recentObservationSeconds)
            && observed <= fetched.addingTimeInterval(futureToleranceSeconds)
    }

    static func date(millis: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1_000)
    }

    static func millis(_ date: Date) -> Int64 {
        let milliseconds: Double = (date.timeIntervalSince1970 * 1_000).rounded()
        return Int64(milliseconds)
    }

    /// The start of the Italian day of `date`.
    static func startOfDay(_ date: Date) -> Date {
        calendar.startOfDay(for: date)
    }

    /// Whole days from the Italian day of `from` to the Italian day of `to`.
    static func days(from: Date, to: Date) -> Int {
        calendar.dateComponents([.day], from: startOfDay(from), to: startOfDay(to)).day ?? 0
    }

    static func sameDay(_ first: Date, _ second: Date) -> Bool {
        calendar.isDate(first, inSameDayAs: second)
    }

    /// "2026-10-16", the Italian day of `date`.
    static func isoDay(_ date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        let year = LiveText.padded(parts.year ?? 0, width: 4)
        let month = LiveText.padded(parts.month ?? 0, width: 2)
        let day = LiveText.padded(parts.day ?? 0, width: 2)
        return "\(year)-\(month)-\(day)"
    }

    /// "10:05", the Italian clock time of `date`.
    static func hhmm(_ date: Date) -> String {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return LiveText.padded(parts.hour ?? 0, width: 2) + ":" + LiveText.padded(parts.minute ?? 0, width: 2)
    }

    /// `hour`:`minute` on the Italian day of `day`, nil for an impossible time.
    static func date(onDayOf day: Date, hour: Int, minute: Int, second: Int = 0) -> Date? {
        guard (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else { return nil }
        return calendar.date(bySettingHour: hour, minute: minute, second: second, of: startOfDay(day))
    }

    static func addingDays(_ days: Int, to date: Date) -> Date? {
        calendar.date(byAdding: .day, value: days, to: date)
    }

    /// Java's `Duration.toMinutes()`: whole minutes, truncated towards zero.
    static func wholeMinutes(from start: Date, to end: Date) -> Int {
        let minutes: Double = (end.timeIntervalSince(start) / 60).rounded(.towardZero)
        guard minutes.isFinite, abs(minutes) < 1_000_000 else { return 0 }
        return Int(minutes)
    }

    /// "H:mm" or "HH:mm" (also with seconds) as hour and minute, nil otherwise.
    static func clock(_ value: String) -> (hour: Int, minute: Int)? {
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3,
              (1...2).contains(parts[0].count), parts[1].count == 2,
              let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        if parts.count == 3 {
            guard parts[2].count >= 2, Int(parts[2].prefix(2)).map({ (0...59).contains($0) }) == true else { return nil }
        }
        return (hour, minute)
    }
}
