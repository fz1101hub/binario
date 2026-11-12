import Foundation

/// The limits of the Agenda and the JSON contract it shares with Android
/// (`PlannedJourneyPersistenceSchema`, `PlannedJourneyTransferContract`): a document written by
/// the iPhone is a valid Android transfer document, so a later file transfer needs no conversion.
public enum PlannedJourneySchema {
    public static let version = 1
    public static let transferKind = "binario_planned_journeys"
    /// As Android's `MAX_JOURNEYS`: a full Agenda refuses a new journey rather than dropping one.
    public static let maximumJourneys = 40
    public static let maximumChecklistItems = 24
    /// Lengths count UTF-16 code units, as Kotlin's `String.length`.
    public static let maximumLabelLength = 96
    public static let maximumChecklistLabelLength = 160
    public static let maximumIdentifierLength = 120
    /// As Android's `PlannedJourneyTransferContract.MAX_BYTES`.
    public static let maximumEncodedBytes = 262_144
    /// Android's `PlannedJourneyReminderMinutes`.
    public static let reminderMinutes: Set<Int> = [5, 10, 15, 20, 30, 45, 60]
}

/// Why a planned journey, or a list of them, was refused.
public enum PlannedJourneyValidationError: Error, Equatable, Sendable {
    case unsupportedSchemaVersion
    case unexpectedField
    case invalidIdentifier
    case invalidLabel
    /// The iPhone keeps one-leg journeys: an itinerary of several legs is not read.
    case unsupportedItinerary
    case invalidSegment
    case invalidStation
    case sameStation
    case invalidDeparture
    /// The daylight-saving change skips this time in that zone: it has no instant.
    case nonexistentLocalTime
    /// The iPhone has no recurring journeys yet: a recurrence is not read.
    case unsupportedRecurrence
    case invalidReminder
    case invalidChecklist
    case sensitiveChecklistContent
    case invalidStatusTimestamps
    case invalidCompletedOccurrences
    case invalidTimestamp
    case tooManyJourneys
    case duplicateIdentifier
    case duplicateJourney
    case unsupportedDocument
    case tooLarge
}

/// A catalogue station of a planned journey, by its canonical identifier (the curated catalogue's,
/// the same on Android and the iPhone), with the name shown and the railway code.
public struct PlannedJourneyStation: Equatable, Hashable, Sendable {
    public let canonicalId: String
    public let displayName: String
    public let railwayCode: String?

    /// Android's rules: identifier and name of 2 to 120 characters, a name without control
    /// characters, a code of 2 to 32 characters without spaces.
    public init(canonicalId: String, displayName: String, railwayCode: String? = nil) throws {
        guard (2...120).contains(PlannedJourneyText.length(PlannedJourneyText.trimmed(canonicalId))),
              (2...120).contains(PlannedJourneyText.length(PlannedJourneyText.trimmed(displayName))),
              !PlannedJourneyText.hasControl(displayName)
        else { throw PlannedJourneyValidationError.invalidStation }
        if let railwayCode {
            guard (2...32).contains(PlannedJourneyText.length(PlannedJourneyText.trimmed(railwayCode))),
                  !PlannedJourneyText.hasWhitespace(railwayCode), !PlannedJourneyText.hasControl(railwayCode)
            else { throw PlannedJourneyValidationError.invalidStation }
        }
        self.canonicalId = canonicalId
        self.displayName = displayName
        self.railwayCode = railwayCode
    }

    /// A station chosen in the catalogue.
    public init(_ station: StationIdentity) throws {
        try self.init(canonicalId: station.id, displayName: station.name, railwayCode: station.railwayCode)
    }

    /// The identity the planner and the station pickers use.
    public var identity: StationIdentity {
        StationIdentity(id: canonicalId, name: displayName, railwayCode: railwayCode)
    }

    /// Android's `normalizedId()`: trimmed, upper case.
    var normalizedId: String {
        PlannedJourneyText.trimmed(canonicalId).uppercased()
    }
}

