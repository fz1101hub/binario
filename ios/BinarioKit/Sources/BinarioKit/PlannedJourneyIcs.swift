import Foundation

/// Android's calendar contract (`PlannedJourneyIcsContract`): the same bounds and product
/// identifier, so a calendar exported by one phone is recognised by the other.
public enum PlannedJourneyIcsContract {
    public static let maximumBytes = 262_144
    public static let maximumEvents = 120
    public static let productID = "-//Binario//Planned Journeys 1.0//IT"
    /// RFC 5545: a physical line holds at most 75 octets.
    public static let maximumLineOctets = 75
}

/// The words of an exported calendar in the user's language; property names, times and the
/// Binario fields stay language-neutral.
public struct PlannedJourneyIcsText: Sendable {
    /// The description of a leg: origin, destination, ISO date, "HH:MM", zone.
    public let describe: @Sendable (PlannedJourneySegment) -> String
    public let reminderDescription: String

    public init(describe: @escaping @Sendable (PlannedJourneySegment) -> String, reminderDescription: String) {
        self.describe = describe
        self.reminderDescription = reminderDescription
    }

    /// Android's `PlannedJourneyIcsText.ITALIAN`.
    public static let italian = PlannedJourneyIcsText(
        describe: { segment in
            "Viaggio: \(segment.origin.displayName) → \(segment.destination.displayName), partenza "
                + "\(segment.departure.date.iso) alle \(segment.departure.time.hhmm) (\(segment.departure.timeZone))"
        },
        reminderDescription: "Promemoria viaggio Binario"
    )
}

/// One event of a calendar written by Binario.
public struct PlannedJourneyCalendarEvent: Equatable, Sendable {
    public let uid: String
    public let journeyID: String
    public let occurrenceDate: PlannedLocalDate
    public let startsAt: Date
    public let summary: String
    public let reminderMinutes: Int?
}

/// Why a calendar was refused.
public enum PlannedJourneyIcsError: Error, Equatable, Sendable {
    case tooLarge
    case tooManyEvents
    case malformed
    /// Not written by Binario (another product identifier), or an event whose identifier does not
    /// match its Binario fields.
    case notBinario
    /// A `TZID` that is not an IANA zone (for example a Windows name): the time cannot be placed.
    case unknownTimeZone
    /// A local time the daylight-saving change skips in that zone.
    case nonexistentLocalTime
    /// A date without a time.
    case allDay
}

/// An owned calendar compared with the Agenda (Android's `PlannedJourneyIcsImportPreview`): a
/// calendar is a check, never a way to create or overwrite journeys.
public struct PlannedJourneyIcsPreview: Equatable, Sendable {
    public let matching: [PlannedJourneyCalendarEvent]
    public let conflicting: [PlannedJourneyCalendarEvent]
    public let unlinked: [PlannedJourneyCalendarEvent]
}

