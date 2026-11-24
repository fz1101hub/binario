import Foundation

/// The chips of the results (Android's `JourneyResultsFilter`): «Tutti», «Diretti», «Meno cambi», «Più veloce»,
/// «Meno a piedi» and, with the network statistics, «Più affidabili».
public enum JourneyResultsFilter: String, CaseIterable, Sendable {
    case all = "ALL"
    case direct = "DIRECT"
    case fewestChanges = "FEWEST_CHANGES"
    case fastest = "FASTEST"
    case leastWalking = "LEAST_WALKING"
    case mostReliable = "MOST_RELIABLE"

    /// The chip the results open on for «Preferisci»: the default keeps «Tutti».
    public static func forRanking(_ ranking: JourneyRanking) -> JourneyResultsFilter {
        switch ranking {
        case .fastest: return .all
        case .fewestChanges: return .fewestChanges
        case .leastWalking: return .leastWalking
        }
    }

    /// The localization key of the chip (Android's `journey_filter_*`).
    public var labelKey: String {
        switch self {
        case .all: return "journey.filter.all"
        case .direct: return "journey.filter.direct"
        case .fewestChanges: return "journey.filter.fewest_changes"
        case .fastest: return "journey.filter.fastest"
        case .leastWalking: return "journey.filter.least_walking"
        case .mostReliable: return "journey.filter.most_reliable"
        }
    }
}

/// The search's limit of changes (Android's change filter of the planner): direct, at most one, at most two.
public enum JourneyChangeFilter: Int, CaseIterable, Sendable {
    case direct = 0
    case one = 1
    case two = 2

    public var labelKey: String {
        switch self {
        case .direct: return "journey.change.direct"
        case .one: return "journey.change.one"
        case .two: return "journey.change.two"
        }
    }
}

// MARK: - Transfer risk

/// A change seen with live data (Android's `TransferRiskLevel`): the next vehicle can be reached with the change time,
/// leaves with less, leaves before the arrival (or a run is cancelled), or there is no live data.
public enum TransferRiskLevel: Int, Comparable, Sendable {
    case unknown = 0
    case ok = 1
    case atRisk = 2
    case missed = 3

    public static func < (lhs: TransferRiskLevel, rhs: TransferRiskLevel) -> Bool { lhs.rawValue < rhs.rawValue }

    /// Worst first: missed, at risk, confirmed, unknown.
    public static func worst<Levels: Sequence>(_ levels: Levels) -> TransferRiskLevel where Levels.Element == TransferRiskLevel {
        levels.max() ?? .unknown
    }
}

/// Live times of one leg (Android's `JourneyLegLive`).
public struct JourneyLegLive: Equatable, Sendable {
    public let expectedDeparture: Date?
    public let expectedArrival: Date?
    public let cancelled: Bool
    /// The publisher of a city network's realtime feed, shown with `observedAt`.
    public let sourceLabel: String?
    public let observedAt: Date?

    public init(expectedDeparture: Date? = nil, expectedArrival: Date? = nil, cancelled: Bool = false, sourceLabel: String? = nil,
                observedAt: Date? = nil) {
        self.expectedDeparture = expectedDeparture
        self.expectedArrival = expectedArrival
        self.cancelled = cancelled
        self.sourceLabel = sourceLabel
        self.observedAt = observedAt
    }

    public var hasData: Bool { expectedDeparture != nil || expectedArrival != nil || cancelled }
}

/// The outcome of one change: expected arrival at the change, expected departure of the next vehicle.
public struct TransferAssessment: Equatable, Sendable {
    public let level: TransferRiskLevel
    public let expectedArrival: Date
    public let expectedDeparture: Date
    /// Next departure minus (arrival + change time), in minutes; negative when the change time is not met.
    public let marginMinutes: Int
}

/// Android's `TransferRiskCalculator`: without a walk the change time is the one the planner required at the station
/// (ten minutes at a large hub), never more than the scheduled wait; the connection is lost only when the next train
/// leaves before the arrival. With a walk the planned walk is the change time, and the connection is lost with less
/// than half of it.
public enum TransferRiskCalculator {
    public static let defaultTransferMinutes = SharedData.JourneyPlanner.defaultTransferMinutes

