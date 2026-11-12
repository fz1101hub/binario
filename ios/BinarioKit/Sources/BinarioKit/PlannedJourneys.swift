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
    /// As Android's `MAX_NESTING_DEPTH`: the document nests seven levels; deeper is refused unread.
    public static let maximumNestingDepth = 16
    /// Android's `PlannedJourneyReminderMinutes`.
    public static let reminderMinutes: Set<Int> = [5, 10, 15, 20, 30, 45, 60]
    /// As Android's `MAX_ITINERARY_SEGMENTS`: a journey has one to four legs.
    public static let maximumSegments = 4
    /// As Android's `MAX_RECURRENCE_OCCURRENCES`: a repeating journey departs at most twelve times.
    public static let maximumOccurrences = 12
}

/// Why a planned journey, or a list of them, was refused.
public enum PlannedJourneyValidationError: Error, Equatable, Sendable {
    case unsupportedSchemaVersion
    case unexpectedField
    case invalidIdentifier
    case invalidLabel
    /// No leg, or more than `PlannedJourneySchema.maximumSegments`.
    case invalidItinerary
    /// A leg without an identifier, or two legs with the same one.
    case invalidSegment
    case invalidStation
    case sameStation
    /// A leg does not start at the station where the leg before it arrives.
    case disconnectedItinerary
    /// A leg does not depart after the leg before it.
    case nonChronologicalItinerary
    case invalidDeparture
    /// The daylight-saving change skips this time in that zone: it has no instant.
    case nonexistentLocalTime
    /// Android's `INVALID_RECURRENCE` and `RECURRENCE_START_MISMATCH`: no weekday, a repeated one,
    /// a start that is not the first departure's date or not one of its weekdays, a count outside
    /// 1…12, an end before the start, or an occurrence whose time the clock change skips.
    case invalidRecurrence
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

/// One leg of a journey (Android's itinerary holds one to four).
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

    /// The same leg `days` later at the same clock time (an occurrence of a recurrence); refused
    /// when that time does not exist then.
    func shifted(days: Int) throws -> PlannedJourneySegment {
        guard days != 0 else { return self }
        guard let date = departure.date.adding(days: days) else { throw PlannedJourneyValidationError.invalidRecurrence }
        return try PlannedJourneySegment(
            id: id, origin: origin, destination: destination,
            departure: PlannedJourneyDateTime(date: date, time: departure.time, timeZone: departure.timeZone)
        )
    }
}

/// A day of the week by Android's `DayOfWeek` name, as the transfer document writes it.
public enum PlannedJourneyWeekday: String, CaseIterable, Comparable, Hashable, Sendable {
    case monday = "MONDAY"
    case tuesday = "TUESDAY"
    case wednesday = "WEDNESDAY"
    case thursday = "THURSDAY"
    case friday = "FRIDAY"
    case saturday = "SATURDAY"
    case sunday = "SUNDAY"

    /// 1 Monday … 7 Sunday, as `DayOfWeek.value` and `PlannedLocalDate.isoWeekday`.
    public var isoNumber: Int {
        switch self {
        case .monday: 1
        case .tuesday: 2
        case .wednesday: 3
        case .thursday: 4
        case .friday: 5
        case .saturday: 6
        case .sunday: 7
        }
    }

    /// The weekday of `date`.
    public init(of date: PlannedLocalDate) {
        self = Self.allCases.first { $0.isoNumber == date.isoWeekday } ?? .monday
    }

    public static func < (lhs: PlannedJourneyWeekday, rhs: PlannedJourneyWeekday) -> Bool {
        lhs.isoNumber < rhs.isoNumber
    }
}

/// Android's `PlannedJourneyRecurrence`: an explicit, finite weekly repetition of a journey at the
/// same clock times, from the first departure's date, at most twelve departures in all.
public struct PlannedJourneyRecurrence: Equatable, Hashable, Sendable {
    /// In the order stored (Android's editor writes them Monday first).
    public let weekdays: [PlannedJourneyWeekday]
    /// The first departure's date, which is one of `weekdays`.
    public let startsOn: PlannedLocalDate
    /// The last day an occurrence may fall on; nil for none.
    public let endsOn: PlannedLocalDate?
    /// How many journeys the recurrence makes in all, 1 to 12.
    public let occurrenceLimit: Int

