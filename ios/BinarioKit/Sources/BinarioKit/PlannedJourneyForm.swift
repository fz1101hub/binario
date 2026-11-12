import Foundation

/// Why the Agenda editor cannot save what it holds; nothing is written.
public enum PlannedJourneyFormIssue: Error, Equatable, Sendable {
    case missingStations
    case sameStation
    /// No date or time chosen, or a time the daylight-saving change skips.
    case invalidDeparture
    case labelTooLong
    case checklistLineTooLong
    case sensitiveChecklist
    case invalid
}

/// What the Agenda editor holds, and the journey it saves: two catalogue stations, an Italian date
/// and time, an optional label and a checklist typed one line per item (Android's editor).
public struct PlannedJourneyForm: Equatable, Sendable {
    public var label: String
    public var origin: StationIdentity?
    public var destination: StationIdentity?
    /// Italian date and time; nil until chosen.
    public var date: PlannedLocalDate?
    public var time: PlannedLocalTime?
    public var checklistText: String
    /// Minutes before departure of the local reminder; nil is «Nessuno».
    public var reminderMinutes: Int?

    public init(
        label: String = "",
        origin: StationIdentity? = nil,
        destination: StationIdentity? = nil,
        date: PlannedLocalDate? = nil,
        time: PlannedLocalTime? = nil,
        checklistText: String = "",
        reminderMinutes: Int? = nil
    ) {
        self.label = label
        self.origin = origin
        self.destination = destination
        self.date = date
        self.time = time
        self.checklistText = checklistText
        self.reminderMinutes = reminderMinutes
    }

    /// The leads the editor offers: «Nessuno» first, then Android's values.
    public static let reminderChoices: [Int?] = [nil] + PlannedJourneySchema.reminderMinutes.sorted().map { Optional($0) }

    /// A new journey: the next whole hour in Italy (today, or tomorrow after 23:00).
    public static func new(now: Date) -> PlannedJourneyForm {
        let inAnHour = now.addingTimeInterval(3_600)
        return PlannedJourneyForm(
            date: ItalianWallClock.date(of: inAnHour),
            time: ItalianWallClock.time(of: inAnHour).flatMap { PlannedLocalTime(hour: $0.hour, minute: 0) }
        )
    }

    /// The editor on a saved journey: its stored values, the checklist one label per line.
    public init(editing journey: PlannedJourney) {
        self.init(
            label: journey.label,
            origin: journey.origin.identity,
            destination: journey.destination.identity,
            date: journey.departure.date,
            time: journey.departure.time,
            checklistText: journey.checklist.map(\.label).joined(separator: "\n"),
            reminderMinutes: journey.reminderMinutes
        )
    }

    /// «Ritorno»: a new journey with the stations swapped. Date and time are chosen anew, the
    /// label is empty, the checklist lines come back unticked and no reminder is inherited
    /// (Android's `PlannedJourneyDrafts.returnFrom` and its editor).
    public static func returnTrip(from journey: PlannedJourney) -> PlannedJourneyForm {
        PlannedJourneyForm(
            origin: journey.destination.identity,
            destination: journey.origin.identity,
            checklistText: journey.checklist.map(\.label).joined(separator: "\n")
        )
    }

    /// «Ripianifica»: a new journey on the same stations, with the same rules as `returnTrip`.
    public static func replan(from journey: PlannedJourney) -> PlannedJourneyForm {
        PlannedJourneyForm(
            origin: journey.origin.identity,
            destination: journey.destination.identity,
            checklistText: journey.checklist.map(\.label).joined(separator: "\n")
        )
    }

    /// The departure chosen, as an instant on the Italian clock; nil when not chosen or skipped.
    public var departureInstant: Date? {
        guard let date, let time else { return nil }
        return ItalianWallClock.instant(date: date, time: time)
    }

    /// Whether saving must first warn that the departure has already passed at `now` (the journey
    /// would go straight to the archive): a new journey, or an edit that moves the departure. An
    /// edit that keeps a past departure does not warn again (Android's rule).
    public func needsPastDepartureWarning(now: Date, editing existing: PlannedJourney?) -> Bool {
        guard let departure = departureInstant, departure < now else { return false }
        guard let existing else { return true }
        return existing.departure.date != date || existing.departure.time != time
            || existing.departure.timeZone != ItalianWallClock.zoneIdentifier
    }