    public static func assess(
        scheduledArrival: Date, live: JourneyLegLive?, scheduledDeparture: Date, nextLive: JourneyLegLive?,
        walkSeconds: TimeInterval = 0, changeMinutes: Int = defaultTransferMinutes
    ) -> TransferAssessment {
        let planned = max(walkSeconds, 0)
        let transfer = planned == 0
            ? defaultTransfer(scheduledWait: scheduledDeparture.timeIntervalSince(scheduledArrival), changeMinutes: changeMinutes)
            : planned
        let minimum = planned / 2
        let liveArrival = live?.expectedArrival
        let liveDeparture = nextLive?.expectedDeparture
        let cancelled = live?.cancelled == true || nextLive?.cancelled == true
        let arrival = liveArrival ?? scheduledArrival
        let departure = liveDeparture ?? scheduledDeparture
        let margin = departure.timeIntervalSince(arrival.addingTimeInterval(transfer))
        let level: TransferRiskLevel
        if liveArrival == nil, liveDeparture == nil, !cancelled {
            level = .unknown
        } else if cancelled {
            level = .missed
        } else if margin >= 0 {
            level = .ok
        } else if departure >= arrival.addingTimeInterval(minimum) {
            level = .atRisk
        } else {
            level = .missed
        }
        return TransferAssessment(level: level, expectedArrival: arrival, expectedDeparture: departure,
                                  marginMinutes: Int((margin / 60).rounded(.down)))
    }

    /// The change time for a scheduled wait when no walk separates the two vehicles.
    public static func defaultTransfer(scheduledWait: TimeInterval, changeMinutes: Int = defaultTransferMinutes) -> TimeInterval {
        let standard = TimeInterval(max(changeMinutes, 0) * 60)
        if scheduledWait < 0 { return 0 }
        return scheduledWait < standard ? scheduledWait : standard
    }

    /// The key of a leg in the live map: its run identity, else its number and scheduled departure.
    public static func legKey(_ leg: JourneyPlanLeg) -> String {
        leg.runID ?? "\(leg.publicNumber ?? "")@\(Int(leg.departure.timeIntervalSince1970))"
    }

    public static func liveOf(_ leg: JourneyPlanLeg, live: [String: JourneyLegLive]) -> JourneyLegLive? {
        guard let found = live[legKey(leg)], found.hasData else { return nil }
        return found
    }

    /// The change before vehicle leg `leavingIndex`: the last vehicle before it arrives, the walks in between are the
    /// change time, then the next vehicle leaves. Nil without a vehicle before.
    public static func assessChange(_ legs: [JourneyPlanLeg], leavingIndex: Int, live: [String: JourneyLegLive]) -> TransferAssessment? {
        guard legs.indices.contains(leavingIndex), !legs[leavingIndex].isWalk else { return nil }
        guard let vehicleIndex = (0..<leavingIndex).reversed().first(where: { !legs[$0].isWalk }) else { return nil }
        let vehicle = legs[vehicleIndex]
        let leaving = legs[leavingIndex]
        let walk = legs[(vehicleIndex + 1)..<leavingIndex].filter(\.isWalk).reduce(0.0) { $0 + $1.arrival.timeIntervalSince($1.departure) }
        return assess(
            scheduledArrival: vehicle.arrival, live: liveOf(vehicle, live: live), scheduledDeparture: leaving.departure,
            nextLive: liveOf(leaving, live: live), walkSeconds: walk, changeMinutes: leaving.changeMinutes ?? defaultTransferMinutes
        )
    }
}

// MARK: - Rows

/// A change between two vehicles (Android's `JourneyTransferRow`); `afterLegIndex` is the leg it follows (the arriving
/// vehicle, or the walk when the change needs one).
public struct JourneyTransferRow: Equatable, Sendable {
    public let stationLabel: String
    public let stationID: String
    public let arrival: Date
    public let departure: Date
    public let waitMinutes: Int
    public let afterLegIndex: Int
    public let risk: TransferRiskLevel
    public let expectedArrival: Date?
    public let expectedDeparture: Date?
}

/// One option as the results show it (Android's `JourneyOptionRow`).
public struct JourneyOptionRow: Equatable, Identifiable, Sendable {
    public let option: JourneyPlanOption
    public let transfers: [JourneyTransferRow]
    /// Live data of the legs, by leg index; empty on the timetable only.
    public let live: [Int: JourneyLegLive]
    public var lastOfDay: Bool