    public init(
        weekdays: [PlannedJourneyWeekday],
        startsOn: PlannedLocalDate,
        endsOn: PlannedLocalDate? = nil,
        occurrenceLimit: Int
    ) throws {
        guard !weekdays.isEmpty, Set(weekdays).count == weekdays.count,
              weekdays.contains(PlannedJourneyWeekday(of: startsOn)),
              (1...PlannedJourneySchema.maximumOccurrences).contains(occurrenceLimit)
        else { throw PlannedJourneyValidationError.invalidRecurrence }
        if let endsOn, endsOn < startsOn { throw PlannedJourneyValidationError.invalidRecurrence }
        self.weekdays = weekdays
        self.startsOn = startsOn
        self.endsOn = endsOn
        self.occurrenceLimit = occurrenceLimit
    }

    /// Android's `PlannedJourneyOccurrences.dates`: the days from `startsOn` that fall on one of
    /// the weekdays, at most `occurrenceLimit` and none after `endsOn`.
    public var dates: [PlannedLocalDate] {
        let enabled = Set(weekdays.map(\.isoNumber))
        var result: [PlannedLocalDate] = []
        var cursor: PlannedLocalDate? = startsOn
        while let day = cursor, result.count < occurrenceLimit {
            if let endsOn, endsOn < day { break }
            if enabled.contains(day.isoWeekday) { result.append(day) }
            if day == endsOn { break }
            cursor = day.adding(days: 1)
        }
        return result
    }

    /// Android's part of the fingerprint: "1~MONDAY,THURSDAY~2026-11-16~2026-12-31~4".
    var fingerprint: String {
        [
            String(PlannedJourneySchema.version),
            weekdays.sorted().map(\.rawValue).joined(separator: ","),
            startsOn.iso,
            endsOn?.iso ?? "",
            String(occurrenceLimit),
        ].joined(separator: "~")
    }
}

/// One departure of a journey (Android's `PlannedJourneyOccurrence`): its legs on that date. A
/// journey without a recurrence has one, on its own date.
public struct PlannedJourneyOccurrence: Equatable, Sendable {
    /// The first leg's local date, the key of a completed occurrence and of a calendar event.
    public let date: PlannedLocalDate
    public let segments: [PlannedJourneySegment]

    /// The first leg's departure.
    public var departure: PlannedJourneyDateTime { segments[0].departure }
    public var instant: Date { departure.instant }
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

/// A personal planned journey of the Agenda, on this iPhone only (Android's `PlannedJourney`): one
/// to four connected legs between catalogue stations at local dates and times, optionally repeated
/// on chosen weekdays, with an optional label and checklist. It holds no train, platform, delay,
/// live state or ticket. Every value is checked when it is built, also when it is read back, so a
/// stored journey is always a valid one.
public struct PlannedJourney: Equatable, Identifiable, Sendable {
    public let id: String
    public let label: String
    /// One to four legs, each starting where the one before arrives and departing after it.
    public let segments: [PlannedJourneySegment]
    /// Minutes before departure of the local reminder (Android's values); nil is none.
    public let reminderMinutes: Int?
    /// Nil for a journey that departs once.
    public let recurrence: PlannedJourneyRecurrence?
    public let checklist: [PlannedJourneyChecklistItem]
    public let status: PlannedJourneyStatus
    /// Whole seconds, as the document stores them.
    public let createdAt: Date
    public let completedAt: Date?
    public let archivedAt: Date?
    /// The occurrences of the recurrence marked done, in the stored order; empty without one.
    public let completedOccurrenceDates: [PlannedLocalDate]
    /// Every departure of the journey, the first first: one, or one per date of the recurrence.
    public let occurrences: [PlannedJourneyOccurrence]
    /// The fractions of a second of the timestamps read from a document (Android writes
    /// milliseconds), kept so that the journey is written back as it was read.
    var instantFractions = PlannedJourneyInstantFractions()

