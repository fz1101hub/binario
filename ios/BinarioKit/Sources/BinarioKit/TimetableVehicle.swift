import Foundation

/// What a service of the bundled timetable travels on, as Android names a leg of its railway packs
/// (`journeyLegMode`): a train, the operator's own scheduled coach, or a bus replacing a train.
/// contracts/shared-rules/v1/journey-leg-vehicles-v1.json holds the cases both apps test.
public enum TimetableLegMode: String, Codable, Equatable, Sendable {
    case train = "TRAIN"
    case bus = "BUS"
    case replacementBus = "REPLACEMENT_BUS"

    /// The packs whose bus routes are the operator's own scheduled coaches (Android's
    /// `EmbeddedGtfsPack.busRoutesAreScheduledCoaches`; SCHEDULED_COACH_PACKS of the index builder):
    /// Trenitalia's buses and ItaloBus. A bus of any other pack replaces a train.
    public static let scheduledCoachPacks: Set<String> = ["trenitalia-gtfs", "italo-gtfs"]

    /// The mode of a GTFS route type of the index: rail (2, 100–199) or bus (3, 700–799), nil for
    /// any other type, which is no leg of a rail journey.
    public static func of(routeType: Int64, sourceID: String) -> TimetableLegMode? {
        switch routeType {
        case 2, 100...199: return .train
        case 3, 700...799: return scheduledCoachPacks.contains(sourceID) ? .bus : .replacementBus
        default: return nil
        }
    }
}

/// The vehicle a service is shown as (Android's `JourneyLegVehicle` for the modes of a railway
/// pack): a train shows its category and number, a bus a bus icon and its label.
public enum TimetableVehicle: String, Codable, Equatable, Sendable, CaseIterable {
    case train = "TRAIN"
    /// Scheduled Trenitalia bus («Bus Trenitalia»).
    case busTrenitalia = "BUS_TRENITALIA"
    /// Trenitalia FrecciaLink coach, when the timetable names it so.
    case freccialink = "FRECCIALINK"
    case italobus = "ITALOBUS"
    /// Scheduled coach of another operator.
    case bus = "BUS"
    /// Bus replacing a train.
    case replacementBus = "REPLACEMENT_BUS"

    /// `JourneyLegVehicle.of`: a coach is FrecciaLink by its line («FL», «FRECCIALINK»), ItaloBus
    /// by its operator or line, a Trenitalia bus by its operator, else a bus.
    public static func of(mode: TimetableLegMode, operatorName: String?, lineLabel: String?) -> TimetableVehicle {
        switch mode {
        case .train:
            return .train
        case .replacementBus:
            return .replacementBus
        case .bus:
            let operatorText = (operatorName ?? "").lowercased()
            let line = (lineLabel ?? "").uppercased().replacingOccurrences(of: " ", with: "")
            if line == "FL" || line.contains("FRECCIALINK") { return .freccialink }
            if operatorText.contains("italo") || line.contains("ITALOBUS") { return .italobus }
            if operatorText.contains("trenitalia") { return .busTrenitalia }
            return .bus
        }
    }

    /// The localization key of the vehicle label (Android's `journey_leg_*`, the same text in the
    /// 17 languages); nil for a train.
    public var labelKey: String? {
        switch self {
        case .train: return nil
        case .busTrenitalia: return "result.vehicle.bus_trenitalia"
        case .freccialink: return "result.vehicle.freccialink"
        case .italobus: return "result.vehicle.italobus"
        case .bus: return "result.vehicle.bus"
        case .replacementBus: return "result.vehicle.replacement_bus"
        }
    }

    public var isBus: Bool { self != .train }

    /// The label of a bus with its published line, as Android's `journeyLegLabel` writes it before
    /// the number: «Bus sostitutivo S11», «Bus Trenitalia BUS»; for a coach the line only when it
    /// adds something to the label («ItaloBus», not «ItaloBus ItaloBus»). A line that is a long
    /// name, a placeholder or the number itself is left out (`numberAndLine`). `label` is the
    /// localized text of `labelKey`.
    public static func busLabel(_ label: String, vehicle: TimetableVehicle, line: String?, number: String? = nil) -> String {
        guard let line = line?.trimmingCharacters(in: .whitespaces), !line.isEmpty, line.count <= 10,
              !line.contains("("), line.caseInsensitiveCompare("INCONNU") != .orderedSame,
              number.map({ line.caseInsensitiveCompare($0.trimmingCharacters(in: .whitespaces)) != .orderedSame }) ?? true
        else { return label }
        if vehicle != .replacementBus {
            let compactLine = line.replacingOccurrences(of: " ", with: "")
            let compactLabel = label.replacingOccurrences(of: " ", with: "")
            if compactLine.caseInsensitiveCompare(compactLabel) == .orderedSame { return label }
        }
        return "\(label) \(line)"
    }
}