/// Android's RFC 5545 boundary (`PlannedJourneyIcs`): the export of the Agenda as events in UTC,
/// and a bounded reading of a calendar Binario wrote.
public enum PlannedJourneyIcs {
    /// Every occurrence of every journey as one event (a repeating journey gives one per date), the
    /// first to leave first, at most `maximumEvents`: `DTSTART` in UTC at the first leg's departure,
    /// no end time (Binario does not know the arrival), each leg described on its own line,
    /// Binario's identifier fields and a display alarm for a reminder. CRLF line ends, lines folded
    /// at 75 octets.
    public static func export(
        _ journeys: [PlannedJourney],
        generatedAt: Date,
        text: PlannedJourneyIcsText = .italian
    ) throws -> String {
        let events = journeys.flatMap { journey in journey.occurrences.map { (journey, $0) } }.sorted { first, second in
            first.1.instant == second.1.instant ? first.0.id < second.0.id : first.1.instant < second.1.instant
        }.prefix(PlannedJourneyIcsContract.maximumEvents)
        var logical = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:" + escape(PlannedJourneyIcsContract.productID),
                       "CALSCALE:GREGORIAN", "METHOD:PUBLISH"]
        for (journey, occurrence) in events {
            logical += eventLines(journey, occurrence, generatedAt: generatedAt, text: text)
        }
        logical.append("END:VCALENDAR")
        let calendar = logical.flatMap(fold).joined(separator: "\r\n") + "\r\n"
        guard calendar.utf8.count <= PlannedJourneyIcsContract.maximumBytes else { throw PlannedJourneyIcsError.tooLarge }
        return calendar
    }

    /// The events of a calendar Binario wrote, refused whole when it is too large, holds too many
    /// events, is not Binario's or has an event that cannot be read.
    public static func parseOwnedCalendar(_ calendar: String) -> Result<[PlannedJourneyCalendarEvent], PlannedJourneyIcsError> {
        guard calendar.utf8.count <= PlannedJourneyIcsContract.maximumBytes else { return .failure(.tooLarge) }
        guard let lines = unfold(calendar) else { return .failure(.malformed) }
        guard lines.first == "BEGIN:VCALENDAR", lines.last == "END:VCALENDAR" else { return .failure(.malformed) }
        guard lines.contains("PRODID:" + escape(PlannedJourneyIcsContract.productID)) else { return .failure(.notBinario) }
        var events: [PlannedJourneyCalendarEvent] = []
        var cursor = 0
        while cursor < lines.count {
            guard lines[cursor] == "BEGIN:VEVENT" else {
                cursor += 1
                continue
            }
            guard let end = lines[(cursor + 1)...].firstIndex(of: "END:VEVENT") else { return .failure(.malformed) }
            switch event(Array(lines[(cursor + 1)..<end])) {
            case let .success(parsed): events.append(parsed)
            case let .failure(error): return .failure(error)
            }
            guard events.count <= PlannedJourneyIcsContract.maximumEvents else { return .failure(.tooManyEvents) }
            cursor = end + 1
        }
        return .success(events)
    }

    /// Android's preview: an event matches when its journey has that occurrence in the Agenda with
    /// the same departure and reminder, conflicts when it differs, and is unlinked otherwise.
    public static func preview(_ events: [PlannedJourneyCalendarEvent], against journeys: [PlannedJourney]) -> PlannedJourneyIcsPreview {
        var matching: [PlannedJourneyCalendarEvent] = []
        var conflicting: [PlannedJourneyCalendarEvent] = []
        var unlinked: [PlannedJourneyCalendarEvent] = []
        for event in events {
            guard let journey = journeys.first(where: { $0.id == event.journeyID }),
                  let occurrence = journey.occurrences.first(where: { $0.date == event.occurrenceDate })
            else {
                unlinked.append(event)
                continue
            }
            if occurrence.instant == event.startsAt, journey.reminderMinutes == event.reminderMinutes {
                matching.append(event)
            } else {
                conflicting.append(event)
            }
        }
        return PlannedJourneyIcsPreview(matching: matching, conflicting: conflicting, unlinked: unlinked)
    }

    /// Android's UID: the first 16 bytes of SHA-256("id|yyyy-MM-dd") in hex, "@binario.local".
    public static func uid(journeyID: String, occurrenceDate: PlannedLocalDate) -> String {
        let digest = BundledGTFSScheduleInstaller.sha256Hex(Data("\(journeyID)|\(occurrenceDate.iso)".utf8))
        return String(digest.prefix(32)) + "@binario.local"
    }

    // MARK: Writing

    private static func eventLines(
        _ journey: PlannedJourney,
        _ occurrence: PlannedJourneyOccurrence,
        generatedAt: Date,
        text: PlannedJourneyIcsText
    ) -> [String] {
        let first = occurrence.segments[0]
        let last = occurrence.segments[occurrence.segments.count - 1]
        let summary = PlannedJourneyText.trimmed(journey.label).isEmpty
            ? "\(first.origin.displayName) → \(last.destination.displayName)"
            : journey.label
        var lines = [
            "BEGIN:VEVENT",
            "UID:" + uid(journeyID: journey.id, occurrenceDate: occurrence.date),
            "DTSTAMP:" + utc(generatedAt),
            "DTSTART:" + utc(occurrence.instant),
            "SUMMARY:" + escape(summary),
            "DESCRIPTION:" + escape(occurrence.segments.map(text.describe).joined(separator: "\n")),
            "X-BINARIO-JOURNEY-ID:" + escape(journey.id),
            "X-BINARIO-OCCURRENCE-DATE:" + occurrence.date.iso,
        ]
        if let minutes = journey.reminderMinutes {
            lines += ["BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT\(minutes)M",
                      "DESCRIPTION:" + escape(text.reminderDescription), "END:VALARM"]
        }
        lines.append("END:VEVENT")
        return lines
    }

    /// "20260712T213000Z".
    static func utc(_ date: Date) -> String {
        let interval: Double = date.timeIntervalSince1970.rounded(.down)
        let seconds: Int = interval.isFinite && abs(interval) < 300_000_000_000 ? Int(interval) : 0
        let days = ItalianWallClock.floorDivision(seconds, 86_400)
        let secondOfDay: Int = seconds - days * 86_400
        let civil = ItalianWallClock.civil(fromDays: days)
        return LiveText.padded(civil.year, width: 4) + LiveText.padded(civil.month, width: 2) + LiveText.padded(civil.day, width: 2)
            + "T" + LiveText.padded(secondOfDay / 3_600, width: 2) + LiveText.padded((secondOfDay % 3_600) / 60, width: 2)
            + LiveText.padded(secondOfDay % 60, width: 2) + "Z"
    }

    /// RFC 5545 TEXT: backslash, semicolon and comma escaped, every line break as "\n".
    static func escape(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: ";", with: "\\;")
            .replacingOccurrences(of: ",", with: "\\,")
    }

    static func unescape(_ value: String) -> String {
        var result = String.UnicodeScalarView()
        var escaped = false
        for scalar in value.unicodeScalars {
            if !escaped, scalar == "\\" {
                escaped = true
            } else if escaped {
                result.append(scalar == "n" || scalar == "N" ? "\n" : scalar)
                escaped = false
            } else {
                result.append(scalar)
            }
        }
        if escaped { result.append("\\") }
        return String(result)
    }

    /// A logical line in physical lines of at most 75 octets, never inside a UTF-8 character;
    /// each continuation starts with a space.
    static func fold(_ line: String) -> [String] {
        var physical: [String] = []
        var current = String.UnicodeScalarView()
        var octets = 0
        for scalar in line.unicodeScalars {
            let size = String(scalar).utf8.count
            if octets + size > PlannedJourneyIcsContract.maximumLineOctets {
                physical.append(String(current))
                current = String.UnicodeScalarView()
                current.append(" ")
                current.append(scalar)
                octets = 1 + size
            } else {
                current.append(scalar)
                octets += size
            }
        }
        physical.append(String(current))
        return physical
    }

    // MARK: Reading

    /// The logical lines, nil when a physical line is longer than 75 octets or a continuation has
    /// nothing to continue.
    static func unfold(_ calendar: String) -> [String]? {
        let physical = calendar
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
        var logical: [String] = []
        for line in physical where !line.isEmpty {
            guard line.utf8.count <= PlannedJourneyIcsContract.maximumLineOctets else { return nil }
            let scalars = line.unicodeScalars
            if let first = scalars.first, first == " " || first == "\t" {
                guard let last = logical.popLast() else { return nil }
                logical.append(last + String(scalars.dropFirst()))
            } else {
                logical.append(line)
            }
        }
        return logical
    }

    private static func event(_ lines: [String]) -> Result<PlannedJourneyCalendarEvent, PlannedJourneyIcsError> {
        // The event's own properties: an alarm's DESCRIPTION is not the event's.
        var own: [String] = []
        var inAlarm = false
        var trigger: String?
        for line in lines {
            if line == "BEGIN:VALARM" { inAlarm = true; continue }
            if line == "END:VALARM" { inAlarm = false; continue }
            if inAlarm {
                if line.hasPrefix("TRIGGER:") { trigger = String(line.dropFirst("TRIGGER:".count)) }
            } else {
                own.append(line)
            }
        }
        guard let uid = value("UID", in: own),
              let journeyID = value("X-BINARIO-JOURNEY-ID", in: own).map(unescape),
              let occurrence = value("X-BINARIO-OCCURRENCE-DATE", in: own).flatMap(PlannedLocalDate.init(iso:)),
              let summary = value("SUMMARY", in: own).map(unescape),
              let startLine = own.first(where: { property($0)?.name == "DTSTART" })
        else { return .failure(.malformed) }
        let startsAt: Date
        switch start(startLine) {
        case let .success(instant): startsAt = instant
        case let .failure(error): return .failure(error)
        }
        guard uid == Self.uid(journeyID: journeyID, occurrenceDate: occurrence) else { return .failure(.notBinario) }
        var reminder: Int?
        if let trigger, trigger.hasPrefix("-PT"), trigger.hasSuffix("M") {
            reminder = Int(trigger.dropFirst(3).dropLast())
        }
        return .success(PlannedJourneyCalendarEvent(
            uid: uid, journeyID: journeyID, occurrenceDate: occurrence, startsAt: startsAt, summary: summary, reminderMinutes: reminder
        ))
    }

    private static func value(_ name: String, in lines: [String]) -> String? {
        lines.first { $0.hasPrefix(name + ":") }.map { String($0.dropFirst(name.count + 1)) }
    }

    /// A property line: its upper-case name, its parameters (quoted values unquoted) and its value.
    /// A colon or semicolon inside double quotes belongs to the parameter.
    static func property(_ line: String) -> (name: String, parameters: [String: String], value: String)? {
        var parts: [String] = []
        var current = String.UnicodeScalarView()
        var quoted = false
        var valueStart: String.UnicodeScalarView.Index?
        let scalars = line.unicodeScalars
        var index = scalars.startIndex
        while index < scalars.endIndex {
            let scalar = scalars[index]
            if scalar == "\"" {
                quoted.toggle()
                current.append(scalar)
            } else if !quoted, scalar == ";" {
                parts.append(String(current))
                current = String.UnicodeScalarView()
            } else if !quoted, scalar == ":" {
                parts.append(String(current))
                valueStart = scalars.index(after: index)
                break
            } else {
                current.append(scalar)
            }
            index = scalars.index(after: index)
        }
        guard let valueStart, let name = parts.first, !name.isEmpty else { return nil }
        var parameters: [String: String] = [:]
        for parameter in parts.dropFirst() {
            guard let equals = parameter.firstIndex(of: "=") else { return nil }
            var value = String(parameter[parameter.index(after: equals)...])
            if value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") {
                value = String(value.dropFirst().dropLast())
            }
            parameters[parameter[..<equals].uppercased()] = value
        }
        return (name.uppercased(), parameters, String(scalars[valueStart...]))
    }

    /// The instant of a `DTSTART` line: UTC ("…Z"), a local time in an IANA `TZID` (quoted or
    /// not), or a floating time, read as Italian time. A date without a time, an unknown zone or
    /// a skipped local time is refused.
    static func start(_ line: String) -> Result<Date, PlannedJourneyIcsError> {
        guard let parsed = property(line), parsed.name == "DTSTART" else { return .failure(.malformed) }
        if parsed.parameters["VALUE"]?.uppercased() == "DATE" { return .failure(.allDay) }
        let bytes = Array(parsed.value.utf8)
        guard bytes.count == 15 || bytes.count == 16, bytes[8] == UInt8(ascii: "T"),
              let year = ItalianWallClock.decimal(bytes[0..<4]),
              let month = ItalianWallClock.decimal(bytes[4..<6]),
              let day = ItalianWallClock.decimal(bytes[6..<8]),
              let hour = ItalianWallClock.decimal(bytes[9..<11]),
              let minute = ItalianWallClock.decimal(bytes[11..<13]),
              let second = ItalianWallClock.decimal(bytes[13..<15]), second < 60,
              let date = PlannedLocalDate(year: year, month: month, day: day),
              let time = PlannedLocalTime(hour: hour, minute: minute)
        else { return .failure(bytes.count == 8 ? .allDay : .malformed) }
        let isUTC = bytes.count == 16
        guard !isUTC || bytes[15] == UInt8(ascii: "Z") else { return .failure(.malformed) }
        let zone: TimeZone
        if isUTC {
            guard parsed.parameters["TZID"] == nil, let utc = TimeZone(secondsFromGMT: 0) else { return .failure(.malformed) }
            zone = utc
        } else if let identifier = parsed.parameters["TZID"] {
            guard let named = TimeZone(identifier: identifier) else { return .failure(.unknownTimeZone) }
            zone = named
        } else {
            zone = ItalianWallClock.timeZone
        }
        guard let instant = ItalianWallClock.instant(date: date, time: time, zone: zone) else {
            return .failure(.nonexistentLocalTime)
        }
        return .success(instant.addingTimeInterval(TimeInterval(second)))
    }
}
