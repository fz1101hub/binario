import Foundation

/// A calendar month of the Agenda's month filter.
public struct PlannedJourneyMonth: Hashable, Comparable, Sendable {
    public let year: Int
    public let month: Int

    public init(_ date: PlannedLocalDate) {
        year = date.year
        month = date.month
    }

    /// The first day of the month.
    public var firstDay: PlannedLocalDate? {
        PlannedLocalDate(year: year, month: month, day: 1)
    }

    public static func < (lhs: PlannedJourneyMonth, rhs: PlannedJourneyMonth) -> Bool {
        (lhs.year, lhs.month) < (rhs.year, rhs.month)
    }
}

/// The Agenda's search and month filter (Android's `PlannedJourneyAgendaSearch`), applied to the
/// journeys of the list chosen, each at the departure the list shows; nothing leaves the iPhone.
public enum PlannedJourneyAgendaSearch {
    /// Every word of `query` appears in the label, a station name or code, the date (yyyy-MM-dd
    /// or dd/MM/yyyy) or the time of a leg, case and accents ignored; an empty query matches all.
    public static func matches(_ journey: PlannedJourney, now: Date, query: String) -> Bool {
        let terms = fold(query).split(separator: " ").map(String.init).filter { !$0.isEmpty }
        guard !terms.isEmpty else { return true }
        var fields = [journey.label]
        for segment in journey.shownOccurrence(now: now).segments {
            fields.append(segment.origin.displayName)
            fields.append(segment.destination.displayName)
            if let code = segment.origin.railwayCode { fields.append(code) }
            if let code = segment.destination.railwayCode { fields.append(code) }
            let date = segment.departure.date
            fields.append(date.iso)
            fields.append(LiveText.padded(date.day, width: 2) + "/" + LiveText.padded(date.month, width: 2) + "/"
                + LiveText.padded(date.year, width: 4))
            fields.append(segment.departure.time.hhmm)
        }
        let haystack = fold(fields.joined(separator: " "))
        return terms.allSatisfy { haystack.contains($0) }
    }

    /// The months of the journeys' shown departures, in the list's order, each once: the choices.
    public static func months(_ journeys: [PlannedJourney], now: Date) -> [PlannedJourneyMonth] {
        var seen = Set<PlannedJourneyMonth>()
        return journeys.map { PlannedJourneyMonth($0.shownOccurrence(now: now).date) }.filter { seen.insert($0).inserted }
    }

    /// The journeys of `month` (nil for all) that match `query`, in their order.
    public static func filter(
        _ journeys: [PlannedJourney],
        now: Date,
        query: String,
        month: PlannedJourneyMonth?
    ) -> [PlannedJourney] {
        journeys.filter { journey in
            (month == nil || PlannedJourneyMonth(journey.shownOccurrence(now: now).date) == month)
                && matches(journey, now: now, query: query)
        }
    }

    /// Android's `fold`: accents removed, lower case, every run of characters other than letters,
    /// digits, ":", "/" and "-" as one space.
    static func fold(_ value: String) -> String {
        var text = replacing("\\p{M}+", in: value.decomposedStringWithCanonicalMapping, with: "")
        text = text.lowercased()
        text = replacing("[^\\p{L}\\p{N}:/-]+", in: text, with: " ")
        return PlannedJourneyText.trimmed(text)
    }

    private static func replacing(_ pattern: String, in text: String, with template: String) -> String {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return text }
        return expression.stringByReplacingMatches(
            in: text, options: [], range: NSRange(text.startIndex..., in: text), withTemplate: template
        )
    }
}

/// «Tra 1 h 10 min»: the time left before a departure, rounded up to the minute, never negative
/// (Android's `PlannedJourneyCountdown`).
public struct PlannedJourneyCountdown: Equatable, Sendable {
    public let hours: Int
    public let minutes: Int

    public init(hours: Int, minutes: Int) {
        self.hours = hours
        self.minutes = minutes
    }

    public static func between(now: Date, departure: Date) -> PlannedJourneyCountdown {
        let seconds: Double = max(0, departure.timeIntervalSince(now))
        let total = seconds.isFinite ? Int((seconds / 60).rounded(.up)) : 0
        return PlannedJourneyCountdown(hours: total / 60, minutes: total % 60)
    }
}