    /// A one-leg journey.
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
        try self.init(
            id: id, label: label, segments: [segment], reminderMinutes: reminderMinutes, checklist: checklist,
            status: status, createdAt: createdAt, completedAt: completedAt, archivedAt: archivedAt
        )
    }

    /// Android's validation (`PlannedJourneyValidator`) of a whole journey.
    public init(
        id: String,
        label: String = "",
        segments: [PlannedJourneySegment],
        reminderMinutes: Int? = nil,
        recurrence: PlannedJourneyRecurrence? = nil,
        checklist: [PlannedJourneyChecklistItem] = [],
        status: PlannedJourneyStatus = .active,
        createdAt: Date,
        completedAt: Date? = nil,
        archivedAt: Date? = nil,
        completedOccurrenceDates: [PlannedLocalDate] = []
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
        let occurrences = try Self.validatedOccurrences(of: segments, recurrence: recurrence)
        let occurrenceDates = Set(occurrences.map(\.date))
        guard recurrence != nil || completedOccurrenceDates.isEmpty,
              Set(completedOccurrenceDates).count == completedOccurrenceDates.count,
              completedOccurrenceDates.count <= PlannedJourneySchema.maximumOccurrences,
              completedOccurrenceDates.allSatisfy({ occurrenceDates.contains($0) })
        else { throw PlannedJourneyValidationError.invalidCompletedOccurrences }

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
        self.segments = segments
        self.reminderMinutes = reminderMinutes
        self.recurrence = recurrence
        self.checklist = checklist
        self.status = status
        self.createdAt = created
        self.completedAt = completed
        self.archivedAt = archived
        self.completedOccurrenceDates = completedOccurrenceDates
        self.occurrences = occurrences
    }

    /// Android's itinerary rules (one to four legs with distinct identifiers, each leaving from the
    /// station the one before arrives at and after it), then one occurrence per date of the
    /// recurrence, each with valid chronological local times.
    private static func validatedOccurrences(
        of segments: [PlannedJourneySegment],
        recurrence: PlannedJourneyRecurrence?
    ) throws -> [PlannedJourneyOccurrence] {
        guard (1...PlannedJourneySchema.maximumSegments).contains(segments.count), let first = segments.first else {
            throw PlannedJourneyValidationError.invalidItinerary
        }
        guard Set(segments.map { PlannedJourneyText.trimmed($0.id) }).count == segments.count else {
            throw PlannedJourneyValidationError.invalidSegment
        }
        for (previous, next) in zip(segments, segments.dropFirst()) {
            guard previous.destination.normalizedId == next.origin.normalizedId else {
                throw PlannedJourneyValidationError.disconnectedItinerary
            }
            guard next.departure.instant > previous.departure.instant else {
                throw PlannedJourneyValidationError.nonChronologicalItinerary
            }
        }
        let root = first.departure.date
        guard let recurrence else { return [PlannedJourneyOccurrence(date: root, segments: segments)] }
        guard recurrence.startsOn == root else { throw PlannedJourneyValidationError.invalidRecurrence }
        return try recurrence.dates.map { (date: PlannedLocalDate) throws -> PlannedJourneyOccurrence in
            let days = date.daysSinceEpoch - root.daysSinceEpoch
            let shifted: [PlannedJourneySegment]
            do {
                shifted = try segments.map { try $0.shifted(days: days) }
            } catch {
                throw PlannedJourneyValidationError.invalidRecurrence
            }
            for (previous, next) in zip(shifted, shifted.dropFirst()) where next.departure.instant <= previous.departure.instant {
                throw PlannedJourneyValidationError.invalidRecurrence
            }
            return PlannedJourneyOccurrence(date: date, segments: shifted)
        }
    }