    /// The checklist lines: trimmed, without empty ones, at most
    /// `PlannedJourneySchema.maximumChecklistItems` (Android takes the first 24 the same way).
    public var checklistLabels: [String] {
        let lines = checklistText.split(whereSeparator: \.isNewline).map { PlannedJourneyText.trimmed(String($0)) }
        return Array(lines.filter { !$0.isEmpty }.prefix(PlannedJourneySchema.maximumChecklistItems))
    }

    /// The journey to save: `existing` with the editor's values, or a new journey identified by
    /// `id` and created at `now`. Every journey the iPhone writes is in Italian time. A line kept
    /// with the same text keeps its tick; the reminder is the one chosen in the editor.
    public func journey(id: String, now: Date, editing existing: PlannedJourney?) -> Result<PlannedJourney, PlannedJourneyFormIssue> {
        guard let origin, let destination else { return .failure(.missingStations) }
        guard let date, let time else { return .failure(.invalidDeparture) }
        let label = PlannedJourneyText.trimmed(self.label)
        guard PlannedJourneyText.length(label) <= PlannedJourneySchema.maximumLabelLength else { return .failure(.labelTooLong) }
        if let reminderMinutes, !PlannedJourneySchema.reminderMinutes.contains(reminderMinutes) { return .failure(.invalid) }
        do {
            let segment = try PlannedJourneySegment(
                id: existing?.segment.id ?? PlannedJourneySegment.firstIdentifier,
                origin: PlannedJourneyStation(origin),
                destination: PlannedJourneyStation(destination),
                departure: PlannedJourneyDateTime(date: date, time: time)
            )
            let checklist = try checklistItems(keepingTicksOf: existing?.checklist ?? [])
            guard let existing else {
                return .success(try PlannedJourney(
                    id: id, label: label, segment: segment, reminderMinutes: reminderMinutes, checklist: checklist, createdAt: now
                ))
            }
            return .success(try PlannedJourney(
                id: existing.id, label: label, segment: segment, reminderMinutes: reminderMinutes,
                checklist: checklist, status: existing.status, createdAt: existing.createdAt,
                completedAt: existing.completedAt, archivedAt: existing.archivedAt
            ))
        } catch let error as PlannedJourneyValidationError {
            return .failure(Self.issue(for: error))
        } catch {
            return .failure(.invalid)
        }
    }

    private func checklistItems(keepingTicksOf previous: [PlannedJourneyChecklistItem]) throws -> [PlannedJourneyChecklistItem] {
        var ticked = previous.filter(\.completed).map(\.label)
        return try checklistLabels.enumerated().map { index, label in
            let completed: Bool
            if let match = ticked.firstIndex(of: label) {
                ticked.remove(at: match)
                completed = true
            } else {
                completed = false
            }
            return try PlannedJourneyChecklistItem(id: "check-\(index + 1)", label: label, completed: completed)
        }
    }

    private static func issue(for error: PlannedJourneyValidationError) -> PlannedJourneyFormIssue {
        switch error {
        case .sameStation: return .sameStation
        case .invalidStation: return .missingStations
        case .nonexistentLocalTime, .invalidDeparture: return .invalidDeparture
        case .invalidLabel: return .labelTooLong
        case .invalidChecklist: return .checklistLineTooLong
        case .sensitiveChecklistContent: return .sensitiveChecklist
        default: return .invalid
        }
    }
}

/// How VoiceOver says an Agenda message (the companion's rule): every message is announced
/// explicitly, since one shown in a form or after a sheet closes is not read otherwise; an error
/// interrupts what is being said (assertive), information waits its turn (polite).
public enum PlannedJourneyFeedbackSeverity: Equatable, Sendable {
    case information
    case error

    /// Whether the announcement waits for the current speech to end.
    public var queuesBehindCurrentSpeech: Bool {
        self == .information
    }
}
