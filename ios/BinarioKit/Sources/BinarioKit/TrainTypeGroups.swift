import Foundation

/// The train types a station board can hide («Tipi di treno»), Android's `TrainTypeGroup`, in the
/// same order.
public enum TrainTypeGroup: String, CaseIterable, Hashable, Sendable {
    case highSpeed = "HIGH_SPEED"
    case intercityNight = "INTERCITY_NIGHT"
    case regionalSuburban = "REGIONAL_SUBURBAN"
    case italo = "ITALO"
    case replacementBus = "REPLACEMENT_BUS"
    case international = "INTERNATIONAL"
}

/// The group of a board row from what the sources publish, as Android's `WidgetTrainTypes.groupOf`
/// for a station board row: a bus first, then the operator, then the letters that open the
/// category or line label. Nil when nothing tells: such a row is never hidden. Codes and pattern:
/// `SharedData.TrainTypeGroups`; cases: contracts/shared-rules/v1/train-type-groups-v1.json.
public enum TrainTypeGroups {
    /// The whole label (Kotlin's `Regex.matches`), compiled once (`LiveRegex` is Sendable).
    private static let regional = LiveRegex("^(?:" + SharedData.TrainTypeGroups.regionalCodePattern + ")$")

    /// `operatorID` is a lower-case operator id («italo», «sbb»), as Android's board rows carry it.
    public static func group(label: String, operatorID: String?, isBus: Bool) -> TrainTypeGroup? {
        let table = SharedData.TrainTypeGroups.self
        let identifier = (operatorID ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        let code = label.uppercased().replacingOccurrences(of: " ", with: "")
        let prefix = String(code.prefix { $0.isLetter })
        if isBus || table.busCodes.contains(prefix) { return .replacementBus }
        if table.italoOperatorIds.contains(identifier) { return .italo }
        if table.internationalOperatorIds.contains(identifier) { return .international }
        if table.internationalCodes.contains(prefix) { return .international }
        if table.highSpeedCodes.contains(prefix) { return .highSpeed }
        if table.intercityCodes.contains(prefix) { return .intercityNight }
        if !code.isEmpty, regional.contains(code) { return .regionalSuburban }
        return nil
    }

    /// The group of a listed service: its category, its operator's id and whether it is a bus of
    /// the timetable. A city line has no group.
    public static func group(of service: TrainService) -> TrainTypeGroup? {
        if service.isUrban { return nil }
        return group(label: service.category, operatorID: operatorID(service.operatorName), isBus: service.isBus)
    }

    /// The id Android's rows carry for a published operator name: «Italo», «SBB CFF FFS», «ÖBB».
    public static func operatorID(_ name: String) -> String? {
        let lower = name.lowercased()
        if lower.contains("italo") { return "italo" }
        if lower.contains("european sleeper") { return "european-sleeper" }
        let words = Set(lower.split(whereSeparator: { !$0.isLetter }).map(String.init))
        if words.contains("öbb") || words.contains("oebb") { return "oebb" }
        return SharedData.TrainTypeGroups.internationalOperatorIds.sorted().first { words.contains($0) }
    }

    /// A row is hidden only when its group is known and excluded.
    public static func isHidden(_ group: TrainTypeGroup?, excluded: Set<TrainTypeGroup>) -> Bool {
        guard let group else { return false }
        return excluded.contains(group)
    }

    /// Stored form, as Android's: group names separated by commas; unknown names are ignored.
    public static func encode(_ excluded: Set<TrainTypeGroup>) -> String {
        TrainTypeGroup.allCases.filter { excluded.contains($0) }.map(\.rawValue).joined(separator: ",")
    }

    public static func decode(_ value: String?) -> Set<TrainTypeGroup> {
        Set((value ?? "").split(separator: ",").compactMap { TrainTypeGroup(rawValue: $0.trimmingCharacters(in: .whitespaces)) })
    }
}