    /// The first leg.
    public var segment: PlannedJourneySegment { segments[0] }
    /// Where the first leg leaves from.
    public var origin: PlannedJourneyStation { segments[0].origin }
    /// Where the last leg arrives.
    public var destination: PlannedJourneyStation { segments[segments.count - 1].destination }
    /// The first leg's departure, on the first date of a recurrence.
    public var departure: PlannedJourneyDateTime { segments[0].departure }

    /// Android's `PlannedJourneyDeduplication.fingerprint`: the same legs (stations, dates, times
    /// and zones) and the same recurrence are the same journey, whatever the label.
    public var fingerprint: String {
        segments.map { segment in
            [
                segment.origin.normalizedId,
                segment.destination.normalizedId,
                segment.departure.date.iso,
                segment.departure.time.hhmm,
                segment.departure.timeZone,
            ].joined(separator: "~")
        }.joined(separator: "|") + "#recurrence:" + (recurrence?.fingerprint ?? "once")
    }

    /// The next departure still to come at `now` (Android's `isUpcoming`): an active journey, an
    /// occurrence not departed and not marked done. Nil when there is none.
    public func nextOccurrence(now: Date) -> PlannedJourneyOccurrence? {
        guard status == .active else { return nil }
        return occurrences.first { $0.instant >= now && !completedOccurrenceDates.contains($0.date) }
    }

    /// The occurrence a list shows: the next one to come, or the last one once none is left.
    public func shownOccurrence(now: Date) -> PlannedJourneyOccurrence {
        nextOccurrence(now: now) ?? occurrences[occurrences.count - 1]
    }

    /// Still to come: active, with an occurrence not departed nor done at `now`.
    public func isUpcoming(now: Date) -> Bool {
        nextOccurrence(now: now) != nil
    }

    /// «Completa»: only an active journey, never before it was created.
    public func completing(at date: Date) throws -> PlannedJourney {
        guard status == .active else { throw PlannedJourneyValidationError.invalidStatusTimestamps }
        return try rebuilt(status: .completed, completedAt: date)
    }

    /// «Completa» on one departure of a repeating journey (Android's `completeOccurrence`): the
    /// journey stays active, that date is marked done.
    public func completingOccurrence(on date: PlannedLocalDate) throws -> PlannedJourney {
        guard status == .active, recurrence != nil, occurrences.contains(where: { $0.date == date }) else {
            throw PlannedJourneyValidationError.invalidCompletedOccurrences
        }
        let done = Set(completedOccurrenceDates + [date]).sorted()
        return try rebuilt(completedOccurrenceDates: done)
    }

    /// The journey with one checklist line ticked or not.
    public func settingChecklistItem(id itemID: String, completed: Bool) throws -> PlannedJourney {
        guard checklist.contains(where: { $0.id == itemID }) else { throw PlannedJourneyValidationError.invalidChecklist }
        return try rebuilt(checklist: checklist.map { $0.id == itemID ? $0.setting(completed: completed) : $0 })
    }

    private func rebuilt(
        checklist: [PlannedJourneyChecklistItem]? = nil,
        status: PlannedJourneyStatus? = nil,
        completedAt: Date?? = nil,
        archivedAt: Date?? = nil,
        completedOccurrenceDates: [PlannedLocalDate]? = nil
    ) throws -> PlannedJourney {
        var journey = try PlannedJourney(
            id: id, label: label, segments: segments, reminderMinutes: reminderMinutes, recurrence: recurrence,
            checklist: checklist ?? self.checklist, status: status ?? self.status, createdAt: createdAt,
            completedAt: completedAt ?? self.completedAt, archivedAt: archivedAt ?? self.archivedAt,
            completedOccurrenceDates: completedOccurrenceDates ?? self.completedOccurrenceDates
        )
        journey.instantFractions = PlannedJourneyInstantFractions(
            created: instantFractions.created,
            completed: completedAt == nil ? instantFractions.completed : 0,
            archived: archivedAt == nil ? instantFractions.archived : 0
        )
        return journey
    }

