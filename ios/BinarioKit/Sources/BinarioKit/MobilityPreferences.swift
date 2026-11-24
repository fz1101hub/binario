import Foundation

/// «Mobilità e accessibilità» (Android's `MobilityPreferences`): kept on the phone and in the local backup. As on Android,
/// the search does not read them; the planner follows «Preferenze di viaggio». Defaults, bounds and the panel's choices
/// are in contracts/shared-data/v1/mobility-preferences-v1.json.
public struct MobilityPreferences: Codable, Equatable, Sendable {
    /// Nil: any number of changes.
    public var maxChanges: Int?
    public var transferMarginMinutes: Int
    public var stepFreePreferred: Bool
    public var compactTimetablePreferred: Bool

    public init(
        maxChanges: Int? = nil,
        transferMarginMinutes: Int = SharedData.MobilityPreferences.defaultTransferMarginMinutes,
        stepFreePreferred: Bool = false,
        compactTimetablePreferred: Bool = false
    ) {
        self.maxChanges = maxChanges.flatMap { $0 >= 0 ? $0 : nil }
        self.transferMarginMinutes = min(max(transferMarginMinutes, 0), SharedData.MobilityPreferences.maximumTransferMarginMinutes)
        self.stepFreePreferred = stepFreePreferred
        self.compactTimetablePreferred = compactTimetablePreferred
    }

    /// The backup's «mobility» object (Android's `LocalDataBackupCodec`): no «maxChanges» for any number of changes.
    public var backupJSON: [String: Any] {
        var value: [String: Any] = [
            "transferMarginMinutes": transferMarginMinutes,
            "stepFreePreferred": stepFreePreferred,
            "compactTimetablePreferred": compactTimetablePreferred,
        ]
        if let maxChanges { value["maxChanges"] = maxChanges }
        return value
    }

    /// A backup's «mobility» object; missing values take the defaults, a margin out of bounds the nearest bound.
    public init(backupJSON value: [String: Any]) {
        self.init(
            maxChanges: PunctualityHistory.jsonInt(value["maxChanges"]),
            transferMarginMinutes: PunctualityHistory.jsonInt(value["transferMarginMinutes"]) ?? SharedData.MobilityPreferences.defaultTransferMarginMinutes,
            stepFreePreferred: value["stepFreePreferred"] as? Bool ?? false,
            compactTimetablePreferred: value["compactTimetablePreferred"] as? Bool ?? false
        )
    }

    // A stored value is read with the same bounds as a backup's.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            maxChanges: try container.decodeIfPresent(Int.self, forKey: .maxChanges),
            transferMarginMinutes: try container.decodeIfPresent(Int.self, forKey: .transferMarginMinutes)
                ?? SharedData.MobilityPreferences.defaultTransferMarginMinutes,
            stepFreePreferred: try container.decodeIfPresent(Bool.self, forKey: .stepFreePreferred) ?? false,
            compactTimetablePreferred: try container.decodeIfPresent(Bool.self, forKey: .compactTimetablePreferred) ?? false
        )
    }
}