    public var id: String { option.id }
    public var legs: [JourneyPlanLeg] { option.legs }
    public var departure: Date { option.departure }
    public var arrival: Date { option.arrival }
    public var changes: Int { option.transfers }
    public var durationMinutes: Int { max(Int(option.arrival.timeIntervalSince(option.departure) / 60), 0) }
    /// Minutes on foot: the «Meno a piedi» order.
    public var walkMinutes: Int { legs.filter(\.isWalk).reduce(0) { $0 + $1.durationMinutes } }
    /// The worst change with live data.
    public var transferRisk: TransferRiskLevel { TransferRiskLevel.worst(transfers.map(\.risk)) }
    /// Every leg published as wheelchair accessible.
    public var wheelchairAccessible: Bool { !legs.isEmpty && legs.allSatisfy { $0.wheelchair?.accessible == true } }
    /// Bikes for the whole journey: not allowed as soon as one vehicle does not take them, allowed only when all do.
    public var bikesAllowed: Bool? { JourneyResultsPresenter.bikes(option.vehicleLegs.map(\.bikesAllowed)) }
    /// GTFS service day of the first vehicle.
    public var serviceDate: Int? { option.vehicleLegs.first?.serviceDate }
    /// The kinds of service used, continuations included.
    public var modes: Set<TravelMode> {
        var modes = Set(option.vehicleLegs.compactMap(\.travelMode))
        for leg in option.vehicleLegs {
            guard let mode = leg.mode.timetableMode else { continue }
            for continuation in leg.continuations {
                if let kind = TravelMode.of(mode: mode, serviceKind: continuation.serviceKind, operatorName: continuation.operatorName) {
                    modes.insert(kind)
                }
            }
        }
        return modes
    }
    /// The longest scheduled wait at a change, in minutes.
    public var longestWaitMinutes: Int { max(transfers.map(\.waitMinutes).max() ?? 0, 0) }
    /// A wait the card names: the planner offers it only when nothing spares it.
    public var hasLongWait: Bool { longestWaitMinutes >= SharedData.JourneyPlanner.longWaitMinutes }
    public var firstAfterEmptyWindow: Bool { option.firstAfterEmptyWindow }
}

/// Android's `JourneyResultsPresenter`, without resources: rows, changes, orders and the day's last and first options.
public enum JourneyResultsPresenter {
    public static func row(_ option: JourneyPlanOption, live: [String: JourneyLegLive] = [:]) -> JourneyOptionRow {
        let legs = option.legs
        var transfers: [JourneyTransferRow] = []
        // A change joins two vehicles; a walk between them is its own leg and the change follows it.
        for leavingIndex in legs.indices.dropFirst() {
            let arrivingIndex = leavingIndex - 1
            let arriving = legs[arrivingIndex]
            let leaving = legs[leavingIndex]
            if leaving.isWalk { continue }
            let walked = arriving.isWalk
            if walked, arrivingIndex == 0 { continue }
            let assessment = TransferRiskCalculator.assessChange(legs, leavingIndex: leavingIndex, live: live)
            let risk = assessment?.level ?? .unknown
            transfers.append(JourneyTransferRow(
                stationLabel: walked ? leaving.originLabel : arriving.destinationLabel,
                stationID: walked ? leaving.originID : arriving.destinationID,
                arrival: arriving.arrival,
                departure: leaving.departure,
                waitMinutes: Int(leaving.departure.timeIntervalSince(arriving.arrival) / 60),
                afterLegIndex: arrivingIndex,
                risk: risk,
                expectedArrival: risk == .unknown ? nil : assessment?.expectedArrival,
                expectedDeparture: risk == .unknown ? nil : assessment?.expectedDeparture
            ))
        }
        var liveByLeg: [Int: JourneyLegLive] = [:]
        for (index, leg) in legs.enumerated() where !leg.isWalk {
            if let found = TransferRiskCalculator.liveOf(leg, live: live) { liveByLeg[index] = found }
        }
        return JourneyOptionRow(option: option, transfers: transfers, live: liveByLeg, lastOfDay: option.lastOfServiceDay)
    }

    /// The order of a chip: «Tutti» and «Diretti» by departure; the sorted chips by their own key, then the options
    /// with a preferred kind of service, then the «Preferisci» key, then the departure.
    public static func apply(_ rows: [JourneyOptionRow], filter: JourneyResultsFilter, preferences: JourneyPreferences? = nil) -> [JourneyOptionRow] {
        let entries = rows.map { JourneyResultEntry.journey($0) }
        return JourneyResultsMerge.apply(entries, filter: filter, preferences: preferences).compactMap(\.journey)
    }

