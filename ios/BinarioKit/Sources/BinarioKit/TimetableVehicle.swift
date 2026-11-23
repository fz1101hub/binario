import Foundation

/// What a service of the bundled timetable travels on, as Android names a leg (`journeyLegMode`): a
/// train, the operator's own scheduled coach, a bus replacing a train, or a metro, tram or funicular
/// line of a city network. contracts/shared-rules/v1/journey-leg-vehicles-v1.json holds the cases
/// both apps test.
public enum TimetableLegMode: String, Codable, Equatable, Sendable {
    case train = "TRAIN"
    case bus = "BUS"
    case replacementBus = "REPLACEMENT_BUS"
    /// A metro line, the Marconi Express people mover (monorail, route type 12) too.
    case metro = "METRO"
    case tram = "TRAM"
    /// A funicular, a rack railway or a lift of a city network.
    case funicular = "FUNICULAR"

    /// The packs whose bus routes are the operator's own scheduled coaches (Android's
    /// `EmbeddedGtfsPack.busRoutesAreScheduledCoaches`; SCHEDULED_COACH_PACKS of the index builder):
    /// Trenitalia's buses and ItaloBus. A bus of any other pack replaces a train. The shared table
    /// contracts/shared-data/v1/pack-roles-v1.json (`SharedData.PackRoles`).
    public static let scheduledCoachPacks: Set<String> = SharedData.PackRoles.scheduledCoachPacks

    /// The mode of a GTFS route type of the index: rail (2, 100–199), metro (1, 12, 400–499), tram
    /// (0, 900–999), funicular and lift (5, 6, 7, 1300–1499) or bus (3, 700–799); nil for any other
    /// type (a ferry, a flight), which is no leg of a rail journey.
    public static func of(routeType: Int64, sourceID: String) -> TimetableLegMode? {
        switch routeType {
        case 2, 100...199: return .train
        case 1, 12, 400...499: return .metro
        case 0, 900...999: return .tram
        case 5, 6, 7, 1300...1499: return .funicular
        case 3, 700...799: return scheduledCoachPacks.contains(sourceID) ? .bus : .replacementBus
        default: return nil
        }
    }

    /// A line of a city network: shown with its line, never a train number.
    public var isUrban: Bool { self == .metro || self == .tram || self == .funicular }
}

/// The vehicle a service is shown as (Android's `JourneyLegVehicle`): a train shows its category and
/// number, a bus a bus icon and its label, a city line its mode and line («Metro M1», «Tram 14»).
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
    /// City metro line («Metro M1»).
    case metro = "METRO"
    /// City tram line («Tram 14»).
    case tram = "TRAM"
    /// Funicular or lift of a city network.
    case funicular = "FUNICULAR"

    /// `JourneyLegVehicle.of`: a coach is FrecciaLink by its line («FL», «FRECCIALINK»), ItaloBus
    /// by its operator or line, a Trenitalia bus by its operator, else a bus; a city line is its mode.
    public static func of(mode: TimetableLegMode, operatorName: String?, lineLabel: String?) -> TimetableVehicle {
        switch mode {
        case .train:
            return .train
        case .replacementBus:
            return .replacementBus
        case .metro:
            return .metro
        case .tram:
            return .tram
        case .funicular:
            return .funicular
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
        case .metro: return "result.vehicle.metro"
        case .tram: return "result.vehicle.tram"
        case .funicular: return "result.vehicle.funicular"
        }
    }

    /// A bus (Android's `JourneyLegVehicle.isBus`): a bus icon, no train statistics.
    public var isBus: Bool {
        switch self {
        case .busTrenitalia, .freccialink, .italobus, .bus, .replacementBus: return true
        case .train, .metro, .tram, .funicular: return false
        }
    }

    /// A metro, tram or funicular line of a city network: its mode and line, never a train number.
    public var isUrban: Bool { urbanMode != nil }

    /// The mode of a city line; nil for a train or a bus.
    public var urbanMode: TimetableLegMode? {
        switch self {
        case .metro: return .metro
        case .tram: return .tram
        case .funicular: return .funicular
        case .train, .busTrenitalia, .freccialink, .italobus, .bus, .replacementBus: return nil
        }
    }

    /// The label of a city line, as Android's `journeyLegLabel` writes it: «Metro M1», «Tram 14»,
    /// «Funicolare»; a city run has no public number. A line that is a long name, a placeholder or
    /// only repeats the mode («METRO», «Tramvia») is left out (`urbanLineRepeatsMode`). `label` is
    /// the localized text of `labelKey`, `line` the run's `TimetableNaming.urbanLine`.
    public static func urbanLabel(_ label: String, line: String?) -> String {
        guard let line = line?.trimmingCharacters(in: .whitespaces), !line.isEmpty, line.count <= 10,
              !line.contains("("), line.caseInsensitiveCompare("INCONNU") != .orderedSame,
              !urbanLineRepeatsMode(line, label: label)
        else { return label }
        return "\(label) \(line)"
    }

    /// Android's `urbanLineRepeatsMode`: the line, without spaces and in lower case, is the label
    /// itself or a word that names a city mode in any language of the list.
    static func urbanLineRepeatsMode(_ line: String, label: String) -> Bool {
        let compact = line.replacingOccurrences(of: " ", with: "").lowercased()
        return compact == label.replacingOccurrences(of: " ", with: "").lowercased() || urbanModeWords.contains(compact)
    }

    private static let urbanModeWords: Set<String> = [
        "metro", "metropolitana", "métro", "u-bahn", "subway", "tram", "tramvia", "tranvia", "tranvía", "tramway",
        "straßenbahn", "funicolare", "funicular", "funiculaire", "standseilbahn",
    ]

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
    /// Android's `urbanLineName`: the public name of a city line from its route_short_name. Milan
    /// publishes its metro lines as «1»…«5» («M1»), Rome as «MEA», «MEB», «MEB1», «MEC» («A»,
    /// «B1»); every other name is kept as published («M1», «T2», «14», the Marconi Express «MEX»).
    /// Nil without a name.
    public static func urbanLine(_ shortName: String?, mode: TimetableLegMode) -> String? {
        guard let value = shortName?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        guard mode == .metro else { return value }
        let scalars = Array(value.unicodeScalars)
        if scalars.count <= 2, scalars.allSatisfy({ ("0"..."9").contains($0) }) { return "M\(value)" }
        // ME followed by A, B or C and at most one digit: Rome's lines.
        if (3...4).contains(scalars.count), scalars[0] == "M", scalars[1] == "E", ("A"..."C").contains(scalars[2]),
           scalars.count == 3 || ("0"..."9").contains(scalars[3]) {
            return String(value.dropFirst(2))
        }
        return value
    }

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