/// A departure as the Agenda stores it: an Italian (or other IANA) date and clock time, never an
/// instant converted to the phone's zone. A time that does not exist in the zone is refused.
public struct PlannedJourneyDateTime: Equatable, Hashable, Sendable {
    public let date: PlannedLocalDate
    public let time: PlannedLocalTime
    /// An IANA identifier, "Europe/Rome" for every journey the iPhone creates.
    public let timeZone: String
    /// The instant of departure, from the wall clock of `timeZone`.
    public let instant: Date

    public init(
        date: PlannedLocalDate,
        time: PlannedLocalTime,
        timeZone: String = ItalianWallClock.zoneIdentifier
    ) throws {
        guard let zone = TimeZone(identifier: timeZone) else { throw PlannedJourneyValidationError.invalidDeparture }
        guard let instant = ItalianWallClock.instant(date: date, time: time, zone: zone) else {
            throw PlannedJourneyValidationError.nonexistentLocalTime
        }
        self.date = date
        self.time = time
        self.timeZone = timeZone
        self.instant = instant
    }
}

/// The one leg of an iPhone journey (Android's itinerary holds up to four).
public struct PlannedJourneySegment: Equatable, Sendable {
    /// The identifier Android gives the first leg of a journey made in its editor.
    public static let firstIdentifier = "segment-1"

    public let id: String
    public let origin: PlannedJourneyStation
    public let destination: PlannedJourneyStation
    public let departure: PlannedJourneyDateTime

    public init(
        id: String = PlannedJourneySegment.firstIdentifier,
        origin: PlannedJourneyStation,
        destination: PlannedJourneyStation,
        departure: PlannedJourneyDateTime
    ) throws {
        let trimmed = PlannedJourneyText.trimmed(id)
        guard !trimmed.isEmpty, PlannedJourneyText.length(trimmed) <= PlannedJourneySchema.maximumIdentifierLength else {
            throw PlannedJourneyValidationError.invalidSegment
        }
        guard origin.normalizedId != destination.normalizedId else { throw PlannedJourneyValidationError.sameStation }
        self.id = id
        self.origin = origin
        self.destination = destination
        self.departure = departure
    }
}

/// One checklist line: short plain text only, no notes, tickets, contacts or attachments.
public struct PlannedJourneyChecklistItem: Equatable, Hashable, Identifiable, Sendable {
    public let id: String
    public let label: String
    public let completed: Bool

    public init(id: String, label: String, completed: Bool = false) throws {
        let trimmedID = PlannedJourneyText.trimmed(id)
        guard !trimmedID.isEmpty, PlannedJourneyText.length(trimmedID) <= PlannedJourneySchema.maximumIdentifierLength,
              (1...PlannedJourneySchema.maximumChecklistLabelLength)
                .contains(PlannedJourneyText.length(PlannedJourneyText.trimmed(label))),
              !PlannedJourneyText.hasControl(label)
        else { throw PlannedJourneyValidationError.invalidChecklist }
        guard !PlannedJourneyText.isSensitive(label) else { throw PlannedJourneyValidationError.sensitiveChecklistContent }
        self.id = id
        self.label = label
        self.completed = completed
    }

    private init(checkedID id: String, label: String, completed: Bool) {
        self.id = id
        self.label = label
        self.completed = completed
    }

    /// The same line, ticked or not.
    public func setting(completed: Bool) -> PlannedJourneyChecklistItem {
        PlannedJourneyChecklistItem(checkedID: id, label: label, completed: completed)
    }
}

public enum PlannedJourneyStatus: String, Sendable {
    case active = "ACTIVE"
    case completed = "COMPLETED"
    /// Android's «Archivia»; the iPhone reads it and shows the journey in its archive.
    case archived = "ARCHIVED"
}