    /// Bikes on board for a whole journey from its vehicle legs.
    public static func bikes(_ legs: [Bool?]) -> Bool? {
        if legs.isEmpty { return nil }
        if legs.contains(false) { return false }
        if legs.allSatisfy({ $0 == true }) { return true }
        return nil
    }

    /// «Ultimo di oggi» and «primo domani»: the option the planner marked as the last of the day, or the latest one of
    /// `serviceDate` when the list also holds an option of a later day; when nothing of the day is left, the first of
    /// the next.
    public static func dayBounds(_ rows: [JourneyOptionRow], serviceDate: Int) -> (lastOfDayID: String?, firstNextDay: JourneyOptionRow?) {
        func day(_ row: JourneyOptionRow) -> Int { row.serviceDate ?? 0 }
        let later = rows.filter { day($0) > serviceDate }
        let today = rows.filter { day($0) <= serviceDate }
        let latest = today.max { $0.departure < $1.departure }
        let lastID = latest.flatMap { row in (!later.isEmpty || row.option.lastOfServiceDay) ? row.id : nil }
        let first = today.isEmpty ? later.min { $0.departure < $1.departure } : nil
        return (lastID, first)
    }
}

// MARK: - Direct trains and journeys in one list

/// One row of the results: a direct train of the existing search, or a journey of the planner (Android's
/// `ResultEntry`). Both keep their own source; this only orders them in one list.
public enum JourneyResultEntry: Identifiable, Sendable {
    case train(TrainService, lastOfDay: Bool)
    case journey(JourneyOptionRow)

    public var id: String {
        switch self {
        case let .train(service, _): return "train-\(service.id)"
        case let .journey(row): return "journey-\(row.id)"
        }
    }

    public var journey: JourneyOptionRow? {
        if case let .journey(row) = self { return row }
        return nil
    }

    public var train: TrainService? {
        if case let .train(service, _) = self { return service }
        return nil
    }

    public var departure: Date? {
        switch self {
        case let .train(service, _): return service.departureDate
        case let .journey(row): return row.departure
        }
    }

    public var arrival: Date? {
        switch self {
        case let .train(service, _): return service.arrivalDate
        case let .journey(row): return row.arrival
        }
    }

    public var durationMinutes: Int? {
        guard let departure, let arrival else { return nil }
        return max(Int(arrival.timeIntervalSince(departure) / 60), 0)
    }

    public var changes: Int {
        if case let .journey(row) = self { return row.changes }
        return 0
    }

    public var walkMinutes: Int {
        if case let .journey(row) = self { return row.walkMinutes }
        return 0
    }

    public var modes: Set<TravelMode> {
        switch self {
        case let .train(service, _): return Set([TravelMode.ofTrain(service)].compactMap { $0 })
        case let .journey(row): return row.modes
        }
    }

    public var lastOfDay: Bool {
        switch self {
        case let .train(_, last): return last
        case let .journey(row): return row.lastOfDay
        }
    }
}

/// Android's `ResultsMerge`: the direct trains first in identity (a journey without changes that is the same train is
/// dropped), the chips' orders, «Ultimo di oggi» confirmed on the merged list, «primo domani» and «Nessun treno fino
/// alle …».
public enum JourneyResultsMerge {
    public static func entries(
        services: [TrainService], journeys: [JourneyOptionRow], serviceDate: Int, preferences: JourneyPreferences? = nil
    ) -> [JourneyResultEntry] {
        let trains = shownServices(services, preferences: preferences)
        var duplicates: [JourneyOptionRow] = []
        var extra: [JourneyOptionRow] = []
        for row in journeys {
            if trains.contains(where: { sameTrain($0, row) }) { duplicates.append(row) } else { extra.append(row) }
        }
        // A last-of-day journey shown as its direct train passes the mark on to it.
        let marked: [JourneyResultEntry] = trains.map { train in
            .train(train, lastOfDay: duplicates.contains { $0.lastOfDay && sameTrain(train, $0) })
        }
        let merged = marked + extra.map { JourneyResultEntry.journey($0) }
        return apply(confirmLastOfDay(merged, serviceDate: serviceDate), filter: .all)
    }