    /// `edited` with this journey's fractions of a second, for the timestamps it keeps.
    func keepingInstantFractions(in edited: PlannedJourney) -> PlannedJourney {
        var journey = edited
        journey.instantFractions = PlannedJourneyInstantFractions(
            created: edited.createdAt == createdAt ? instantFractions.created : 0,
            completed: edited.completedAt == completedAt ? instantFractions.completed : 0,
            archived: edited.archivedAt == archivedAt ? instantFractions.archived : 0
        )
        return journey
    }
}

/// Nanoseconds of the timestamps of a journey, 0 for the whole seconds the iPhone writes.
struct PlannedJourneyInstantFractions: Equatable, Sendable {
    var created = 0
    var completed = 0
    var archived = 0
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
    /// The bytes an Android phone writes for these journeys (`PlannedJourneyTransfer.encode` with
    /// Android's org.json): Android's field order, no spaces, "/" escaped, the timestamps as
    /// `Instant.toString()`. A document read from Android is written back byte for byte.
    public static func encode(_ collection: PlannedJourneyCollection) throws -> Data {
        let document = PlannedJourneyJSON.object([
            ("kind", PlannedJourneyJSON.string(PlannedJourneySchema.transferKind)),
            ("schemaVersion", String(PlannedJourneySchema.version)),
            ("journeys", PlannedJourneyJSON.array(collection.journeys.map(PlannedJourneyJSON.journey))),
        ])
        let data = Data(document.utf8)
        guard data.count <= PlannedJourneySchema.maximumEncodedBytes else { throw PlannedJourneyValidationError.tooLarge }
        return data
    }

    /// The whole document, or nil when any part of it is refused.
    public static func decode(_ data: Data) -> PlannedJourneyCollection? {
        guard !data.isEmpty, data.count <= PlannedJourneySchema.maximumEncodedBytes,
              nestingDepth(data) <= PlannedJourneySchema.maximumNestingDepth,
              let document = try? JSONDecoder().decode(PlannedJourneyDocument.self, from: data)
        else { return nil }
        return try? PlannedJourneyCollection(validating: document.journeys)
    }

    /// Android's `recover`: the journeys of a refused document that are valid on their own (the
    /// first of equal identifiers or itineraries, at most the limit), and how many were left out.
    /// Nil when the document itself cannot be read: too large, not JSON, another kind or version.
    public static func recover(_ data: Data) -> PlannedJourneyRecovery? {
        guard !data.isEmpty, data.count <= PlannedJourneySchema.maximumEncodedBytes,
              nestingDepth(data) <= PlannedJourneySchema.maximumNestingDepth,
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

    /// Android's `nestingDepth`: the deepest array or object outside strings, counted on the bytes
    /// before any parsing, stopping as soon as the limit is passed.
    static func nestingDepth(_ data: Data) -> Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for byte in data {
            if inString {
                if escaped {
                    escaped = false
                } else if byte == UInt8(ascii: "\\") {
                    escaped = true
                } else if byte == UInt8(ascii: "\"") {
                    inString = false
                }
                continue
            }
            switch byte {
            case UInt8(ascii: "\""):
                inString = true
            case UInt8(ascii: "["), UInt8(ascii: "{"):
                depth += 1
                deepest = max(deepest, depth)
                if deepest > PlannedJourneySchema.maximumNestingDepth { return deepest }
            case UInt8(ascii: "]"), UInt8(ascii: "}"):
                depth -= 1
            default:
                break
            }
        }
        return deepest
    }
}

/// The Agenda's lists, on the Italian wall clock of each journey. A journey is one row: a repeating
/// one shows its next departure, and goes to the archive once none is left.
public enum PlannedJourneyAgenda {
    /// «Prossimi»: journeys with a departure still to come at `now`, the next to leave first.
    public static func upcoming(_ journeys: [PlannedJourney], now: Date) -> [PlannedJourney] {
        journeys.compactMap { journey in journey.nextOccurrence(now: now).map { (journey, $0.instant) } }
            .sorted { first, second in first.1 == second.1 ? first.0.id < second.0.id : first.1 < second.1 }
            .map(\.0)
    }