/// A personal planned journey of the Agenda, on this iPhone only: a one-leg itinerary between two
/// catalogue stations at an Italian date and time, an optional label and checklist. It holds no
/// train, platform, delay, live state or ticket. Every value is checked when it is built, also when
/// it is read back, so a stored journey is always a valid one.
public struct PlannedJourney: Equatable, Identifiable, Sendable {
    public let id: String
    public let label: String
    public let segment: PlannedJourneySegment
    /// Kept as Android stores it; the iPhone schedules no reminder yet.
    public let reminderMinutes: Int?
    public let checklist: [PlannedJourneyChecklistItem]
    public let status: PlannedJourneyStatus
    /// Whole seconds, as the document stores them.
    public let createdAt: Date
    public let completedAt: Date?
    public let archivedAt: Date?

    public init(
        id: String,
        label: String = "",
        segment: PlannedJourneySegment,
        reminderMinutes: Int? = nil,
        checklist: [PlannedJourneyChecklistItem] = [],
        status: PlannedJourneyStatus = .active,
        createdAt: Date,
        completedAt: Date? = nil,
        archivedAt: Date? = nil
    ) throws {
        let trimmedID = PlannedJourneyText.trimmed(id)
        guard !trimmedID.isEmpty, PlannedJourneyText.length(trimmedID) <= PlannedJourneySchema.maximumIdentifierLength else {
            throw PlannedJourneyValidationError.invalidIdentifier
        }
        guard PlannedJourneyText.length(label) <= PlannedJourneySchema.maximumLabelLength,
              !PlannedJourneyText.hasControl(label)
        else { throw PlannedJourneyValidationError.invalidLabel }
        if let reminderMinutes, !PlannedJourneySchema.reminderMinutes.contains(reminderMinutes) {
            throw PlannedJourneyValidationError.invalidReminder
        }
        guard checklist.count <= PlannedJourneySchema.maximumChecklistItems,
              Set(checklist.map { PlannedJourneyText.trimmed($0.id) }).count == checklist.count
        else { throw PlannedJourneyValidationError.invalidChecklist }

        let created = PlannedJourneyInstant.wholeSecond(createdAt)
        let completed = completedAt.map(PlannedJourneyInstant.wholeSecond)
        let archived = archivedAt.map(PlannedJourneyInstant.wholeSecond)
        let consistent: Bool
        switch status {
        case .active: consistent = completed == nil && archived == nil
        case .completed: consistent = completed != nil && archived == nil
        case .archived: consistent = archived != nil
        }
        var ordered = true
        if let completed, completed < created { ordered = false }
        if let archived, archived < created { ordered = false }
        if let completed, let archived, archived < completed { ordered = false }
        guard consistent, ordered else { throw PlannedJourneyValidationError.invalidStatusTimestamps }

        self.id = id
        self.label = label
        self.segment = segment
        self.reminderMinutes = reminderMinutes
        self.checklist = checklist
        self.status = status
        self.createdAt = created
        self.completedAt = completed
        self.archivedAt = archived
    }

    public var origin: PlannedJourneyStation { segment.origin }
    public var destination: PlannedJourneyStation { segment.destination }
    public var departure: PlannedJourneyDateTime { segment.departure }

    /// Android's `PlannedJourneyDeduplication.fingerprint` of a one-leg journey without recurrence:
    /// the same stations, date, time and zone are the same journey, whatever the label.
    public var fingerprint: String {
        [
            segment.origin.normalizedId,
            segment.destination.normalizedId,
            departure.date.iso,
            departure.time.hhmm,
            departure.timeZone,
        ].joined(separator: "~") + "#recurrence:once"
    }

    /// Still to come: active and not departed at `now`.
    public func isUpcoming(now: Date) -> Bool {
        status == .active && departure.instant >= now
    }

    /// «Completa»: only an active journey, never before it was created.
    public func completing(at date: Date) throws -> PlannedJourney {
        guard status == .active else { throw PlannedJourneyValidationError.invalidStatusTimestamps }
        return try PlannedJourney(
            id: id, label: label, segment: segment, reminderMinutes: reminderMinutes, checklist: checklist,
            status: .completed, createdAt: createdAt, completedAt: date, archivedAt: nil
        )
    }