    /// The direct trains the travel preferences leave: none with «Solo accessibili» (a direct train carries no
    /// wheelchair data), none of an avoided kind (a train of unknown kind stays).
    public static func shownServices(_ services: [TrainService], preferences: JourneyPreferences?) -> [TrainService] {
        guard let preferences else { return services }
        if preferences.wheelchairOnly { return [] }
        if preferences.avoidedModes.isEmpty { return services }
        return services.filter { service in TravelMode.ofTrain(service).map { !preferences.avoidedModes.contains($0) } ?? true }
    }

    /// The mark stays only on the latest entry of the day: a direct train leaving later the same day takes it away.
    private static func confirmLastOfDay(_ entries: [JourneyResultEntry], serviceDate: Int) -> [JourneyResultEntry] {
        let marked = entries.filter(\.lastOfDay)
        guard !marked.isEmpty else { return entries }
        var keep: String?
        if marked.count == 1, let last = marked.first, let departure = last.departure {
            let later = entries.contains { entry in
                entry.id != last.id && day(entry, serviceDate) <= serviceDate && (entry.departure.map { $0 > departure } ?? false)
            }
            if !later { keep = last.id }
        }
        return entries.map { entry in
            guard entry.lastOfDay, entry.id != keep else { return entry }
            switch entry {
            case let .train(service, _): return .train(service, lastOfDay: false)
            case var .journey(row):
                row.lastOfDay = false
                return .journey(row)
            }
        }
    }

    /// The service day of an entry: the GTFS day of a journey, else its departure's day in Italian time.
    private static func day(_ entry: JourneyResultEntry, _ serviceDate: Int) -> Int {
        switch entry {
        case let .journey(row): return row.serviceDate ?? encoded(row.departure)
        case let .train(service, _): return service.departureDate.map(encoded) ?? serviceDate + 1
        }
    }

    static func encoded(_ date: Date) -> Int {
        let parts = romeCalendar.dateComponents([.year, .month, .day], from: date)
        return (parts.year ?? 0) * 10_000 + (parts.month ?? 0) * 100 + (parts.day ?? 0)
    }

