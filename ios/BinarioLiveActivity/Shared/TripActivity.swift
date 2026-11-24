import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif
import AppIntents

// The followed trip's Live Activity: the iPhone's form of Android's ongoing notification («Viaggio seguito»).
// Compiled into the app and into the BinarioLiveActivity extension. Every text arrives already localized from the
// app, so the extension carries no strings and no data of its own.

#if canImport(ActivityKit)
struct TripActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// «REG 2153 · ritardo 5 min».
        var title: String
        /// The collapsed line: the countdown and next stop, or the change at risk.
        var line: String
        /// The expanded lines: countdown, next stop, estimate, change, «Aggiornato alle …».
        var details: [String]
        /// Short text for the Dynamic Island: «+5 min», «1 fermata».
        var compact: String
        /// Cancelled, or a change at risk: drawn in the warning colour.
        var warning: Bool
    }

    /// The trip the actions act on.
    var tripID: String
    var refreshLabel: String
    var stopLabel: String
}
#endif

/// What the buttons of the Live Activity ask the app.
enum TripActivityAction: String, Sendable {
    case refresh
    case stop
}

/// Where the app receives the buttons: `LiveActivityIntent`s run in the app's process, which sets the handler at
/// launch. In the extension the handler stays nil.
@MainActor
enum TripActivityActions {
    static var handler: (@MainActor (TripActivityAction, String) async -> Void)?

    static func perform(_ action: TripActivityAction, tripID: String) async {
        await handler?(action, tripID)
    }
}

/// «Aggiorna» of the Live Activity.
struct TripRefreshIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Aggiorna"
    static let isDiscoverable = false

    @Parameter(title: "Viaggio")
    var tripID: String

    init() {
        tripID = ""
    }

    init(tripID: String) {
        self.tripID = tripID
    }

    func perform() async throws -> some IntentResult {
        await TripActivityActions.perform(.refresh, tripID: tripID)
        return .result()
    }
}

/// «Interrompi» of the Live Activity: ends it and the following of the trip.
struct TripStopIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Interrompi"
    static let isDiscoverable = false

    @Parameter(title: "Viaggio")
    var tripID: String

    init() {
        tripID = ""
    }

    init(tripID: String) {
        self.tripID = tripID
    }

    func perform() async throws -> some IntentResult {
        await TripActivityActions.perform(.stop, tripID: tripID)
        return .result()
    }
}