    /// The journey with one checklist line ticked or not.
    public func settingChecklistItem(id itemID: String, completed: Bool) throws -> PlannedJourney {
        guard checklist.contains(where: { $0.id == itemID }) else { throw PlannedJourneyValidationError.invalidChecklist }
        return try PlannedJourney(
            id: id, label: label, segment: segment, reminderMinutes: reminderMinutes,
            checklist: checklist.map { $0.id == itemID ? $0.setting(completed: completed) : $0 },
            status: status, createdAt: createdAt, completedAt: completedAt, archivedAt: archivedAt
        )
    }
}

/// The journeys of an Agenda: at most `PlannedJourneySchema.maximumJourneys`, one per identifier
/// and one per itinerary and schedule (Android's snapshot validation).
public struct PlannedJourneyCollection: Equatable, Sendable {
    public let journeys: [PlannedJourney]

    public init(validating journeys: [PlannedJourney]) throws {
        guard journeys.count <= PlannedJourneySchema.maximumJourneys else { throw PlannedJourneyValidationError.tooManyJourneys }
        var identifiers = Set<String>()
        var fingerprints = Set<String>()
        for journey in journeys {
            guard identifiers.insert(PlannedJourneyText.trimmed(journey.id)).inserted else {
                throw PlannedJourneyValidationError.duplicateIdentifier
            }
            guard fingerprints.insert(journey.fingerprint).inserted else { throw PlannedJourneyValidationError.duplicateJourney }
        }
        self.journeys = journeys
    }
}

/// The journeys a refused document still holds, read one by one.
public struct PlannedJourneyRecovery: Equatable, Sendable {
    public let journeys: [PlannedJourney]
    /// Records left out: unreadable, invalid, repeated or beyond the limit.
    public let unreadableRecords: Int
}

/// Android's transfer document (`PlannedJourneyTransfer`): `{"kind": "binario_planned_journeys",
/// "schemaVersion": 1, "journeys": [...]}` with Android's field names and a closed schema (an
/// unknown field refuses the record). The iPhone stores its Agenda in this form.
public enum PlannedJourneyTransfer {
    public static func encode(_ collection: PlannedJourneyCollection) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(PlannedJourneyDocument(journeys: collection.journeys))
        guard data.count <= PlannedJourneySchema.maximumEncodedBytes else { throw PlannedJourneyValidationError.tooLarge }
        return data
    }

    /// The whole document, or nil when any part of it is refused.
    public static func decode(_ data: Data) -> PlannedJourneyCollection? {
        guard !data.isEmpty, data.count <= PlannedJourneySchema.maximumEncodedBytes,
              let document = try? JSONDecoder().decode(PlannedJourneyDocument.self, from: data)
        else { return nil }
        return try? PlannedJourneyCollection(validating: document.journeys)
    }

    /// Android's `recover`: the journeys of a refused document that are valid on their own (the
    /// first of equal identifiers or itineraries, at most the limit), and how many were left out.
    /// Nil when the document itself cannot be read: too large, not JSON, another kind or version.
    public static func recover(_ data: Data) -> PlannedJourneyRecovery? {
        guard !data.isEmpty, data.count <= PlannedJourneySchema.maximumEncodedBytes,
              let document = try? JSONDecoder().decode(PlannedJourneyRecoverableDocument.self, from: data)
        else { return nil }
        var kept: [PlannedJourney] = []
        var identifiers = Set<String>()
        var fingerprints = Set<String>()
        var unreadable = 0
        for record in document.records {
            guard let journey = record.journey, kept.count < PlannedJourneySchema.maximumJourneys,
                  !identifiers.contains(PlannedJourneyText.trimmed(journey.id)),
                  !fingerprints.contains(journey.fingerprint)
            else {
                unreadable += 1
                continue
            }
            kept.append(journey)
            identifiers.insert(PlannedJourneyText.trimmed(journey.id))
            fingerprints.insert(journey.fingerprint)
        }
        return PlannedJourneyRecovery(journeys: kept, unreadableRecords: unreadable)
    }
}

