import SwiftUI
import BinarioKit

struct PlannerView: View {
    @EnvironmentObject private var model: AppModel
    @State private var origin: Station?
    @State private var destination: Station?
    @State private var departure = Date()
    @State private var validationKey: String?
    @State private var isSearching = false
    @State private var searchRequestID = 0

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

                if model.scheduleState == .loading {
                    Section {
                        HStack {
                            ProgressView()
                            Text("schedule.loading")
                        }
                    }
                } else if model.scheduleState == .unavailable {
                    Section {
                        Label("schedule.unavailable", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.secondary)
                    }
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
                    .disabled(
                        origin == nil || destination == nil || isSearching || model.scheduleState != .available
                    )

                    Button(action: save) {
                        Label("planner.save", systemImage: "star")
                    }
                    .disabled(origin == nil || destination == nil)
                }

                if let validationKey {
                    Section { Text(LocalizedStringKey(validationKey)).foregroundStyle(.red) }
                }

                if let snapshot = model.latestSnapshot {
                    AvailableResultView(snapshot: snapshot)
                } else if case let .unavailable(reason) = model.searchState {
                    UnavailableResultView(reason: reason)
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
        .onChange(of: origin?.id) { _, _ in inputDidChange() }
        .onChange(of: destination?.id) { _, _ in inputDidChange() }
        .onChange(of: departure) { _, _ in inputDidChange() }
    }

    private func swapStations() {
        (origin, destination) = (destination, origin)
        validationKey = nil
    }

    private func inputDidChange() {
        searchRequestID &+= 1
        validationKey = nil
        isSearching = false
        model.invalidateSearchResult()
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
        searchRequestID &+= 1
        let requestID = searchRequestID
        isSearching = true
        Task {
            await model.run(query: query)
            guard requestID == searchRequestID else { return }
            isSearching = false
        }
    }

    private func save() {
        guard let query = makeQuery() else { return }
        validationKey = nil
        model.save(query: query)
    }
}

private struct AvailableResultView: View {
    let snapshot: JourneySnapshot

    var body: some View {
        Section("result.title") {
            ForEach(snapshot.services, id: \.id) { service in
                VStack(alignment: .leading, spacing: 5) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(service.scheduledDeparture)
                            .font(.headline.monospacedDigit())
                        Text(service.number)
                            .font(.headline)
                        Spacer()
                        Text(service.category)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(service.destination)
                    Text(service.operatorName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }

            Text("\(String(localized: "result.source_label")) \(snapshot.provenance.sourceLabel)")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Label("result.programmed.disclosure", systemImage: "clock")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

private struct UnavailableResultView: View {
    let reason: JourneyUnavailableReason

    var body: some View {
        Section("result.title") {
            ContentUnavailableView(
                titleKey,
                systemImage: reason == .providerInvalid || reason == .noAuthorizedProvider
                    ? "exclamationmark.triangle" : "calendar.badge.exclamationmark",
                description: Text(bodyKey)
            )
            Text("result.unavailable.disclosure")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var titleKey: LocalizedStringKey {
        switch reason {
        case .noJourney: "result.unavailable.title"
        case .scheduleExpired: "result.expired.title"
        case .noAuthorizedProvider, .providerInvalid: "result.provider_error.title"
        }
    }

    private var bodyKey: LocalizedStringKey {
        switch reason {
        case .noJourney: "result.unavailable.body"
        case .scheduleExpired: "result.expired.body"
        case .noAuthorizedProvider, .providerInvalid: "result.provider_error.body"
        }
    }
}
