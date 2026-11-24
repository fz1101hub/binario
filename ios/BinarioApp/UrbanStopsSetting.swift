import Foundation

/// «Includi fermate di metro e tram nella ricerca» (Android's `SourcesSettingsStore.urbanStopsInSearch`):
/// the stops of the city networks among the station suggestions, with their M, T or F badge. On by
/// default in the personal configuration (`BINARIO_PERSONAL_SOURCES`), off otherwise, as on Android; the planner
/// and the city stops' boards use the city lines either way. Kept on this iPhone only.
enum UrbanStopsSetting {
    static let key = "it.binario.ios.urban-stops-in-search.v1"

    static var defaultValue: Bool {
        #if BINARIO_PERSONAL_SOURCES
        return true
        #else
        return false
        #endif
    }

    static var isOn: Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? defaultValue
    }
}