/// The Agenda's lists, on the Italian wall clock of each journey.
public enum PlannedJourneyAgenda {
    /// «Prossimi»: active journeys not departed at `now`, the first to leave first.
    public static func upcoming(_ journeys: [PlannedJourney], now: Date) -> [PlannedJourney] {
        journeys.filter { $0.isUpcoming(now: now) }.sorted { first, second in
            first.departure.instant == second.departure.instant
                ? first.id < second.id
                : first.departure.instant < second.departure.instant
        }
    }

    /// «Archiviati»: completed, archived or already departed, the latest first.
    public static func archive(_ journeys: [PlannedJourney], now: Date) -> [PlannedJourney] {
        journeys.filter { !$0.isUpcoming(now: now) }.sorted { first, second in
            first.departure.instant == second.departure.instant
                ? first.id < second.id
                : first.departure.instant > second.departure.instant
        }
    }

    /// The next journey: the earliest active one not departed.
    public static func next(_ journeys: [PlannedJourney], now: Date) -> PlannedJourney? {
        upcoming(journeys, now: now).first
    }

    /// Whole minutes left until departure, rounded up («Tra 30 min»); nil once departed.
    public static func minutesUntilDeparture(of journey: PlannedJourney, now: Date) -> Int? {
        let seconds: Double = journey.departure.instant.timeIntervalSince(now)
        guard seconds >= 0, seconds.isFinite else { return nil }
        let minutes: Double = (seconds / 60).rounded(.up)
        return Int(minutes)
    }
}

// MARK: - Text rules shared with Android

enum PlannedJourneyText {
    /// Kotlin's `String.length`: UTF-16 code units.
    static func length(_ value: String) -> Int {
        value.utf16.count
    }

    /// Kotlin's `trim()`.
    static func trimmed(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Kotlin's `Char.isISOControl()`: U+0000–U+001F and U+007F–U+009F.
    static func hasControl(_ value: String) -> Bool {
        value.unicodeScalars.contains { $0.value <= 0x1F || (0x7F...0x9F).contains($0.value) }
    }

    static func hasWhitespace(_ value: String) -> Bool {
        value.unicodeScalars.contains { $0.properties.isWhitespace }
    }

    /// Android's `containsSensitiveChecklistContent`: credentials, payment, identity or contact data
    /// (a listed word, an e-mail address, a run of 13 to 19 digits) never go into a checklist.
    static func isSensitive(_ label: String) -> Bool {
        let lowered = label.lowercased()
        let terms = ["password", "passcode", "pin", "cvv", "iban", "codice fiscale", "numero carta", "credit card"]
        for term in terms where matches("\\b" + NSRegularExpression.escapedPattern(for: term) + "\\b", in: lowered) {
            return true
        }
        if matches("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", in: label, caseInsensitive: true) { return true }
        // Java's \d is ASCII: [0-9] keeps the iPhone's rule Android's.
        return matches("(?:[0-9][ -]?){13,19}", in: label)
    }

    private static func matches(_ pattern: String, in text: String, caseInsensitive: Bool = false) -> Bool {
        let options: NSRegularExpression.Options = caseInsensitive ? [.caseInsensitive] : []
        guard let expression = try? NSRegularExpression(pattern: pattern, options: options) else { return false }
        return expression.firstMatch(in: text, options: [], range: NSRange(text.startIndex..., in: text)) != nil
    }
}

/// Android's `Instant.toString()` and `Instant.parse`, for whole seconds in UTC.
enum PlannedJourneyInstant {
    static func wholeSecond(_ date: Date) -> Date {
        Date(timeIntervalSince1970: date.timeIntervalSince1970.rounded(.down))
    }

