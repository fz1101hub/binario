import Foundation

/// A ready-made checklist line. The app writes it in the user's language and stores it as typed,
/// like any other line, so the sensitive-content check still applies (Android's
/// `PlannedJourneyChecklistLine`).
public enum PlannedJourneyChecklistLine: CaseIterable, Sendable {
    case timeMargin
    case documents
    case belongings
    case connection
    case bike
    case assistance
}

/// «Preparazione del viaggio»: each preset adds the three common lines and its own, in
/// `PlannedJourneyChecklistLine` order (Android's `PlannedJourneyChecklistPreset`).
public enum PlannedJourneyChecklistPreset: CaseIterable, Sendable {
    case commuter
    case connection
    case bike
    case assistance

    public var lines: [PlannedJourneyChecklistLine] {
        let own: PlannedJourneyChecklistLine?
        switch self {
        case .commuter: own = nil
        case .connection: own = .connection
        case .bike: own = .bike
        case .assistance: own = .assistance
        }
        let common: [PlannedJourneyChecklistLine] = [.timeMargin, .documents, .belongings]
        return PlannedJourneyChecklistLine.allCases.filter { common.contains($0) || $0 == own }
    }

    /// The editor's checklist text with this preset's lines after the user's own: a line already
    /// there (same text, case ignored) is not repeated, and the list keeps to the checklist limit.
    public func applying(to text: String, label: (PlannedJourneyChecklistLine) -> String) -> String {
        let existing = text.split(whereSeparator: \.isNewline)
            .map { PlannedJourneyText.trimmed(String($0)) }
            .filter { !$0.isEmpty }
        var seen = Set(existing.map { $0.lowercased() })
        let added = lines
            .map { PlannedJourneyText.trimmed(label($0)) }
            .filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
        return (existing + added).prefix(PlannedJourneySchema.maximumChecklistItems).joined(separator: "\n")
    }
}