    /// «Archiviati»: completed, archived or with every departure gone or done, the latest first.
    public static func archive(_ journeys: [PlannedJourney], now: Date) -> [PlannedJourney] {
        journeys.filter { !$0.isUpcoming(now: now) }
            .map { journey in (journey, journey.shownOccurrence(now: now).instant) }
            .sorted { first, second in first.1 == second.1 ? first.0.id < second.0.id : first.1 > second.1 }
            .map(\.0)
    }

    /// The next journey: the earliest active one not departed.
    public static func next(_ journeys: [PlannedJourney], now: Date) -> PlannedJourney? {
        upcoming(journeys, now: now).first
    }

    /// Whole minutes left until the next departure, rounded up («Tra 30 min»); nil once departed.
    public static func minutesUntilDeparture(of journey: PlannedJourney, now: Date) -> Int? {
        guard let next = journey.nextOccurrence(now: now) else { return nil }
        let seconds: Double = next.instant.timeIntervalSince(now)
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

    /// Java's `Instant.toString()`: the fraction in groups of three digits, as many as needed
    /// ("…:15.250Z", "…:15.250100Z"), none for a whole second.
    static func format(_ date: Date, nanoseconds: Int) -> String {
        let whole = format(date)
        guard (1...999_999_999).contains(nanoseconds) else { return whole }
        var digits = LiveText.padded(nanoseconds, width: 9)
        while digits.count > 3, digits.hasSuffix("000") {
            digits.removeLast(3)
        }
        return String(whole.dropLast()) + "." + digits + "Z"
    }

    /// The fraction of a second of a valid instant, in nanoseconds: 0 without one.
    static func nanoseconds(_ value: String) -> Int? {
        guard parse(value) != nil else { return nil }
        let bytes = Array(value.utf8)
        let fraction = bytes[19..<(bytes.count - 1)].dropFirst()
        guard !fraction.isEmpty else { return 0 }
        guard let number = ItalianWallClock.decimal(fraction) else { return nil }
        var nanoseconds = number
        for _ in fraction.count..<9 {
            nanoseconds *= 10
        }
        return nanoseconds
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

extension PlannedJourneyStation: Decodable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["canonicalId", "displayName", "railwayCode"])
        try self.init(
            canonicalId: container.decode(String.self, forKey: PlannedJourneyKey("canonicalId")),
            displayName: container.decode(String.self, forKey: PlannedJourneyKey("displayName")),
            railwayCode: container.decodeIfPresent(String.self, forKey: PlannedJourneyKey("railwayCode"))
        )
    }
}

extension PlannedJourneyDateTime: Decodable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["date", "time", "timeZone"])
        guard let date = PlannedLocalDate(iso: try container.decode(String.self, forKey: PlannedJourneyKey("date"))),
              let time = PlannedLocalTime(hhmm: try container.decode(String.self, forKey: PlannedJourneyKey("time")))
        else { throw PlannedJourneyValidationError.invalidDeparture }
        try self.init(date: date, time: time, timeZone: container.decode(String.self, forKey: PlannedJourneyKey("timeZone")))
    }
}

extension PlannedJourneySegment: Decodable {
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
}

extension PlannedJourneyRecurrence: Decodable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["schemaVersion", "weekdays", "startsOn", "endsOn", "occurrenceLimit"])
        guard try container.decode(Int.self, forKey: PlannedJourneyKey("schemaVersion")) == PlannedJourneySchema.version else {
            throw PlannedJourneyValidationError.unsupportedSchemaVersion
        }
        let weekdays = try container.decode([String].self, forKey: PlannedJourneyKey("weekdays")).map { (name: String) throws -> PlannedJourneyWeekday in
            guard let weekday = PlannedJourneyWeekday(rawValue: name) else { throw PlannedJourneyValidationError.invalidRecurrence }
            return weekday
        }
        guard let startsOn = PlannedLocalDate(iso: try container.decode(String.self, forKey: PlannedJourneyKey("startsOn"))) else {
            throw PlannedJourneyValidationError.invalidRecurrence
        }
        var endsOn: PlannedLocalDate?
        if let text = try container.decodeIfPresent(String.self, forKey: PlannedJourneyKey("endsOn")) {
            guard let date = PlannedLocalDate(iso: text) else { throw PlannedJourneyValidationError.invalidRecurrence }
            endsOn = date
        }
        try self.init(
            weekdays: weekdays,
            startsOn: startsOn,
            endsOn: endsOn,
            occurrenceLimit: container.decode(Int.self, forKey: PlannedJourneyKey("occurrenceLimit"))
        )
    }
}