    private static var romeCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        return calendar
    }

    /// «Non ci sono più treni oggi: primo domani alle …»: nothing of `serviceDate` is left while the timetable lists
    /// the next day; the earliest entry shown.
    public static func firstDepartureNextDay(_ entries: [JourneyResultEntry], journeys: [JourneyOptionRow], serviceDate: Int) -> Date? {
        guard journeys.contains(where: { ($0.serviceDate ?? encoded($0.departure)) > serviceDate }) else { return nil }
        guard !entries.contains(where: { day($0, serviceDate) <= serviceDate }) else { return nil }
        return entries.compactMap(\.departure).min()
    }

    /// «Nessun treno fino alle HH:mm»: the planner found nothing in the searched window and the first option after it
    /// leaves on the requested day, with no direct train before it.
    public static func noTrainsUntil(_ entries: [JourneyResultEntry], journeys: [JourneyOptionRow], requested: Date, firstNextDay: Date?) -> Date? {
        guard firstNextDay == nil,
              let first = journeys.filter(\.firstAfterEmptyWindow).map(\.departure).min(),
              encoded(first) == encoded(requested), first > requested
        else { return nil }
        if entries.contains(where: { entry in entry.departure.map { $0 >= requested && $0 < first } ?? false }) { return nil }
        return first
    }

    /// The orders of the chips: «Tutti» by departure, «Diretti» only entries without changes, «Meno cambi», «Più veloce»
    /// and «Meno a piedi» by their key then the preferences, «Più affidabili» by the statistics (`reliability`, higher
    /// first) then the departure.
    public static func apply(
        _ entries: [JourneyResultEntry], filter: JourneyResultsFilter, preferences: JourneyPreferences? = nil,
        reliability: [String: Double] = [:]
    ) -> [JourneyResultEntry] {
        let preferred = preferences?.preferredModes ?? []
        let ranking = preferences?.ranking ?? .fastest
        func byDeparture(_ a: JourneyResultEntry, _ b: JourneyResultEntry) -> Bool? {
            if let order = optionalOrder(a.departure, b.departure) { return order }
            return optionalOrder(a.arrival, b.arrival)
        }
        func ties(_ a: JourneyResultEntry, _ b: JourneyResultEntry) -> Bool? {
            let preferredA = a.modes.filter(preferred.contains).count
            let preferredB = b.modes.filter(preferred.contains).count
            if preferredA != preferredB { return preferredA > preferredB }
            switch ranking {
            case .fastest:
                if let order = optionalOrder(a.durationMinutes, b.durationMinutes) { return order }
            case .fewestChanges:
                if a.changes != b.changes { return a.changes < b.changes }
            case .leastWalking:
                if a.walkMinutes != b.walkMinutes { return a.walkMinutes < b.walkMinutes }
            }
            return byDeparture(a, b)
        }
        func sorted(_ list: [JourneyResultEntry], _ first: (JourneyResultEntry, JourneyResultEntry) -> Bool?) -> [JourneyResultEntry] {
            list.enumerated().sorted { a, b in first(a.element, b.element) ?? (a.offset < b.offset) }.map(\.element)
        }
        switch filter {
        case .all:
            return sorted(entries, byDeparture)
        case .direct:
            return sorted(entries.filter { $0.changes == 0 }, byDeparture)
        case .fewestChanges:
            return sorted(entries) { a, b -> Bool? in
                if a.changes != b.changes { return a.changes < b.changes }
                return ties(a, b)
            }
        case .fastest:
            return sorted(entries) { a, b -> Bool? in
                if let order = optionalOrder(a.durationMinutes, b.durationMinutes) { return order }
                return ties(a, b)
            }
        case .leastWalking:
            return sorted(entries) { a, b -> Bool? in
                if a.walkMinutes != b.walkMinutes { return a.walkMinutes < b.walkMinutes }
                return ties(a, b)
            }
        case .mostReliable:
            return sorted(entries) { a, b -> Bool? in
                let first = reliability[a.id]
                let second = reliability[b.id]
                if first != second {
                    // Measured entries first, the higher score first.
                    guard let first else { return false }
                    guard let second else { return true }
                    return first > second
                }
                return byDeparture(a, b)
            }
        }
    }

    /// Ascending with nil last; nil when the two are equal.
    private static func optionalOrder<Value: Comparable>(_ a: Value?, _ b: Value?) -> Bool? {
        switch (a, b) {
        case let (a?, b?): return a == b ? nil : a < b
        case (nil, nil): return nil
        case (nil, _): return false
        case (_, nil): return true
        }
    }

    /// Options each chip shows, announced with the chip.
    public static func counts(_ entries: [JourneyResultEntry]) -> [JourneyResultsFilter: Int] {
        var counts: [JourneyResultsFilter: Int] = [:]
        for filter in JourneyResultsFilter.allCases { counts[filter] = apply(entries, filter: filter).count }
        return counts
    }

    /// A journey without changes that is the direct train (same minute and number, or same minutes when a number is
    /// missing; a short walk before or after it does not make it another option).
    public static func sameTrain(_ train: TrainService, _ row: JourneyOptionRow) -> Bool {
        guard row.changes == 0 else { return false }
        let vehicles = row.option.vehicleLegs
        guard vehicles.count == 1, let vehicle = vehicles.first else { return false }
        guard let departure = train.departureDate, minute(departure) == minute(vehicle.departure) else { return false }
        if row.walkMinutes > SharedData.JourneyPlanner.duplicateWalkMinutes { return false }
        if let trainNumber = normalizedNumber(train.number), let legNumber = normalizedNumber(vehicle.publicNumber) {
            return trainNumber == legNumber
        }
        guard let arrival = train.arrivalDate else { return false }
        return minute(arrival) == minute(vehicle.arrival)
    }

    private static func minute(_ date: Date) -> Int { Int((date.timeIntervalSince1970 / 60).rounded(.down)) }

    private static func normalizedNumber(_ value: String?) -> String? {
        guard let value else { return nil }
        let digits = String(value.unicodeScalars.filter { ("0"..."9").contains($0) }.map(Character.init)).drop { $0 == "0" }
        return digits.isEmpty ? nil : String(digits)
    }
}

// MARK: - Network statistics of a journey

/// The chance a change works on the statistics: how often the arriving train was at most the slack late.
public struct ConnectionOdds: Equatable, Sendable {
    public let samples: Int
    public let expectedSuccesses: Double

    public var probability: Double { samples > 0 ? min(max(expectedSuccesses / Double(samples), 0), 1) : 0 }
    public var successes: Int { min(max(Int((expectedSuccesses + 1e-9).rounded(.down)), 0), samples) }
}

