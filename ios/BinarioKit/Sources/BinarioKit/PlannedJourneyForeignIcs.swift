import Foundation

/// The journeys a calendar of another app proposes: one draft per event that can be one, and how
/// many events could not.
public struct PlannedJourneyForeignCalendar: Equatable, Sendable {
    public let drafts: [PlannedJourneyTextDraft]
    public let skippedEvents: Int
}

/// Calendar files of other apps (Android's `PlannedJourneyForeignIcs`; Binario's own export keeps
/// its check against the Agenda): each timed event whose SUMMARY is a route «A → B» becomes a
/// proposed journey at its Italian departure time. A UTC time and a known IANA `TZID` (quoted or
/// not, a colon inside quotes allowed) are converted to Italian time; a floating time is read as
/// Italian; an all-day event, an unknown `TZID` (Windows names included) or a time that does not
/// exist in its zone is skipped, never guessed. Of a repeating event only its `DTSTART` is read.
/// The file is bounded like an export, and at most 120 events are read; the rest count as skipped.
public enum PlannedJourneyForeignIcs {
    /// A calendar envelope without Binario's own product identifier.
    public static func isForeign(_ calendar: String) -> Bool {
        let lines = unfold(calendar)
        return lines.first?.uppercased() == "BEGIN:VCALENDAR"
            && !lines.contains("PRODID:" + PlannedJourneyIcs.escape(PlannedJourneyIcsContract.productID))
    }

    /// Nil when the file is too large or is not a calendar.
    public static func read(_ calendar: String) -> PlannedJourneyForeignCalendar? {
        guard calendar.utf8.count <= PlannedJourneyIcsContract.maximumBytes else { return nil }
        let lines = unfold(calendar)
        guard lines.first?.uppercased() == "BEGIN:VCALENDAR" else { return nil }
        var drafts: [PlannedJourneyTextDraft] = []
        var skipped = 0
        var events = 0
        var current: [String]?
        for line in lines {
            switch line.uppercased() {
            case "BEGIN:VEVENT":
                current = []
            case "END:VEVENT":
                guard let event = current else { continue }
                current = nil
                events += 1
                if events <= PlannedJourneyIcsContract.maximumEvents, let draft = draft(event) {
                    drafts.append(draft)
                } else {
                    skipped += 1
                }
            default:
                current?.append(line)
            }
        }
        return PlannedJourneyForeignCalendar(drafts: drafts, skippedEvents: skipped)
    }

    private static func draft(_ lines: [String]) -> PlannedJourneyTextDraft? {
        let properties = lines.compactMap(PlannedJourneyIcs.property)
        guard let summary = properties.first(where: { $0.name == "SUMMARY" }).map({ unescape($0.value) }),
              let startLine = lines.first(where: { PlannedJourneyIcs.property($0)?.name == "DTSTART" }),
              let start = properties.first(where: { $0.name == "DTSTART" })
        else { return nil }
        if let type = start.parameters["VALUE"], type.uppercased() != "DATE-TIME" { return nil }
        guard case let .success(instant) = PlannedJourneyIcs.start(startLine),
              let date = ItalianWallClock.date(of: instant), let time = ItalianWallClock.time(of: instant),
              let route = PlannedJourneyTextImport.route(summary)
        else { return nil }
        return PlannedJourneyTextDraft(origin: route.origin, destination: route.destination, date: date, time: time)
    }

    /// Folded lines joined (a leading space or tab continues the line before), any length accepted.
    static func unfold(_ calendar: String) -> [String] {
        var logical: [String] = []
        let physical = calendar
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
        for line in physical {
            let scalars = line.unicodeScalars
            if let first = scalars.first, first == " " || first == "\t", let last = logical.popLast() {
                logical.append(last + String(scalars.dropFirst()))
            } else if !line.isEmpty {
                logical.append(line)
            }
        }
        return logical
    }

    /// Android's foreign `unescape`: an escaped line break is a space, the result trimmed.
    static func unescape(_ value: String) -> String {
        var result = String.UnicodeScalarView()
        var escaped = false
        for scalar in value.unicodeScalars {
            if escaped {
                result.append(scalar == "n" || scalar == "N" ? " " : scalar)
                escaped = false
            } else if scalar == "\\" {
                escaped = true
            } else {
                result.append(scalar)
            }
        }
        return PlannedJourneyText.trimmed(String(result))
    }
}