extension PlannedJourneyChecklistItem: Decodable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["id", "label", "completed"])
        try self.init(
            id: container.decode(String.self, forKey: PlannedJourneyKey("id")),
            label: container.decode(String.self, forKey: PlannedJourneyKey("label")),
            completed: container.decode(Bool.self, forKey: PlannedJourneyKey("completed"))
        )
    }
}

extension PlannedJourney: Decodable {
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
        // Absent or null is a journey that departs once, as Android's `optionalObject`.
        let recurrence = try container.decodeIfPresent(PlannedJourneyRecurrence.self, forKey: PlannedJourneyKey("recurrence"))
        let completedOccurrenceDates = try container.decode([String].self, forKey: PlannedJourneyKey("completedOccurrenceDates"))
            .map { (text: String) throws -> PlannedLocalDate in
                guard let date = PlannedLocalDate(iso: text) else { throw PlannedJourneyValidationError.invalidCompletedOccurrences }
                return date
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
            segments: segments,
            reminderMinutes: container.decodeIfPresent(Int.self, forKey: PlannedJourneyKey("reminderMinutes")),
            recurrence: recurrence,
            checklist: container.decode([PlannedJourneyChecklistItem].self, forKey: PlannedJourneyKey("checklist")),
            status: status,
            createdAt: createdAt,
            completedAt: Self.instant(container, "completedAt"),
            archivedAt: Self.instant(container, "archivedAt"),
            completedOccurrenceDates: completedOccurrenceDates
        )
        instantFractions = try PlannedJourneyInstantFractions(
            created: Self.fraction(container, "createdAt"),
            completed: Self.fraction(container, "completedAt"),
            archived: Self.fraction(container, "archivedAt")
        )
    }

    /// An optional instant field: absent or null is nil, anything else must be a valid instant.
    private static func instant(_ container: KeyedDecodingContainer<PlannedJourneyKey>, _ name: String) throws -> Date? {
        guard let text = try container.decodeIfPresent(String.self, forKey: PlannedJourneyKey(name)) else { return nil }
        guard let date = PlannedJourneyInstant.parse(text) else { throw PlannedJourneyValidationError.invalidTimestamp }
        return date
    }

    /// The nanoseconds of an instant field as written, 0 when absent or whole.
    private static func fraction(_ container: KeyedDecodingContainer<PlannedJourneyKey>, _ name: String) throws -> Int {
        guard let text = try container.decodeIfPresent(String.self, forKey: PlannedJourneyKey(name)) else { return 0 }
        return PlannedJourneyInstant.nanoseconds(text) ?? 0
    }
}

/// Android's org.json output on a phone (`JSONObject.toString()`, whose objects keep the order the
/// fields were put in): no spaces, `"`, `\` and `/` escaped with a backslash, tab, backspace, line
/// feed, carriage return and form feed by their letter, other control characters as `\u00xx`, every
/// other character as itself; whole numbers as digits.
enum PlannedJourneyJSON {
    static func string(_ value: String) -> String {
        var result = "\""
        for scalar in value.unicodeScalars {
            switch scalar {
            case "\"": result += "\\\""
            case "\\": result += "\\\\"
            case "/": result += "\\/"
            case "\t": result += "\\t"
            case "\u{08}": result += "\\b"
            case "\n": result += "\\n"
            case "\r": result += "\\r"
            case "\u{0C}": result += "\\f"
            default:
                if scalar.value <= 0x1F {
                    let hex = String(scalar.value, radix: 16)
                    result += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    result.unicodeScalars.append(scalar)
                }
            }
        }
        return result + "\""
    }

