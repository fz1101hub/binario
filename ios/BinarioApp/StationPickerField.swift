import SwiftUI
import BinarioKit

struct StationPickerField: View {
    @EnvironmentObject private var model: AppModel
    let titleKey: LocalizedStringKey
    @Binding var selection: Station?

    @State private var presented = false

    var body: some View {
        Button {
            presented = true
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(titleKey)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(selection?.name ?? String(localized: "planner.choose_station"))
                        .foregroundStyle(selection == nil ? .secondary : .primary)
                }
                Spacer()
                // "forward" follows the layout direction: it points left in Arabic.
                Image(systemName: "chevron.forward")
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(titleKey))
        .accessibilityValue(selection?.name ?? String(localized: "planner.choose_station"))
        .sheet(isPresented: $presented) {
            StationSearchView(titleKey: titleKey, selection: $selection)
        }
    }
}

private struct StationSearchView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let titleKey: LocalizedStringKey
    @Binding var selection: Station?
    @State private var query = ""

    var body: some View {
        // Searched once per keystroke: the empty check and the list read the same results.
        let suggestions = model.suggestions(for: query)
        NavigationStack {
            Group {
                if query.isEmpty {
                    ContentUnavailableView(
                        "planner.search_station",
                        systemImage: "magnifyingglass"
                    )
                } else if suggestions.isEmpty {
                    ContentUnavailableView.search(text: query)
                } else {
                    List(suggestions) { station in
                        Button {
                            selection = station
                            dismiss()
                        } label: {
                            HStack(spacing: 10) {
                                // A city stop: its «M», «T» or «F» badge and what it is, as on Android.
                                if let badge = model.urbanStops.badge(of: station.id) {
                                    UrbanStopBadgeMark(badge: badge)
                                }
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(station.name)
                                        .foregroundStyle(.primary)
                                    if let badge = model.urbanStops.badge(of: station.id) {
                                        Text(LocalizedStringKey(badge.descriptionKey))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    } else if let code = station.railwayCode {
                                        Text(code)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                    if model.hasTimetable(station) == false {
                                        Text("planner.station.no_timetable")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                        .accessibilityLabel(Text(accessibilityLabel(for: station)))
                    }
                    .accessibilityLabel(Text("accessibility.station_suggestions"))
                }
            }
            .navigationTitle(titleKey)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "planner.search_station")
        }
    }

    private func accessibilityLabel(for station: Station) -> String {
        var label = "\(String(localized: "accessibility.selected_station")): \(station.name)"
        if let badge = model.urbanStops.badge(of: station.id) {
            label += ", " + NSLocalizedString(badge.descriptionKey, comment: "")
        }
        guard model.hasTimetable(station) == false else { return label }
        return "\(label), \(String(localized: "planner.station.no_timetable"))"
    }
}

/// The «M», «T» or «F» mark of a city stop in the station suggestions (Android's
/// `UrbanStopBadgeMark`); what it means is said by the row.
private struct UrbanStopBadgeMark: View {
    let badge: UrbanStopBadge

    var body: some View {
        Text(verbatim: badge.rawValue)
            .font(.caption.weight(.bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 5)
            .frame(minWidth: 20, minHeight: 20)
            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 5))
            .accessibilityHidden(true)
    }
}