    /// "2026-11-12T10:15:30Z".
    static func format(_ date: Date) -> String {
        let interval: Double = date.timeIntervalSince1970.rounded(.down)
        let seconds: Int = interval.isFinite && abs(interval) < 300_000_000_000 ? Int(interval) : 0
        let days = ItalianWallClock.floorDivision(seconds, 86_400)
        let secondOfDay: Int = seconds - days * 86_400
        let civil = ItalianWallClock.civil(fromDays: days)
        let day = LiveText.padded(civil.year, width: 4) + "-" + LiveText.padded(civil.month, width: 2) + "-"
            + LiveText.padded(civil.day, width: 2)
        let clock = LiveText.padded(secondOfDay / 3_600, width: 2) + ":" + LiveText.padded((secondOfDay % 3_600) / 60, width: 2)
            + ":" + LiveText.padded(secondOfDay % 60, width: 2)
        return day + "T" + clock + "Z"
    }

    /// "yyyy-MM-ddTHH:mm:ss" in UTC with an optional fraction of 1 to 9 digits and "Z" (what
    /// Android writes); the fraction is dropped.
    static func parse(_ value: String) -> Date? {
        let bytes = Array(value.utf8)
        guard bytes.count >= 20, bytes.count <= 30,
              bytes[10] == UInt8(ascii: "T"), bytes[13] == UInt8(ascii: ":"), bytes[16] == UInt8(ascii: ":"),
              bytes[bytes.count - 1] == UInt8(ascii: "Z"),
              let date = PlannedLocalDate(iso: String(decoding: bytes[0..<10], as: UTF8.self)),
              let hour = ItalianWallClock.decimal(bytes[11..<13]),
              let minute = ItalianWallClock.decimal(bytes[14..<16]),
              let second = ItalianWallClock.decimal(bytes[17..<19]),
              hour < 24, minute < 60, second < 60
        else { return nil }
        let fraction = bytes[19..<(bytes.count - 1)]
        if !fraction.isEmpty {
            guard fraction.first == UInt8(ascii: "."), (2...10).contains(fraction.count),
                  ItalianWallClock.decimal(fraction.dropFirst()) != nil
            else { return nil }
        }
        let total: Int = date.daysSinceEpoch * 86_400 + hour * 3_600 + minute * 60 + second
        return Date(timeIntervalSince1970: TimeInterval(total))
    }
}

// MARK: - JSON coding (Android's field names, closed schema)

/// A key of the Agenda's JSON contract; any key decodes, so an unknown one can be refused.
struct PlannedJourneyKey: CodingKey {
    let stringValue: String

    init(_ value: String) {
        stringValue = value
    }

    init?(stringValue: String) {
        self.stringValue = stringValue
    }

    var intValue: Int? { nil }

    init?(intValue: Int) {
        return nil
    }
}

extension KeyedDecodingContainer where K == PlannedJourneyKey {
    /// Android's `requireOnly`: a field the contract does not name refuses the object.
    func requireOnly(_ allowed: Set<String>) throws {
        guard allKeys.allSatisfy({ allowed.contains($0.stringValue) }) else {
            throw PlannedJourneyValidationError.unexpectedField
        }
    }
}

extension PlannedJourneyStation: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["canonicalId", "displayName", "railwayCode"])
        try self.init(
            canonicalId: container.decode(String.self, forKey: PlannedJourneyKey("canonicalId")),
            displayName: container.decode(String.self, forKey: PlannedJourneyKey("displayName")),
            railwayCode: container.decodeIfPresent(String.self, forKey: PlannedJourneyKey("railwayCode"))
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: PlannedJourneyKey.self)
        try container.encode(canonicalId, forKey: PlannedJourneyKey("canonicalId"))
        try container.encode(displayName, forKey: PlannedJourneyKey("displayName"))
        try container.encodeIfPresent(railwayCode, forKey: PlannedJourneyKey("railwayCode"))
    }
}

