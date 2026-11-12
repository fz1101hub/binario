import Foundation

/// Why the Agenda editor cannot save what it holds; nothing is written.
public enum PlannedJourneyFormIssue: Error, Equatable, Sendable {
    case missingStations
    case sameStation
    /// No date or time chosen, or a time the daylight-saving change skips.
    case invalidDeparture
    /// A leg does not start where the leg before it arrives.
    case disconnectedLegs
    /// A leg does not depart after the leg before it.
    case nonChronologicalLegs
    case labelTooLong
    case checklistLineTooLong
    case sensitiveChecklist
    case invalid
}

/// A leg of the editor (Android's `PlannedJourneyEditorLeg`): two catalogue stations and an
/// Italian date and time, nil until chosen.
public struct PlannedJourneyFormLeg: Equatable, Sendable {
    public var origin: StationIdentity?
    public var destination: StationIdentity?
    public var date: PlannedLocalDate?
    public var time: PlannedLocalTime?
    /// The stored leg's identifier while a journey is edited; nil for a new leg.
    public var segmentID: String?

    public init(
        origin: StationIdentity? = nil,
        destination: StationIdentity? = nil,
        date: PlannedLocalDate? = nil,
        time: PlannedLocalTime? = nil,
        segmentID: String? = nil
    ) {
        self.origin = origin
        self.destination = destination
        self.date = date
        self.time = time
        self.segmentID = segmentID
    }

    /// A stored leg in Italian time: the same instant, whatever zone it was written in.
    init(_ segment: PlannedJourneySegment) {
        self.init(
            origin: segment.origin.identity,
            destination: segment.destination.identity,
            date: ItalianWallClock.date(of: segment.departure.instant),
            time: ItalianWallClock.time(of: segment.departure.instant),
            segmentID: segment.id
        )
    }

    /// The departure chosen, as an instant on the Italian clock; nil when not chosen or skipped.
    public var departureInstant: Date? {
        guard let date, let time else { return nil }
        return ItalianWallClock.instant(date: date, time: time)
    }
}