    static func object(_ fields: [(String, String)]) -> String {
        "{" + fields.map { string($0.0) + ":" + $0.1 }.joined(separator: ",") + "}"
    }

    static func array(_ values: [String]) -> String {
        "[" + values.joined(separator: ",") + "]"
    }

    /// Android's `journeyToJson`, field by field in its order; absent optionals are left out.
    static func journey(_ journey: PlannedJourney) -> String {
        var fields: [(String, String)] = [
            ("schemaVersion", String(PlannedJourneySchema.version)),
            ("id", string(journey.id)),
            ("label", string(journey.label)),
            ("itinerary", object([("segments", array(journey.segments.map(segment)))])),
        ]
        if let minutes = journey.reminderMinutes {
            fields.append(("reminderMinutes", String(minutes)))
        }
        if let recurrence = journey.recurrence {
            fields.append(("recurrence", self.recurrence(recurrence)))
        }
        fields.append(("checklist", array(journey.checklist.map(checklistItem))))
        fields.append(("status", string(journey.status.rawValue)))
        let fractions = journey.instantFractions
        fields.append(("createdAt", string(PlannedJourneyInstant.format(journey.createdAt, nanoseconds: fractions.created))))
        if let completedAt = journey.completedAt {
            fields.append(("completedAt", string(PlannedJourneyInstant.format(completedAt, nanoseconds: fractions.completed))))
        }
        if let archivedAt = journey.archivedAt {
            fields.append(("archivedAt", string(PlannedJourneyInstant.format(archivedAt, nanoseconds: fractions.archived))))
        }
        fields.append(("completedOccurrenceDates", array(journey.completedOccurrenceDates.map { string($0.iso) })))
        return object(fields)
    }

    static func segment(_ segment: PlannedJourneySegment) -> String {
        object([
            ("id", string(segment.id)),
            ("origin", station(segment.origin)),
            ("destination", station(segment.destination)),
            ("departure", object([
                ("date", string(segment.departure.date.iso)),
                ("time", string(segment.departure.time.hhmm)),
                ("timeZone", string(segment.departure.timeZone)),
            ])),
        ])
    }

    static func station(_ station: PlannedJourneyStation) -> String {
        var fields = [("canonicalId", string(station.canonicalId)), ("displayName", string(station.displayName))]
        if let code = station.railwayCode {
            fields.append(("railwayCode", string(code)))
        }
        return object(fields)
    }

    static func recurrence(_ recurrence: PlannedJourneyRecurrence) -> String {
        var fields = [
            ("schemaVersion", String(PlannedJourneySchema.version)),
            ("weekdays", array(recurrence.weekdays.map { string($0.rawValue) })),
            ("startsOn", string(recurrence.startsOn.iso)),
        ]
        if let endsOn = recurrence.endsOn {
            fields.append(("endsOn", string(endsOn.iso)))
        }
        fields.append(("occurrenceLimit", String(recurrence.occurrenceLimit)))
        return object(fields)
    }

    static func checklistItem(_ item: PlannedJourneyChecklistItem) -> String {
        object([("id", string(item.id)), ("label", string(item.label)), ("completed", item.completed ? "true" : "false")])
    }
}

/// The whole document, refused as soon as one part is.
private struct PlannedJourneyDocument: Decodable {
    let journeys: [PlannedJourney]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: PlannedJourneyKey.self)
        try container.requireOnly(["kind", "schemaVersion", "journeys"])
        guard try container.decode(String.self, forKey: PlannedJourneyKey("kind")) == PlannedJourneySchema.transferKind,
              try container.decode(Int.self, forKey: PlannedJourneyKey("schemaVersion")) == PlannedJourneySchema.version
        else { throw PlannedJourneyValidationError.unsupportedDocument }
        journeys = try container.decode([PlannedJourney].self, forKey: PlannedJourneyKey("journeys"))
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
