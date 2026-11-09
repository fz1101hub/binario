import Foundation

/// Why a favourite train, or the list of them, was refused.
public enum FavouriteTrainValidationError: Error, Equatable, Sendable {
    case invalidNumber
    case invalidText
    case invalidDeparture
    case invalidStations
    case tooManyTrains
    case duplicateTrain
}

/// A train saved with the star of the train detail («I miei treni»), kept on this iPhone only.
///
/// Its identity is the train number with the station it is boarded at (the planner's origin), as on
/// Android a favourite is its number with its origin (`FavouriteTrains.kt`, `BinarioDeepLink.FavouriteTrain`):
/// the same number saved from another station is another favourite. What the detail shows is kept
/// as the timetable published it on the day it was saved (programmed data, never live), with the
/// searched destination, where the detail measures the train. A valid favourite always makes a
/// valid `JourneyQuery`: the stations are checked by building one.
public struct FavouriteTrain: Codable, Equatable, Hashable, Identifiable, Sendable {
    /// The longest published number the timetable index keeps (the repository's bound).
    public static let maximumNumberLength = 24
    /// Android's train number (`BinarioDeepLink.FavouriteTrain`, `^[A-Za-z0-9]{1,12}$`): the number
    /// proper («2153» of Trenord's «RE_11 - 2153») has 1 to 12 ASCII letters or digits.
    public static let maximumNumberKeyLength = 12
    public static let maximumOperatorLength = 64
    public static let maximumCategoryLength = 64
    public static let maximumTerminusLength = 96

    /// The number as the timetable publishes it ("2153", "RE_11 - 2153", "S 2590").
    public let number: String
    public let operatorName: String
    public let category: String
    /// The train's own destination, as the timetable publishes it.
    public let terminus: String
    /// "HH:mm" at `origin`, Italian time, as the timetable published it when the train was saved.
    public let scheduledDeparture: String
    /// GTFS bikes_allowed when the timetable published it, else nil.
    public let bikesAllowed: Bool?
    /// The station the train is boarded at: with `number`, the favourite's identity.
    public let origin: StationIdentity
    /// The searched destination: the stop the train detail measures the statistics at.
    public let destination: StationIdentity
    public let savedAt: Date

    public var id: String { Self.identifier(number: number, originID: origin.id) }

    public init(
        number: String,
        operatorName: String,
        category: String,
        terminus: String,
        scheduledDeparture: String,
        bikesAllowed: Bool?,
        origin: StationIdentity,
        destination: StationIdentity,
        savedAt: Date
    ) throws {
        guard Self.isValidNumber(number) else { throw FavouriteTrainValidationError.invalidNumber }
        guard Self.isValidText(operatorName, maximum: Self.maximumOperatorLength),
              Self.isValidText(category, maximum: Self.maximumCategoryLength),
              Self.isValidText(terminus, maximum: Self.maximumTerminusLength)
        else { throw FavouriteTrainValidationError.invalidText }
        guard Self.isValidDeparture(scheduledDeparture) else { throw FavouriteTrainValidationError.invalidDeparture }
        do {
            _ = try JourneyQuery(origin: origin, destination: destination, departure: savedAt)
        } catch {
            throw FavouriteTrainValidationError.invalidStations
        }
        self.number = number
        self.operatorName = operatorName
        self.category = category
        self.terminus = terminus
        self.scheduledDeparture = scheduledDeparture
        self.bikesAllowed = bikesAllowed
        self.origin = origin
        self.destination = destination
        self.savedAt = savedAt
    }

    /// The train of a planner result, boarded at the query's origin.
    public init(service: TrainService, query: JourneyQuery, savedAt: Date) throws {
        try self.init(
            number: service.number,
            operatorName: service.operatorName,
            category: service.category,
            terminus: service.destination,
            scheduledDeparture: service.scheduledDeparture,
            bikesAllowed: service.bikesAllowed,
            origin: query.origin,
            destination: query.destination,
            savedAt: savedAt
        )
    }

