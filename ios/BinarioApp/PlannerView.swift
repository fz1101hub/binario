import SwiftUI
import BinarioKit

struct PlannerView: View {
    @EnvironmentObject private var model: AppModel
    @State private var origin: Station?
    @State private var destination: Station?
    @State private var departure = Date()
    @State private var validationKey: String?
    @State private var isSearching = false

    var body: some View {
        Form {
            Section {
                Label("planner.intro.title", systemImage: "train.side.front.car")
                    .font(.headline)
                Text("planner.intro.body")
                    .foregroundStyle(.secondary)
            }

            switch model.catalogState {
            case .loading:
                Section {
                    HStack {
                        ProgressView()
                        Text("catalog.loading")
                    }
                }
            case .available:
                Section {
                    StationPickerField(titleKey: "planner.origin", selection: $origin)
                    StationPickerField(titleKey: "planner.destination", selection: $destination)
                    DatePicker("planner.departure", selection: $departure)
                }

                Section {
                    Button(action: swapStations) {
                        Label("planner.swap", systemImage: "arrow.up.arrow.down")
                    }
                    .accessibilityLabel(Text("accessibility.swap_route"))

                    Button(action: search) {
                        if isSearching {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("planner.search")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(origin == nil || destination == nil || isSearching)

                    Button(action: save) {
                        Label("planner.save", systemImage: "star")
                    }
                    .disabled(origin == nil || destination == nil)
                }

                if let validationKey {
                    Section { Text(LocalizedStringKey(validationKey)).foregroundStyle(.red) }
                }

                if model.searchState == .unavailable {
                    UnavailableResultView()
                }
            case .unavailable:
                ContentUnavailableView(
                    "catalog.unavailable.title",
                    systemImage: "exclamationmark.triangle",
                    description: Text("catalog.unavailable.body")
                )
            }
        }
        .navigationTitle("planner.title")
    }

    private func swapStations() {
        (origin, destination) = (destination, origin)
        validationKey = nil
    }

    private func makeQuery() -> JourneyQuery? {
        guard let origin, let destination else {
            validationKey = "planner.selection_required"
            return nil
        }
        guard origin.id != destination.id else {
            validationKey = "planner.same_station"
            return nil
        }
        do {
            return try JourneyQuery(
                origin: StationIdentity(origin),
                destination: StationIdentity(destination),
                departure: departure
            )
        } catch {
            validationKey = "planner.selection_required"
            return nil
        }
    }

    private func search() {
        guard let query = makeQuery() else { return }
        validationKey = nil
        isSearching = true
        Task {
            await model.run(query: query)
            isSearching = false
        }
    }

    private func save() {
        guard let query = makeQuery() else { return }
        validationKey = nil
        model.save(query: query)
    }
}

private struct UnavailableResultView: View {
    var body: some View {
        Section("result.title") {
            ContentUnavailableView(
                "result.unavailable.title",
                systemImage: "wifi.slash",
                description: Text("result.unavailable.body")
            )
            Text("result.unavailable.disclosure")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}
