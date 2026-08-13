import Foundation

enum BinarioDirection: String, Codable, CaseIterable {
    case outbound = "OUTBOUND"
    case `return` = "RETURN"

    var accessibilityLabel: String {
        switch self {
        case .outbound: return "Andata"
        case .return: return "Ritorno"
        }
    }
}

enum BinarioFreshness: String, Codable {
    case fresh = "FRESH"
    case aging = "AGING"
    case stale = "STALE"
    case unavailable = "UNAVAILABLE"

    var label: String {
        switch self {
        case .fresh: return "Dati aggiornati"
        case .aging: return "Dati in aggiornamento"
        case .stale: return "Ultimo dato disponibile"
        case .unavailable: return "Dati non disponibili in tempo reale"
        }
    }
}

struct WatchService: Codable, Equatable, Identifiable {
    let number: String
    let operatorName: String
    let category: String
    let destination: String
    let scheduledDeparture: String
    let expectedDeparture: String
    let platform: String?
    let delayMinutes: Int
    let status: String

    var id: String { number + "-" + expectedDeparture }

    var accessibilitySummary: String {
        let delay = delayMinutes > 0 ? ", ritardo di \(delayMinutes) minuti" : ""
        let platformLabel = platform.map { ", binario \($0)" } ?? ", binario non disponibile"
        return "\(operatorName), \(category), treno \(number), destinazione \(destination), previsto alle \(expectedDeparture)\(delay)\(platformLabel)."
    }
}

struct BinarioWatchSnapshot: Codable, Equatable {
    static let currentSchemaVersion = 1

    let schemaVersion: Int
    let routeId: String
    let routeLabel: String
    let direction: BinarioDirection
    let services: [WatchService]
    let providerName: String
    let freshness: BinarioFreshness
    let confidenceLabel: String
    let retrievedAtEpochMillis: Int64
    let notice: String?

    var isCompatible: Bool { schemaVersion == Self.currentSchemaVersion }

    var retrievedAt: Date {
        Date(timeIntervalSince1970: TimeInterval(retrievedAtEpochMillis) / 1_000)
    }

    static let unavailable = BinarioWatchSnapshot(
        schemaVersion: currentSchemaVersion,
        routeId: "",
        routeLabel: "Nessuna tratta attiva",
        direction: .outbound,
        services: [],
        providerName: "Nessun provider",
        freshness: .unavailable,
        confidenceLabel: "Qualità non disponibile",
        retrievedAtEpochMillis: 0,
        notice: "dati non disponibili in tempo reale"
    )
}