    /// Every stored record is checked again: an edited or corrupt one is refused.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            number: container.decode(String.self, forKey: .number),
            operatorName: container.decode(String.self, forKey: .operatorName),
            category: container.decode(String.self, forKey: .category),
            terminus: container.decode(String.self, forKey: .terminus),
            scheduledDeparture: container.decode(String.self, forKey: .scheduledDeparture),
            bikesAllowed: container.decodeIfPresent(Bool.self, forKey: .bikesAllowed),
            origin: container.decode(StationIdentity.self, forKey: .origin),
            destination: container.decode(StationIdentity.self, forKey: .destination),
            savedAt: container.decode(Date.self, forKey: .savedAt)
        )
    }

    /// The identity of a favourite: its number (case ignored, as on Android) and its boarding station.
    public static func identifier(number: String, originID: String) -> String {
        "\(number.uppercased())@\(originID)"
    }

    /// The train as the detail shows it: programmed, as published when it was saved.
    public var service: TrainService {
        TrainService(
            id: "favourite:\(id)",
            number: number,
            operatorName: operatorName,
            category: category,
            destination: terminus,
            scheduledDeparture: scheduledDeparture,
            expectedDeparture: scheduledDeparture,
            status: OperationalState.programmed.rawValue,
            platform: nil,
            delayMinutes: 0,
            bikesAllowed: bikesAllowed
        )
    }

    /// The ride of the detail: from the boarding station to the searched destination.
    public var query: JourneyQuery? {
        try? JourneyQuery(origin: origin, destination: destination, departure: savedAt)
    }

    /// A published train number the iPhone keeps: at most `maximumNumberLength` ASCII letters,
    /// digits, single spaces, "-", "_" and "+" (the forms of the bundled timetables), whose number
    /// proper (`NetworkReliability.numberKey`) is what Android accepts, 1 to 12 letters or digits.
    /// A line name such as «Linea Sassari-Sorso» is not a train number.
    public static func isValidNumber(_ number: String) -> Bool {
        let scalars = Array(number.unicodeScalars)
        guard !scalars.isEmpty, scalars.count <= maximumNumberLength,
              scalars.allSatisfy(isNumberScalar),
              number.split(separator: " ").joined(separator: " ") == number
        else { return false }
        let key = NetworkReliability.numberKey(number)
        let keyScalars = Array(key.unicodeScalars)
        return !keyScalars.isEmpty && keyScalars.count <= maximumNumberKeyLength && keyScalars.allSatisfy(isASCIIAlphanumeric)
    }

    /// A single-line text of at most `maximum` characters, without control characters or
    /// surrounding or repeated whitespace.
    static func isValidText(_ value: String, maximum: Int) -> Bool {
        let normalized = value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        guard !normalized.isEmpty, normalized.count <= maximum, normalized == value else { return false }
        return !value.unicodeScalars.contains { $0.properties.generalCategory == .control }
    }

    /// "HH:mm", 00:00 to 23:59.
    static func isValidDeparture(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        guard bytes.count == 5, bytes[2] == UInt8(ascii: ":") else { return false }
        let digits: [UInt8] = [bytes[0], bytes[1], bytes[3], bytes[4]]
        guard digits.allSatisfy({ (48...57).contains($0) }) else { return false }
        let hour: Int = Int(bytes[0] - 48) * 10 + Int(bytes[1] - 48)
        let minute: Int = Int(bytes[3] - 48) * 10 + Int(bytes[4] - 48)
        return hour < 24 && minute < 60
    }

    private static func isNumberScalar(_ scalar: Unicode.Scalar) -> Bool {
        if isASCIIAlphanumeric(scalar) { return true }
        return scalar == " " || scalar == "-" || scalar == "_" || scalar == "+"
    }

    private static func isASCIIAlphanumeric(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 48...57, 65...90, 97...122: return true
        default: return false
        }
    }
}

/// The favourite trains in their order, newest first, as on Android: at most `maximumTrains`, one
/// per identity. A full list refuses a new train (the user removes one first) rather than dropping
/// the oldest; removing is always possible.
public struct FavouriteTrainCollection: Equatable, Sendable {
    /// As many as Android keeps (`FavouriteTrains.MAX`).
    public static let maximumTrains = 12

    public let trains: [FavouriteTrain]

    public init(validating trains: [FavouriteTrain]) throws {
        guard trains.count <= Self.maximumTrains else { throw FavouriteTrainValidationError.tooManyTrains }
        guard Set(trains.map(\.id)).count == trains.count else { throw FavouriteTrainValidationError.duplicateTrain }
        self.trains = trains
    }

    private init(checked trains: [FavouriteTrain]) {
        self.trains = trains
    }

    /// The first train of each identity, in order, at most `maximumTrains` (Android's `normalized`).
    public static func normalized(_ trains: [FavouriteTrain]) -> FavouriteTrainCollection {
        var seen = Set<String>()
        var kept: [FavouriteTrain] = []
        for train in trains {
            guard kept.count < maximumTrains else { break }
            guard seen.insert(train.id).inserted else { continue }
            kept.append(train)
        }
        return FavouriteTrainCollection(checked: kept)
    }

    public func contains(id: String) -> Bool {
        trains.contains { $0.id == id }
    }

    /// The list with `train` first; the same list when it is already saved.
    /// Throws `tooManyTrains` when the list is full.
    public func adding(_ train: FavouriteTrain) throws -> FavouriteTrainCollection {
        guard !contains(id: train.id) else { return self }
        guard trains.count < Self.maximumTrains else { throw FavouriteTrainValidationError.tooManyTrains }
        return FavouriteTrainCollection(checked: [train] + trains)
    }

    /// The list without the trains of `ids`.
    public func removing(ids: Set<String>) -> FavouriteTrainCollection {
        FavouriteTrainCollection(checked: trains.filter { !ids.contains($0.id) })
    }
}