extension PlannedJourneyDateTime: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["date", "time", "timeZone"])
        guard let date = PlannedLocalDate(iso: try container.decode(String.self, forKey: PlannedJourneyKey("date"))),
              let time = PlannedLocalTime(hhmm: try container.decode(String.self, forKey: PlannedJourneyKey("time")))
        else { throw PlannedJourneyValidationError.invalidDeparture }
        try self.init(date: date, time: time, timeZone: container.decode(String.self, forKey: PlannedJourneyKey("timeZone")))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: PlannedJourneyKey.self)
        try container.encode(date.iso, forKey: PlannedJourneyKey("date"))
        try container.encode(time.hhmm, forKey: PlannedJourneyKey("time"))
        try container.encode(timeZone, forKey: PlannedJourneyKey("timeZone"))
    }
}

extension PlannedJourneySegment: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["id", "origin", "destination", "departure"])
        try self.init(
            id: container.decode(String.self, forKey: PlannedJourneyKey("id")),
            origin: container.decode(PlannedJourneyStation.self, forKey: PlannedJourneyKey("origin")),
            destination: container.decode(PlannedJourneyStation.self, forKey: PlannedJourneyKey("destination")),
            departure: container.decode(PlannedJourneyDateTime.self, forKey: PlannedJourneyKey("departure"))
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: PlannedJourneyKey.self)
        try container.encode(id, forKey: PlannedJourneyKey("id"))
        try container.encode(origin, forKey: PlannedJourneyKey("origin"))
        try container.encode(destination, forKey: PlannedJourneyKey("destination"))
        try container.encode(departure, forKey: PlannedJourneyKey("departure"))
    }
}

extension PlannedJourneyChecklistItem: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["id", "label", "completed"])
        try self.init(
            id: container.decode(String.self, forKey: PlannedJourneyKey("id")),
            label: container.decode(String.self, forKey: PlannedJourneyKey("label")),
            completed: container.decode(Bool.self, forKey: PlannedJourneyKey("completed"))
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: PlannedJourneyKey.self)
        try container.encode(id, forKey: PlannedJourneyKey("id"))
        try container.encode(label, forKey: PlannedJourneyKey("label"))
        try container.encode(completed, forKey: PlannedJourneyKey("completed"))
    }
}

extension PlannedJourney: Codable {
    static let fields: Set<String> = [
        "schemaVersion", "id", "label", "itinerary", "reminderMinutes", "recurrence", "checklist",
        "status", "createdAt", "completedAt", "archivedAt", "completedOccurrenceDates",
    ]

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(Self.fields)
        guard try container.decode(Int.self, forKey: PlannedJourneyKey("schemaVersion")) == PlannedJourneySchema.version else {
            throw PlannedJourneyValidationError.unsupportedSchemaVersion
        }
        let itinerary = try container.nestedContainer(keyedBy: PlannedJourneyKey.self, forKey: PlannedJourneyKey("itinerary"))
        try itinerary.requireOnly(["segments"])
        let segments = try itinerary.decode([PlannedJourneySegment].self, forKey: PlannedJourneyKey("segments"))
        guard segments.count == 1, let segment = segments.first else { throw PlannedJourneyValidationError.unsupportedItinerary }
        let recurrence = PlannedJourneyKey("recurrence")
        if container.contains(recurrence), try !container.decodeNil(forKey: recurrence) {
            throw PlannedJourneyValidationError.unsupportedRecurrence
        }
        // Without a recurrence Android requires an empty list of completed occurrences.
        guard try container.decode([String].self, forKey: PlannedJourneyKey("completedOccurrenceDates")).isEmpty else {
            throw PlannedJourneyValidationError.invalidCompletedOccurrences
        }
        guard let status = PlannedJourneyStatus(rawValue: try container.decode(String.self, forKey: PlannedJourneyKey("status"))) else {
            throw PlannedJourneyValidationError.invalidStatusTimestamps
        }
        guard let createdAt = try Self.instant(container, "createdAt") else {
            throw PlannedJourneyValidationError.invalidTimestamp
        }
        try self.init(
            id: container.decode(String.self, forKey: PlannedJourneyKey("id")),
            label: container.decode(String.self, forKey: PlannedJourneyKey("label")),
            segment: segment,
            reminderMinutes: container.decodeIfPresent(Int.self, forKey: PlannedJourneyKey("reminderMinutes")),
            checklist: container.decode([PlannedJourneyChecklistItem].self, forKey: PlannedJourneyKey("checklist")),
            status: status,
            createdAt: createdAt,
            completedAt: Self.instant(container, "completedAt"),
            archivedAt: Self.instant(container, "archivedAt")
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: PlannedJourneyKey.self)
        try container.encode(PlannedJourneySchema.version, forKey: PlannedJourneyKey("schemaVersion"))
        try container.encode(id, forKey: PlannedJourneyKey("id"))
        try container.encode(label, forKey: PlannedJourneyKey("label"))
        var itinerary = container.nestedContainer(keyedBy: PlannedJourneyKey.self, forKey: PlannedJourneyKey("itinerary"))
        try itinerary.encode([segment], forKey: PlannedJourneyKey("segments"))
        try container.encodeIfPresent(reminderMinutes, forKey: PlannedJourneyKey("reminderMinutes"))
        try container.encode(checklist, forKey: PlannedJourneyKey("checklist"))
        try container.encode(status.rawValue, forKey: PlannedJourneyKey("status"))
        try container.encode(PlannedJourneyInstant.format(createdAt), forKey: PlannedJourneyKey("createdAt"))
        try container.encodeIfPresent(completedAt.map(PlannedJourneyInstant.format), forKey: PlannedJourneyKey("completedAt"))
        try container.encodeIfPresent(archivedAt.map(PlannedJourneyInstant.format), forKey: PlannedJourneyKey("archivedAt"))
        try container.encode([String](), forKey: PlannedJourneyKey("completedOccurrenceDates"))
    }

    /// An optional instant field: absent or null is nil, anything else must be a valid instant.
    private static func instant(_ container: KeyedDecodingContainer<PlannedJourneyKey>, _ name: String) throws -> Date? {
        guard let text = try container.decodeIfPresent(String.self, forKey: PlannedJourneyKey(name)) else { return nil }
        guard let date = PlannedJourneyInstant.parse(text) else { throw PlannedJourneyValidationError.invalidTimestamp }
        return date
    }
}

