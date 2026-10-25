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
                Image(systemName: "chevron.right")
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

    private var suggestions: [Station] { model.suggestions(for: query) }

    var body: some View {
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
                            VStack(alignment: .leading, spacing: 3) {
                                Text(station.name)
                                    .foregroundStyle(.primary)
                                if let code = station.railwayCode {
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
        let label = "\(String(localized: "accessibility.selected_station")): \(station.name)"
        guard model.hasTimetable(station) == false else { return label }
        return "\(label), \(String(localized: "planner.station.no_timetable"))"
    }
}