/// The favourite trains in the app's `UserDefaults`, on this iPhone only: a JSON array, newest
/// first, under `storageKey`. Nothing is synchronised or sent; Apple system backups may include it.
public final class FavouriteTrainStore {
    public static let storageKey = "it.binario.ios.favourite-trains.v1"
    /// Twelve trains take a few kilobytes: anything larger was not written by the app.
    public static let maximumStoredBytes = 64 * 1_024

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The saved trains: an unreadable value reads as none, and an invalid record is skipped on
    /// its own (as Android's codec skips one record), with its duplicates and any beyond the limit.
    public func load() -> FavouriteTrainCollection {
        guard let data = defaults.data(forKey: Self.storageKey) else { return FavouriteTrainCollection.normalized([]) }
        return FavouriteTrainCollection.normalized(Self.decode(data))
    }

    public func save(_ collection: FavouriteTrainCollection) throws {
        defaults.set(try Self.encode(collection.trains), forKey: Self.storageKey)
    }

    static func encode(_ trains: [FavouriteTrain]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        return try encoder.encode(trains)
    }

    static func decode(_ data: Data) -> [FavouriteTrain] {
        guard data.count <= maximumStoredBytes else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        guard let records = try? decoder.decode([StoredFavouriteTrain].self, from: data) else { return [] }
        return records.compactMap(\.train)
    }
}

/// One stored record, nil when it is not a valid favourite.
private struct StoredFavouriteTrain: Decodable {
    let train: FavouriteTrain?

    init(from decoder: Decoder) throws {
        train = try? FavouriteTrain(from: decoder)
    }
}

/// One favourite train of «Affidabilità dei miei treni»: its network statistics at its last stop.
public struct FavouriteTrainReliability: Equatable, Identifiable, Sendable {
    public let train: FavouriteTrain
    /// At the train's last stop, against its own 5- or 15-minute limit, with at least
    /// `NetworkReliability.minimumSamples` samples.
    public let network: StopReliability

    public var id: String { train.id }
}

/// «Affidabilità dei miei treni»: the first `maximumRows` favourite trains, in their order, each
/// with its on-time share at its last stop in the bundled network statistics (Android's
/// `FavouriteTrainPunctuality.rows` and `ReliabilityMarks.favouriteStop`). The iPhone records no
/// run of its own, so Android's «Registrato su questo telefono» and the trend of the last four weeks
/// against the four before, both computed from the runs Android records, have no counterpart here:
/// the bundled file is one 30-day aggregate per train and stop. A favourite without statistics, or
/// below `NetworkReliability.minimumSamples` at its last stop, has no row.
public struct FavouriteTrainsReliability: Equatable, Sendable {
    /// As on Android (`FavouriteTrainPunctuality.MAX_ROWS`).
    public static let maximumRows = 8

    public let rows: [FavouriteTrainReliability]
    /// The last day the statistics include.
    public let windowEnd: NetworkReliabilityDay
    /// The days the statistics cover.
    public let days: Int

    /// Nil without statistics (a build without the file, or while it is read) or without a row.
    public static func assess(_ favourites: [FavouriteTrain], stats: NetworkReliabilityStats?) -> FavouriteTrainsReliability? {
        guard let stats else { return nil }
        let rows: [FavouriteTrainReliability] = favourites.prefix(maximumRows).compactMap { train in
            let originCode = NetworkReliability.stationCode(train.origin)
            guard let stop = lastStop(stats, number: train.number, originCode: originCode) else { return nil }
            return FavouriteTrainReliability(train: train, network: stop)
        }
        guard !rows.isEmpty else { return nil }
        return FavouriteTrainsReliability(rows: rows, windowEnd: stats.windowEnd, days: stats.coveredDays)
    }

    /// The train numbered `number` measured at its last stop (Android's `ReliabilityMarks.favouriteStop`),
    /// or nil below `NetworkReliability.minimumSamples` samples there.
    public static func lastStop(_ stats: NetworkReliabilityStats, number: String, originCode: String?) -> StopReliability? {
        guard let found = match(stats, number: number, originCode: originCode) else { return nil }
        return NetworkReliability.at(found.train, index: found.alightingIndex)
    }

    /// The train that leaves (or calls at) `originCode`, measured at its last stop; without a code,
    /// the only train with that number that runs. Several trains with the number and no code to
    /// tell them apart give none rather than a guess.
    static func match(_ stats: NetworkReliabilityStats, number: String, originCode: String?) -> NetworkReliabilityMatch? {
        guard !number.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        if let terminus = NetworkReliability.matchTerminus(stats, number: number, boardingCode: originCode) { return terminus }
        if NetworkReliability.isStationCode(originCode) { return nil }
        let running = stats.trains(numbered: number).filter { $0.stopCount > 1 }
        guard running.count == 1, let single = running.first else { return nil }
        return NetworkReliabilityMatch(train: single, boardingIndex: 0, alightingIndex: single.stopCount - 1)
    }
}