/// The whole document, refused as soon as one part is.
private struct PlannedJourneyDocument: Codable {
    let journeys: [PlannedJourney]

    init(journeys: [PlannedJourney]) {
        self.journeys = journeys
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["kind", "schemaVersion", "journeys"])
        guard try container.decode(String.self, forKey: PlannedJourneyKey("kind")) == PlannedJourneySchema.transferKind,
              try container.decode(Int.self, forKey: PlannedJourneyKey("schemaVersion")) == PlannedJourneySchema.version
        else { throw PlannedJourneyValidationError.unsupportedDocument }
        journeys = try container.decode([PlannedJourney].self, forKey: PlannedJourneyKey("journeys"))
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: PlannedJourneyKey.self)
        try container.encode(PlannedJourneySchema.transferKind, forKey: PlannedJourneyKey("kind"))
        try container.encode(PlannedJourneySchema.version, forKey: PlannedJourneyKey("schemaVersion"))
        try container.encode(journeys, forKey: PlannedJourneyKey("journeys"))
    }
}

/// The document read record by record: its kind and version must still be the Agenda's.
private struct PlannedJourneyRecoverableDocument: Decodable {
    let records: [PlannedJourneyRecord]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        guard try container.decode(String.self, forKey: PlannedJourneyKey("kind")) == PlannedJourneySchema.transferKind,
              try container.decode(Int.self, forKey: PlannedJourneyKey("schemaVersion")) == PlannedJourneySchema.version
        else { throw PlannedJourneyValidationError.unsupportedDocument }
        records = try container.decode([PlannedJourneyRecord].self, forKey: PlannedJourneyKey("journeys"))
    }
}

/// One stored record, nil when it is not a valid journey on its own.
private struct PlannedJourneyRecord: Decodable {
    let journey: PlannedJourney?

    init(from decoder: Decoder) throws {
        journey = try? PlannedJourney(from: decoder)
    }
}
