import SwiftUI
import BinarioKit

/// Impostazioni › «Preferenze di viaggio» (Android's `TravelPreferencesSettings`): walking speed and longest walk,
/// «Preferisci», the kinds of service to prefer or avoid (a tap cycles indifferent, preferred, avoided) and «Solo
/// percorsi accessibili in sedia a rotelle». Kept on this iPhone and sent only to the offline planner.
struct TravelPreferencesView: View {
    @EnvironmentObject private var journeys: JourneysModel

    var body: some View {
        let preferences = journeys.preferences
        Form {
            Section {
                Text("settings.travel.description")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("settings.travel.walking_speed") {
                Picker("settings.travel.walking_speed", selection: binding(\.walkingSpeed)) {
                    Text("settings.travel.walking_slow").tag(WalkingSpeed.slow)
                    Text("settings.travel.walking_normal").tag(WalkingSpeed.normal)
                    Text("settings.travel.walking_fast").tag(WalkingSpeed.fast)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            Section("settings.travel.max_walk") {
                Picker("settings.travel.max_walk", selection: binding(\.maxWalkMinutes)) {
                    ForEach(JourneyPreferences.walkMinuteChoices, id: \.self) { minutes in
                        Text(verbatim: String.localizedStringWithFormat(NSLocalizedString("settings.travel.minutes", comment: ""), minutes))
                            .tag(minutes)
                    }
                }
                .pickerStyle(.segmented)
            }
            Section("settings.travel.ranking") {
                Picker("settings.travel.ranking", selection: binding(\.ranking)) {
                    Text("settings.travel.ranking_fastest").tag(JourneyRanking.fastest)
                    Text("result.journey.filter.fewest_changes").tag(JourneyRanking.fewestChanges)
                    Text("settings.travel.ranking_least_walking").tag(JourneyRanking.leastWalking)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            Section {
                ForEach(TravelMode.allCases, id: \.self) { mode in
                    let choice = preferences.choice(of: mode)
                    Button {
                        journeys.setPreferences(preferences.with(mode, choice: Self.next(choice)))
                    } label: {
                        HStack {
                            Text(verbatim: Self.label(mode))
                                .foregroundStyle(.primary)
                            Spacer()
                            Text(verbatim: Self.stateLabel(choice))
                                .foregroundStyle(choice == .avoid ? BinarioPalette.statusError : choice == .prefer ? BinarioPalette.brandGreen : .secondary)
                        }
                    }
                    .accessibilityValue(Text(verbatim: Self.stateLabel(choice)))
                }
            } header: {
                Text("settings.travel.modes")
            } footer: {
                Text("settings.travel.modes_description")
            }
            Section {
                Toggle("settings.travel.wheelchair_only", isOn: binding(\.wheelchairOnly))
            } footer: {
                Text("settings.travel.wheelchair_note")
            }
        }
        .navigationTitle("settings.travel.title")
    }

    /// A binding that saves the preferences (and searches the journeys on screen again).
    private func binding<Value>(_ path: WritableKeyPath<JourneyPreferences, Value>) -> Binding<Value> {
        Binding(
            get: { journeys.preferences[keyPath: path] },
            set: { value in
                var copy = journeys.preferences
                copy[keyPath: path] = value
                journeys.setPreferences(copy)
            }
        )
    }

    /// A tap: indifferent, preferred, avoided, then indifferent again.
    private static func next(_ choice: ModeChoice) -> ModeChoice {
        switch choice {
        case .any: return .prefer
        case .prefer: return .avoid
        case .avoid: return .any
        }
    }

    private static func stateLabel(_ choice: ModeChoice) -> String {
        switch choice {
        case .any: return NSLocalizedString("settings.travel.state_any", comment: "")
        case .prefer: return NSLocalizedString("settings.travel.state_prefer", comment: "")
        case .avoid: return NSLocalizedString("settings.travel.state_avoid", comment: "")
        }
    }

    private static func label(_ mode: TravelMode) -> String {
        switch mode {
        case .highSpeed: return NSLocalizedString("settings.travel.mode_high_speed", comment: "")
        case .intercity: return NSLocalizedString("settings.travel.mode_intercity", comment: "")
        case .regional: return NSLocalizedString("settings.travel.mode_regional", comment: "")
        case .italo: return NSLocalizedString("settings.travel.mode_italo", comment: "")
        case .metro: return NSLocalizedString("result.vehicle.metro", comment: "")
        case .tram: return NSLocalizedString("result.vehicle.tram", comment: "")
        case .funicular: return NSLocalizedString("settings.travel.mode_funicular", comment: "")
        case .operatorBus: return NSLocalizedString("settings.travel.mode_operator_bus", comment: "")
        }
    }
}