/// How the bundled timetable names a service, as Android's `EmbeddedGtfsRouteAdapter` does for the
/// same trips, so that one train reads the same on both phones.
public enum TimetableNaming {
    /// A route's short name as Android shows it (`lineLabel`): Trenitalia's carries its company code
    /// first («0083_FR» is «FR», «0083_BUS» is «BUS»); any other is kept as published.
    public static func lineLabel(_ shortName: String) -> String {
        let trimmed = shortName.trimmingCharacters(in: .whitespaces)
        let prefix = "0083_"
        guard trimmed.hasPrefix(prefix) else { return trimmed }
        let code = trimmed.dropFirst(prefix.count)
        guard (1...4).contains(code.count),
              code.unicodeScalars.allSatisfy({ ("A"..."Z").contains($0) || ("a"..."z").contains($0) })
        else { return trimmed }
        return String(code)
    }

    /// The operator a timetable names (Android's `canonicalAgencyLabel`): the short brand of the
    /// operators the apps know («TRENITALIA» is «Trenitalia», «Italo - Nuovo Trasporto
    /// Viaggiatori» is «Italo»), any other agency name as published.
    public static func operatorName(_ agencyName: String, sourceID: String) -> String {
        let raw = agencyName.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty {
            switch sourceID {
            case "lombardia-trenord-gtfs": return "Trenord"
            case "oebb-gtfs": return "ÖBB"
            case "sncf-gtfs": return "SNCF Voyageurs"
            default: return raw
            }
        }
        let normalized = normalize(raw)
        let lower = raw.lowercased()
        if normalized.contains("tilo") { return "TILO" }
        if normalized.contains("schweizerischebundesbahnen") || containsWord("sbb", in: lower) { return "SBB" }
        if normalized.contains("bls") { return "BLS" }
        if normalized.contains("rhatischebahn") || normalized.contains("rhaetischebahn") || normalized == "rhb" { return "RhB" }
        if normalized.contains("ferrovieautolineeregionaliticinesi") || normalized == "fart" { return "FART" }
        if normalized.contains("societasubalpinadiimpreseferroviarie") || normalized == "ssif" { return "SSIF" }
        if normalized.contains("osterreichischebundesbahnen") || normalized.contains("oebb") || normalized == "obb" { return "ÖBB" }
        if normalized.contains("dbfernverkehr") || normalized.contains("deutschebahn") { return "DB" }
        if normalized.contains("sncf") { return "SNCF Voyageurs" }
        if normalized.contains("europeansleeper") { return "European Sleeper" }
        if normalized.contains("trenord") { return "Trenord" }
        if normalized.contains("trenitalia") { return "Trenitalia" }
        if normalized.contains("ntv") || normalized.contains("italotreno") || normalized.hasPrefix("italo") { return "Italo" }
        return raw
    }

    /// Android's `normalize`: without accents, lower case, letters and digits only.
    static func normalize(_ value: String) -> String {
        let folded = value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
        return String(folded.unicodeScalars.filter { ("a"..."z").contains($0) || ("0"..."9").contains($0) }.map(Character.init))
    }

    /// `(^|[^a-z])word([^a-z]|$)` on lower-case text.
    private static func containsWord(_ word: String, in text: String) -> Bool {
        var searchStart = text.startIndex
        while let range = text.range(of: word, range: searchStart..<text.endIndex) {
            let before = range.lowerBound == text.startIndex ? nil : text[text.index(before: range.lowerBound)]
            let after = range.upperBound == text.endIndex ? nil : text[range.upperBound]
            if !isLowerLetter(before), !isLowerLetter(after) { return true }
            searchStart = text.index(after: range.lowerBound)
        }
        return false
    }

    private static func isLowerLetter(_ character: Character?) -> Bool {
        guard let character, let scalar = character.unicodeScalars.first, character.unicodeScalars.count == 1 else { return false }
        return ("a"..."z").contains(scalar)
    }
}
