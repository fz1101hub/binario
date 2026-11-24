import Foundation

/// What a leg of a journey travels on (Android's `LegMode`): a vehicle of the timetable, or a walk.
public enum JourneyLegMode: String, Codable, Hashable, Sendable {
    case train = "TRAIN"
    case bus = "BUS"
    case replacementBus = "REPLACEMENT_BUS"
    case metro = "METRO"
    case tram = "TRAM"
    case funicular = "FUNICULAR"
    case walk = "WALK"

    init(_ mode: TimetableLegMode) {
        switch mode {
        case .train: self = .train
        case .bus: self = .bus
        case .replacementBus: self = .replacementBus
        case .metro: self = .metro
        case .tram: self = .tram
        case .funicular: self = .funicular
        }
    }

    /// The timetable's mode of a vehicle leg; nil for a walk.
    public var timetableMode: TimetableLegMode? {
        switch self {
        case .train: return .train
        case .bus: return .bus
        case .replacementBus: return .replacementBus
        case .metro: return .metro
        case .tram: return .tram
        case .funicular: return .funicular
        case .walk: return nil
        }
    }
}

/// Wheelchair access of a leg as the timetables publish it (Android's `LegWheelchairAccess`): the vehicle and the two
/// stops, each true only when published as accessible.
public struct JourneyLegWheelchair: Codable, Hashable, Sendable {
    public let vehicle: Bool
    public let boarding: Bool
    public let alighting: Bool

    public init(vehicle: Bool, boarding: Bool, alighting: Bool) {
        self.vehicle = vehicle
        self.boarding = boarding
        self.alighting = alighting
    }

    public var accessible: Bool { vehicle && boarding && alighting }
}

/// «Resta a bordo»: at `stationLabel` the vehicle of the leg goes on as another run (a GTFS block, or the same train in
/// another pack).
public struct JourneyInSeatContinuation: Codable, Hashable, Sendable {
    public let stationID: String
    public let stationLabel: String
    public let runID: String
    public let publicNumber: String?
    public let lineLabel: String?
    public let operatorName: String?
    public let serviceKind: JourneyServiceKind

    public init(stationID: String, stationLabel: String, runID: String, publicNumber: String?, lineLabel: String?,
                operatorName: String?, serviceKind: JourneyServiceKind) {
        self.stationID = stationID
        self.stationLabel = stationLabel
        self.runID = runID
        self.publicNumber = publicNumber
        self.lineLabel = lineLabel
        self.operatorName = operatorName
        self.serviceKind = serviceKind
    }
}

/// One leg of a journey (Android's `JourneyLeg`): a vehicle or a walk, with scheduled times only.
public struct JourneyPlanLeg: Codable, Hashable, Sendable {
    public let mode: JourneyLegMode
    /// `<pack>:<trip>:<yyyy-MM-dd>` of a vehicle leg; nil for a walk.
    public let runID: String?
    /// The pack and the trip of a vehicle leg, for its live data.
    public let sourceID: String?
    public let tripID: String?
    /// A catalogue station id, or `gtfs:<pack>:<stop>` for a stop the catalogue does not know.
    public let originID: String
    public let originLabel: String
    public let destinationID: String
    public let destinationLabel: String
    public let departure: Date
    public let arrival: Date
    /// The public number of a train («2153»); nil for a city line and a walk.
    public let publicNumber: String?
    /// The trip publishes its own number: only then is it asked to the live sources.
    public let hasTrainNumber: Bool
    /// The line or category shown before the number («RE11», «FR», «M1»).
    public let lineLabel: String?
    public let operatorName: String?
    public let serviceKind: JourneyServiceKind
    /// GTFS service day of the run (yyyymmdd); nil for a walk.
    public let serviceDate: Int?
    public let wheelchair: JourneyLegWheelchair?
    /// Bikes on board as the timetable publishes them; nil when unknown and for a walk.
    public let bikesAllowed: Bool?
    public let continuations: [JourneyInSeatContinuation]
    /// The change time the planner required where this vehicle is boarded, so the live check asks the same.
    public let changeMinutes: Int?

    public init(
        mode: JourneyLegMode, runID: String?, sourceID: String?, tripID: String?, originID: String, originLabel: String,
        destinationID: String, destinationLabel: String, departure: Date, arrival: Date, publicNumber: String?,
        hasTrainNumber: Bool, lineLabel: String?, operatorName: String?, serviceKind: JourneyServiceKind, serviceDate: Int?,
        wheelchair: JourneyLegWheelchair?, bikesAllowed: Bool?, continuations: [JourneyInSeatContinuation], changeMinutes: Int?
    ) {
        self.mode = mode
        self.runID = runID
        self.sourceID = sourceID
        self.tripID = tripID
        self.originID = originID
        self.originLabel = originLabel
        self.destinationID = destinationID
        self.destinationLabel = destinationLabel
        self.departure = departure
        self.arrival = arrival
        self.publicNumber = publicNumber
        self.hasTrainNumber = hasTrainNumber
        self.lineLabel = lineLabel
        self.operatorName = operatorName
        self.serviceKind = serviceKind
        self.serviceDate = serviceDate
        self.wheelchair = wheelchair
        self.bikesAllowed = bikesAllowed
        self.continuations = continuations
        self.changeMinutes = changeMinutes
    }

    public var isWalk: Bool { mode == .walk }

    /// The vehicle the leg is shown as (a bus label, a city line); nil for a walk.
    public var vehicle: TimetableVehicle? {
        mode.timetableMode.map { TimetableVehicle.of(mode: $0, operatorName: operatorName, lineLabel: lineLabel) }
    }