/// A journey on the network statistics (Android's `JourneyNetworkReliability`): every train leg where it is left, and
/// the changes after them.
public struct JourneyPlanReliability: Equatable, Sendable {
    public struct Leg: Equatable, Sendable {
        public let legIndex: Int
        public let stop: StopReliability
    }

    public struct Connection: Equatable, Sendable {
        public let afterLegIndex: Int
        public let stationLabel: String
        public let slackMinutes: Int
        public let odds: ConnectionOdds
    }

    public let legs: [Leg]
    public let connections: [Connection]

    /// The chance of the whole journey working on a day of the window.
    public var score: Double {
        legs.reduce(1.0) { $0 * $1.stop.score } * connections.reduce(1.0) { $0 * $1.odds.probability }
    }

    /// The leg least likely to be on time (then the higher mean delay, then the first).
    public var weakestLeg: Leg? {
        legs.enumerated().min { a, b in
            if a.element.stop.score != b.element.stop.score { return a.element.stop.score < b.element.stop.score }
            if a.element.stop.meanDelayMinutes != b.element.stop.meanDelayMinutes {
                return a.element.stop.meanDelayMinutes > b.element.stop.meanDelayMinutes
            }
            return a.offset < b.offset
        }?.element
    }

    /// The change least likely to work.
    public var weakestConnection: Connection? {
        connections.enumerated().min { a, b in
            a.element.odds.probability != b.element.odds.probability ? a.element.odds.probability < b.element.odds.probability : a.offset < b.offset
        }?.element
    }

    /// Android's `JourneyNetworkReliabilityAssessor.assess`: nil unless every train leg is measured where it is left (or
    /// cancelled every day observed); a change after a train is measured on that train's delays at the change station
    /// against the slack: the planned wait minus the walk, or else the change time there.
    public static func assess(
        _ row: JourneyOptionRow, stats: NetworkReliabilityStats, stationCode: (String) -> String?,
        transferMinutes: (String?) -> Int = { JourneyPlannerSettings().railTransferMinutes($0) }
    ) -> JourneyPlanReliability? {
        let legsOfRow = row.legs
        let vehicles = legsOfRow.indices.filter { !legsOfRow[$0].isWalk }
        let trains = vehicles.filter { legsOfRow[$0].mode == .train }
        guard !trains.isEmpty else { return nil }
        var matches: [Int: NetworkReliabilityMatch] = [:]
        for index in trains {
            let leg = legsOfRow[index]
            matches[index] = NetworkReliability.match(
                stats, number: leg.publicNumber, alightingCode: stationCode(leg.destinationID), boardingCode: stationCode(leg.originID)
            )
        }
        var legs: [Leg] = []
        for index in trains {
            let leg = legsOfRow[index]
            if let match = matches[index] {
                guard let stop = NetworkReliability.at(match.train, index: match.alightingIndex) else { return nil }
                legs.append(Leg(legIndex: index, stop: stop))
            } else {
                guard let stop = NetworkReliability.cancelledEveryDay(stats, number: leg.publicNumber, originCode: stationCode(leg.originID)) else {
                    return nil
                }
                legs.append(Leg(legIndex: index, stop: stop))
            }
        }
        var connections: [Connection] = []
        for (arriving, leaving) in zip(vehicles, vehicles.dropFirst()) {
            guard let match = matches[arriving] else { continue }
            let arrivingLeg = legsOfRow[arriving]
            let walks = legsOfRow[(arriving + 1)..<leaving].filter(\.isWalk)
            let needed = walks.isEmpty ? transferMinutes(stationCode(arrivingLeg.destinationID)) : walks.reduce(0) { $0 + $1.durationMinutes }
            let slack = Int(legsOfRow[leaving].departure.timeIntervalSince(arrivingLeg.arrival) / 60) - needed
            guard (0..<match.train.stopCount).contains(match.alightingIndex) else { continue }
            let samples = match.train.samples(at: match.alightingIndex)
            guard samples >= NetworkReliability.minimumSamples else { continue }
            let successes = min(max(match.train.count(at: match.alightingIndex, atMost: Double(slack)), 0), Double(samples))
            connections.append(Connection(afterLegIndex: arriving, stationLabel: arrivingLeg.destinationLabel, slackMinutes: slack,
                                          odds: ConnectionOdds(samples: samples, expectedSuccesses: successes)))
        }
        return JourneyPlanReliability(legs: legs, connections: connections)
    }
}