/// What the Agenda editor holds, and the journey it saves: one to four legs between catalogue
/// stations at Italian dates and times (the first in `origin`, `destination`, `date` and `time`,
/// the others in `laterLegs`), an optional label and a checklist typed one line per item
/// (Android's editor).
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
    /// The first stored leg's identifier while a journey is edited.
    public var firstSegmentID: String?
    /// Legs 2 to 4, in order; empty for a one-leg journey.
    public var laterLegs: [PlannedJourneyFormLeg]
    /// How many journeys the repetition makes in all; 1 is a journey that departs once.
    public var repeatCount: Int
    /// The weekdays it repeats on besides the first date's, which is always one of them.
    public var repeatWeekdays: Set<PlannedJourneyWeekday>
    /// A stored repetition's last day, kept while it does not precede the first departure.
    public var repeatEndsOn: PlannedLocalDate?

    public init(
        label: String = "",
        origin: StationIdentity? = nil,
        destination: StationIdentity? = nil,
        date: PlannedLocalDate? = nil,
        time: PlannedLocalTime? = nil,
        checklistText: String = "",
        reminderMinutes: Int? = nil,
        firstSegmentID: String? = nil,
        laterLegs: [PlannedJourneyFormLeg] = [],
        repeatCount: Int = 1,
        repeatWeekdays: Set<PlannedJourneyWeekday> = [],
        repeatEndsOn: PlannedLocalDate? = nil
    ) {
        self.label = label
        self.origin = origin
        self.destination = destination
        self.date = date
        self.time = time
        self.checklistText = checklistText
        self.reminderMinutes = reminderMinutes
        self.firstSegmentID = firstSegmentID
        self.laterLegs = laterLegs
        self.repeatCount = repeatCount
        self.repeatWeekdays = repeatWeekdays
        self.repeatEndsOn = repeatEndsOn
    }

    /// The counts the editor offers (Android's): once, 2, 4, 8 or 12 journeys in all, and the
    /// count of a stored repetition.
    public static func repeatCounts(including current: Int) -> [Int] {
        let choices = [1, 2, 4, 8, PlannedJourneySchema.maximumOccurrences]
        let all = (1...PlannedJourneySchema.maximumOccurrences).contains(current) ? choices + [current] : choices
        return Array(Set(all)).sorted()
    }

    /// Android's editor rule: from the first departure's date, on its weekday and the others
    /// chosen, `repeatCount` journeys in all (at most twelve); nil for a journey that departs once.
    public func recurrence(startingOn date: PlannedLocalDate) throws -> PlannedJourneyRecurrence? {
        guard repeatCount > 1 else { return nil }
        var weekdays = repeatWeekdays
        weekdays.insert(PlannedJourneyWeekday(of: date))
        return try PlannedJourneyRecurrence(
            weekdays: weekdays.sorted(),
            startsOn: date,
            endsOn: repeatEndsOn.flatMap { $0 < date ? nil : $0 },
            occurrenceLimit: min(repeatCount, PlannedJourneySchema.maximumOccurrences)
        )
    }

    /// Every leg, the first one first.
    public var legs: [PlannedJourneyFormLeg] {
        get {
            [PlannedJourneyFormLeg(origin: origin, destination: destination, date: date, time: time, segmentID: firstSegmentID)]
                + laterLegs
        }
        set {
            guard let first = newValue.first else { return }
            origin = first.origin
            destination = first.destination
            date = first.date
            time = first.time
            firstSegmentID = first.segmentID
            laterLegs = Array(newValue.dropFirst())
        }
    }

    /// Whether «Aggiungi tratta» may add one (four legs at most).
    public var canAddLeg: Bool {
        laterLegs.count + 1 < PlannedJourneySchema.maximumSegments
    }

    /// «Aggiungi tratta»: the new leg starts where the last one arrives, on its date; its time is
    /// left to choose (Android's `addLeg`).
    public mutating func addLeg() {
        guard canAddLeg, let last = legs.last else { return }
        laterLegs.append(PlannedJourneyFormLeg(origin: last.destination, date: last.date))
    }

    /// «Rimuovi tratta»: the only leg of a journey stays.
    public mutating func removeLeg(at index: Int) {
        var all = legs
        guard all.count > 1, all.indices.contains(index) else { return }
        all.remove(at: index)
        legs = all
    }

    /// Leg `index` changed to `leg`. The next leg's origin follows a changed destination while it
    /// still showed that destination (it was prefilled from it), and the following legs with no date
    /// take a newly chosen date (Android's `withLeg`).
    public mutating func setLeg(_ leg: PlannedJourneyFormLeg, at index: Int) {
        var all = legs
        guard all.indices.contains(index) else { return }
        let before = all[index]
        all[index] = leg
        if all.indices.contains(index + 1), all[index + 1].origin == before.destination {
            all[index + 1].origin = leg.destination
        }
        if leg.date != before.date {
            for following in all.indices where following > index {
                guard all[following].date == nil else { break }
                all[following].date = leg.date
            }
        }
        legs = all
    }

    /// The leads the editor offers: «Nessuno» first, then Android's values.
    public static let reminderChoices: [Int?] = [nil] + PlannedJourneySchema.reminderMinutes.sorted().map { Optional($0) }

    /// A new journey: the next whole hour in Italy (today, or tomorrow after 23:00), with the
    /// default lead chosen in the Agenda (Android's: none until one is chosen).
    public static func new(now: Date, reminderMinutes: Int? = nil) -> PlannedJourneyForm {
        let inAnHour = now.addingTimeInterval(3_600)
        return PlannedJourneyForm(
            date: ItalianWallClock.date(of: inAnHour),
            time: ItalianWallClock.time(of: inAnHour).flatMap { PlannedLocalTime(hour: $0.hour, minute: 0) },
            reminderMinutes: reminderMinutes
        )
    }

    /// «Salva come modello»: the one leg's stations and time, the lead and the checklist lines;
    /// nil for a journey of several legs or one not filled in (Android's rule: a template is one leg).
    public func template(id: String, name: String) -> PlannedJourneyTemplate? {
        guard laterLegs.isEmpty, let origin, let destination, let time,
              let start = try? PlannedJourneyStation(origin), let end = try? PlannedJourneyStation(destination)
        else { return nil }
        return PlannedJourneyTemplate(
            id: id, name: name, origin: start, destination: end, time: time,
            reminderMinutes: reminderMinutes, checklist: checklistLabels
        )
    }

    /// A template applied: its one leg, time, lead and checklist replace the form's; the date is the
    /// next day in Italy when that time is still to come, to be checked (the iPhone picks date and
    /// time together, where Android leaves the date blank).
    public mutating func apply(_ template: PlannedJourneyTemplate, now: Date) {
        var day = ItalianWallClock.date(of: now)
        if let today = day, let instant = ItalianWallClock.instant(date: today, time: template.time), instant <= now {
            day = today.adding(days: 1)
        }
        legs = [PlannedJourneyFormLeg(
            origin: template.origin.identity, destination: template.destination.identity, date: day, time: template.time
        )]
        reminderMinutes = template.reminderMinutes
        checklistText = template.checklist.joined(separator: "\n")
        repeatCount = 1
        repeatWeekdays = []
        repeatEndsOn = nil
    }

    /// The editor on a saved journey: every leg with its identifier (editing never drops one), in
    /// Italian time; the checklist one label per line.
    public init(editing journey: PlannedJourney) {
        self.init(
            label: journey.label,
            checklistText: journey.checklist.map(\.label).joined(separator: "\n"),
            reminderMinutes: journey.reminderMinutes,
            repeatCount: journey.recurrence?.occurrenceLimit ?? 1,
            repeatWeekdays: Set(journey.recurrence?.weekdays ?? []),
            repeatEndsOn: journey.recurrence?.endsOn
        )
        legs = journey.segments.map { PlannedJourneyFormLeg($0) }
    }

    /// «Ritorno»: a new journey back, the legs in reverse order with their stations swapped. Dates
    /// and times are chosen anew, the label is empty, the checklist lines come back unticked and the
    /// reminder is the default lead, never the source's (Android's `PlannedJourneyDrafts.returnFrom`
    /// and its editor).
    public static func returnTrip(from journey: PlannedJourney, reminderMinutes: Int? = nil) -> PlannedJourneyForm {
        var form = PlannedJourneyForm(
            checklistText: journey.checklist.map(\.label).joined(separator: "\n"), reminderMinutes: reminderMinutes
        )
        form.legs = journey.segments.reversed().map { segment in
            PlannedJourneyFormLeg(origin: segment.destination.identity, destination: segment.origin.identity)
        }
        return form
    }

    /// «Ripianifica»: a new journey on the same legs, with the same rules as `returnTrip`.
    public static func replan(from journey: PlannedJourney, reminderMinutes: Int? = nil) -> PlannedJourneyForm {
        var form = PlannedJourneyForm(
            checklistText: journey.checklist.map(\.label).joined(separator: "\n"), reminderMinutes: reminderMinutes
        )
        form.legs = journey.segments.map { segment in
            PlannedJourneyFormLeg(origin: segment.origin.identity, destination: segment.destination.identity)
        }
        return form
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
    /// `id` and created at `now`. Every leg the iPhone writes is in Italian time; a leg keeps its
    /// stored identifier, a new one takes the first free "segment-N". A line kept with the same
    /// text keeps its tick; the reminder is the one chosen in the editor.
    public func journey(id: String, now: Date, editing existing: PlannedJourney?) -> Result<PlannedJourney, PlannedJourneyFormIssue> {
        let legs = self.legs
        guard legs.allSatisfy({ $0.origin != nil && $0.destination != nil }) else { return .failure(.missingStations) }
        guard legs.allSatisfy({ $0.date != nil && $0.time != nil }) else { return .failure(.invalidDeparture) }
        let label = PlannedJourneyText.trimmed(self.label)
        guard PlannedJourneyText.length(label) <= PlannedJourneySchema.maximumLabelLength else { return .failure(.labelTooLong) }
        if let reminderMinutes, !PlannedJourneySchema.reminderMinutes.contains(reminderMinutes) { return .failure(.invalid) }
        do {
            var usedIdentifiers = Set(legs.compactMap(\.segmentID))
            let segments = try legs.map { (leg: PlannedJourneyFormLeg) throws -> PlannedJourneySegment in
                guard let origin = leg.origin, let destination = leg.destination, let date = leg.date, let time = leg.time else {
                    throw PlannedJourneyValidationError.invalidDeparture
                }
                var identifier = leg.segmentID
                if identifier == nil {
                    var number = 1
                    while usedIdentifiers.contains("segment-\(number)") {
                        number += 1
                    }
                    identifier = "segment-\(number)"
                    usedIdentifiers.insert("segment-\(number)")
                }
                return try PlannedJourneySegment(
                    id: identifier ?? PlannedJourneySegment.firstIdentifier,
                    origin: PlannedJourneyStation(origin),
                    destination: PlannedJourneyStation(destination),
                    departure: PlannedJourneyDateTime(date: date, time: time)
                )
            }
            let checklist = try checklistItems(keepingTicksOf: existing?.checklist ?? [])
            let recurrence = try self.recurrence(startingOn: segments[0].departure.date)
            guard let existing else {
                return .success(try PlannedJourney(
                    id: id, label: label, segments: segments, reminderMinutes: reminderMinutes, recurrence: recurrence,
                    checklist: checklist, createdAt: now
                ))
            }
            // A date marked done stays only while the repetition still makes it (Android's rule).
            let dates = Set(recurrence?.dates ?? [])
            return .success(existing.keepingInstantFractions(in: try PlannedJourney(
                id: existing.id, label: label, segments: segments, reminderMinutes: reminderMinutes, recurrence: recurrence,
                checklist: checklist, status: existing.status, createdAt: existing.createdAt,
                completedAt: existing.completedAt, archivedAt: existing.archivedAt,
                completedOccurrenceDates: existing.completedOccurrenceDates.filter { dates.contains($0) }
            )))
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
        case .disconnectedItinerary: return .disconnectedLegs
        case .nonChronologicalItinerary: return .nonChronologicalLegs
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
