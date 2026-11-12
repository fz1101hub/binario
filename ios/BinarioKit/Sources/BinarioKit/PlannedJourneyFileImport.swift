import Foundation

/// Android's conflict policy for a journey of the file whose identifier is already in the Agenda
/// with another itinerary or schedule.
public enum PlannedJourneyConflictPolicy: Equatable, Sendable {
    /// «Mantieni esistenti»: only the new journeys are added.
    case keepExisting
    /// «Sostituisci conflitti»: the file's version replaces the Agenda's, in its place.
    case replaceExisting
}

public struct PlannedJourneyImportConflict: Equatable, Sendable {
    public let existing: PlannedJourney
    public let imported: PlannedJourney
}

/// What a JSON file would change (Android's `PlannedJourneyImportPreview`); nothing is written
/// until the user chooses.
public struct PlannedJourneyImportPreview: Equatable, Sendable {
    /// New journeys, added after the Agenda's.
    public let accepted: [PlannedJourney]
    /// Already in the Agenda with the same stations, date and time: ignored.
    public let duplicates: [PlannedJourney]
    /// The same identifier with another itinerary or schedule.
    public let conflicts: [PlannedJourneyImportConflict]
    /// Records of the file this iPhone cannot read (several legs, a repetition, invalid data),
    /// left out.
    public let unreadableRecords: Int

    /// Whether choosing «Importa» can change anything.
    public var hasChanges: Bool {
        !accepted.isEmpty || !conflicts.isEmpty
    }
}

/// A file picked in «Importa», read before anything is written.
public enum PlannedJourneyFilePreview: Equatable, Sendable {
    /// An Agenda export (Android's transfer document).
    case journeys(PlannedJourneyImportPreview)
    /// A calendar Binario wrote: a check against the Agenda only.
    case calendar(PlannedJourneyIcsPreview)
}

/// Android's import rules (`PlannedJourneyTransfer.preview` and `merge`) for the iPhone.
public enum PlannedJourneyFileImport {
    /// The file's preview against the Agenda's journeys; nil when it is neither an Agenda export
    /// nor a calendar Binario wrote, or is larger than the transfer limit.
    public static func preview(_ data: Data, existing: [PlannedJourney]) -> PlannedJourneyFilePreview? {
        guard !data.isEmpty, data.count <= PlannedJourneySchema.maximumEncodedBytes else { return nil }
        if let text = String(data: data, encoding: .utf8),
           text.drop(while: { $0.isWhitespace || $0 == "\u{FEFF}" }).hasPrefix("BEGIN:VCALENDAR") {
            guard case let .success(events) = PlannedJourneyIcs.parseOwnedCalendar(text) else { return nil }
            return .calendar(PlannedJourneyIcs.preview(events, against: existing))
        }
        if let collection = PlannedJourneyTransfer.decode(data) {
            return .journeys(preview(collection.journeys, unreadableRecords: 0, existing: existing))
        }
        // A document of the right kind and version, with journeys the iPhone does not keep: the
        // readable ones are offered and the others counted.
        guard let recovered = PlannedJourneyTransfer.recover(data) else { return nil }
        return .journeys(preview(recovered.journeys, unreadableRecords: recovered.unreadableRecords, existing: existing))
    }

    /// Android's `preview`: a journey already in the Agenda (same fingerprint) is a duplicate, one
    /// with an identifier already used is a conflict, the others are new.
    public static func preview(
        _ imported: [PlannedJourney],
        unreadableRecords: Int,
        existing: [PlannedJourney]
    ) -> PlannedJourneyImportPreview {
        let fingerprints = Set(existing.map(\.fingerprint))
        var accepted: [PlannedJourney] = []
        var duplicates: [PlannedJourney] = []
        var conflicts: [PlannedJourneyImportConflict] = []
        for journey in imported {
            if fingerprints.contains(journey.fingerprint) {
                duplicates.append(journey)
            } else if let current = existing.first(where: { $0.id == journey.id }) {
                conflicts.append(PlannedJourneyImportConflict(existing: current, imported: journey))
            } else {
                accepted.append(journey)
            }
        }
        return PlannedJourneyImportPreview(
            accepted: accepted, duplicates: duplicates, conflicts: conflicts, unreadableRecords: unreadableRecords
        )
    }

    /// Android's `merge`: the Agenda in its order, the new journeys after it, conflicts replaced in
    /// place when asked; then one journey per itinerary and schedule (the first kept) and at most
    /// the limit.
    public static func merge(
        existing: [PlannedJourney],
        preview: PlannedJourneyImportPreview,
        policy: PlannedJourneyConflictPolicy
    ) -> [PlannedJourney] {
        var merged = existing
        func put(_ journey: PlannedJourney) {
            if let index = merged.firstIndex(where: { $0.id == journey.id }) {
                merged[index] = journey
            } else {
                merged.append(journey)
            }
        }
        preview.accepted.forEach(put)
        if policy == .replaceExisting {
            preview.conflicts.map(\.imported).forEach(put)
        }
        var seen = Set<String>()
        let unique = merged.filter { seen.insert($0.fingerprint).inserted }
        return Array(unique.prefix(PlannedJourneySchema.maximumJourneys))
    }
}

extension PlannedJourneyStore {
    /// Android's `applyJsonPreview`: refused when the Agenda is read-only or the new journeys do
    /// not fit in it; nothing is written then.
    public func applyImport(
        _ preview: PlannedJourneyImportPreview,
        policy: PlannedJourneyConflictPolicy
    ) -> Result<PlannedJourneyAgendaState, PlannedJourneyRefusal> {
        let current = load()
        guard !current.isReadOnly else { return .failure(.readOnly) }
        guard current.journeys.count + preview.accepted.count <= PlannedJourneySchema.maximumJourneys else {
            return .failure(.full)
        }
        return replaceAll(PlannedJourneyFileImport.merge(existing: current.journeys, preview: preview, policy: policy))
    }
}