    public var durationMinutes: Int { max(Int(arrival.timeIntervalSince(departure) / 60), 0) }

    /// The kind of service of a vehicle leg (preferred and avoided kinds).
    public var travelMode: TravelMode? {
        mode.timetableMode.flatMap { TravelMode.of(mode: $0, serviceKind: serviceKind, operatorName: operatorName) }
    }
}

/// One journey of the offline planner (Android's `JourneyOption`).
public struct JourneyPlanOption: Codable, Hashable, Identifiable, Sendable {
    public let id: String
    public let originID: String
    public let destinationID: String
    public let departure: Date
    public let arrival: Date
    public let legs: [JourneyPlanLeg]
    /// The packs whose trains the journey uses.
    public let sourceIDs: [String]
    /// The last departure of the requested service day in these timetables («Ultimo di oggi»).
    public var lastOfServiceDay: Bool
    /// Nothing left in the searched window: this is the first option after it.
    public var firstAfterEmptyWindow: Bool

    public init(id: String, originID: String, destinationID: String, departure: Date, arrival: Date, legs: [JourneyPlanLeg],
                sourceIDs: [String], lastOfServiceDay: Bool = false, firstAfterEmptyWindow: Bool = false) {
        self.id = id
        self.originID = originID
        self.destinationID = destinationID
        self.departure = departure
        self.arrival = arrival
        self.legs = legs
        self.sourceIDs = sourceIDs
        self.lastOfServiceDay = lastOfServiceDay
        self.firstAfterEmptyWindow = firstAfterEmptyWindow
    }

    public var vehicleLegs: [JourneyPlanLeg] { legs.filter { !$0.isWalk } }
    public var transfers: Int { max(vehicleLegs.count - 1, 0) }
}

/// Why a search found what it found (Android's `JourneySearchStatus`).
public enum JourneyPlanStatus: String, Codable, Sendable {
    case found
    /// The timetables cover both stations but have nothing in the searched window.
    case noSolution
    /// No timetable knows one of the stations.
    case notCovered
    /// The timetables covering the stations end before the requested day.
    case scheduleExpired
    /// The timetables could not be read.
    case unavailable
}

/// The answer of one search (Android's `JourneySearchResult`).
public struct JourneyPlanResult: Equatable, Sendable {
    public let status: JourneyPlanStatus
    public let options: [JourneyPlanOption]
    /// Part of the timetables could not be read: the options may be incomplete.
    public let incompleteCoverage: Bool
    /// «Solo accessibili» found nothing and there is no wheelchair data around the two ends.
    public let accessibilityDataUnavailable: Bool
    /// A timetable serving an end stops before the searched day: the options are those of the other networks.
    public let staleTimetables: Bool
    /// The packs that end before the searched day, with their last day (yyyymmdd).
    public let expiredSources: [String: Int]

    public init(status: JourneyPlanStatus, options: [JourneyPlanOption], incompleteCoverage: Bool = false,
                accessibilityDataUnavailable: Bool = false, staleTimetables: Bool = false, expiredSources: [String: Int] = [:]) {
        self.status = status
        self.options = options
        self.incompleteCoverage = incompleteCoverage
        self.accessibilityDataUnavailable = accessibilityDataUnavailable
        self.staleTimetables = staleTimetables
        self.expiredSources = expiredSources
    }
}

/// The end rule of a foreign pack bundled as its border subset (Android's `GtfsBorderZone`): it covers a zone station,
/// or a station its full pack does not serve; a served station outside the zone is left to the full pack.
public struct JourneyBorderZone: Hashable, Sendable {
    public let zoneStationIDs: Set<String>
    public let servedStationIDs: Set<String>

    public init(zoneStationIDs: Set<String>, servedStationIDs: Set<String>) {
        self.zoneStationIDs = zoneStationIDs
        self.servedStationIDs = servedStationIDs
    }

    public func contains(_ stationID: String?) -> Bool {
        guard let stationID else { return false }
        return zoneStationIDs.contains(stationID) || !servedStationIDs.contains(stationID)
    }
}

/// One search of journeys with changes.
public struct JourneyPlanRequest: Sendable {
    public let origin: StationIdentity
    public let destination: StationIdentity
    public let departure: Date
    public let maxTransfers: Int
    public let maxResults: Int
    public let preferences: JourneyPreferences?
    /// The catalogue stations by id: names, coordinates and railway codes of the transfer points.
    public let stations: [String: Station]
    /// The foreign packs read as their border subset (no full timetable installed), by source.
    public let borderZones: [String: JourneyBorderZone]

    public init(
        origin: StationIdentity,
        destination: StationIdentity,
        departure: Date,
        maxTransfers: Int = SharedData.JourneyPlanner.defaultMaximumTransfers,
        maxResults: Int = SharedData.JourneyPlanner.maximumResults,
        preferences: JourneyPreferences? = nil,
        stations: [String: Station],
        borderZones: [String: JourneyBorderZone] = [:]
    ) {
        self.origin = origin
        self.destination = destination
        self.departure = departure
        self.maxTransfers = min(max(maxTransfers, 0), 6)
        self.maxResults = min(max(maxResults, 1), 20)
        self.preferences = preferences
        self.stations = stations
        self.borderZones = borderZones
    }

    /// The same search from another time and for another number of results («Più presto», «Più tardi»).
    public func from(_ departure: Date, maxResults: Int? = nil) -> JourneyPlanRequest {
        JourneyPlanRequest(origin: origin, destination: destination, departure: departure, maxTransfers: maxTransfers,
                           maxResults: maxResults ?? self.maxResults, preferences: preferences, stations: stations,
                           borderZones: borderZones)
    }
}
