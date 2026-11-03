import SwiftUI
import BinarioKit

struct PlannerView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var origin: Station?
    @State private var destination: Station?
    @State private var departure = Date()
    /// False until the user picks a time: the planner then searches from the current time, so a
    /// tab left open does not keep asking (and telling the Watch) about trains that have left.
    @State private var departureEdited = false
    /// A saved route arrived while the timetables were still loading.
    @State private var searchWhenScheduleIsReady = false
    @State private var validationKey: String?
    @State private var isSearching = false
    @State private var searchRequestID = 0

    var body: some View {
        Form {
            Section {
                Label {
                    Text("planner.intro.title")
                } icon: {
                    Image(systemName: "train.side.front.car")
                        .foregroundStyle(BinarioPalette.brandGreen)
                }
                .font(.headline)
                Text("planner.intro.body")
                    .foregroundStyle(.secondary)
                if !model.includedPacks.isEmpty {
                    Text("\(String(localized: "planner.sources.footer")) \(model.includedFeedAttributions)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
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
                    StationPickerField(titleKey: "planner.origin", selection: originSelection)
                    StationPickerField(titleKey: "planner.destination", selection: destinationSelection)
                    // Results list times in the timetable zone: pick them in the same zone.
                    DatePicker("planner.departure", selection: departureSelection)
                        .environment(\.timeZone, AppModel.timetableTimeZone)
                    if AppModel.needsTimetableZoneCaption(at: departure) {
                        Text("planner.departure.italian_time")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
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
                            // The prominent fill is AccentColor: white text fails on its
                            // light dark-mode green, so the label takes the paired token.
                            Text("planner.search")
                                .foregroundStyle(canSearch ? BinarioPalette.onAccent : Color.secondary)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canSearch)

                    Button(action: save) {
                        Label("planner.save", systemImage: "star")
                    }
                    .disabled(origin == nil || destination == nil)
                }

                if let validationKey {
                    Section { Text(LocalizedStringKey(validationKey)).foregroundStyle(BinarioPalette.statusError) }
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
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { followCurrentTime() }
        }
        .onChange(of: model.scheduleState) { _, state in
            guard state == .available, searchWhenScheduleIsReady else { return }
            searchWhenScheduleIsReady = false
            if canSearch { search() }
        }
        .task(id: model.plannerRequest?.id) { applyPlannerRequest() }
    }

    private var canSearch: Bool {
        origin != nil && destination != nil && !isSearching && model.scheduleState == .available
    }

    // Only the user's own edits invalidate the result: the planner also moves the time to now
    // and fills a saved route itself, and must not revoke the Watch snapshot when it does.
    private var originSelection: Binding<Station?> {
        Binding(
            get: { origin },
            set: { station in
                guard station?.id != origin?.id else { return }
                origin = station
                inputDidChange()
            }
        )
    }

    private var destinationSelection: Binding<Station?> {
        Binding(
            get: { destination },
            set: { station in
                guard station?.id != destination?.id else { return }
                destination = station
                inputDidChange()
            }
        )
    }

    private var departureSelection: Binding<Date> {
        Binding(
            get: { departure },
            set: { date in
                guard date != departure else { return }
                departure = date
                departureEdited = true
                inputDidChange()
            }
        )
    }

    private func swapStations() {
        let changesInput = origin?.id != destination?.id
        (origin, destination) = (destination, origin)
        validationKey = nil
        if changesInput { inputDidChange() }
    }

    /// Back in the app with the default time: move it to now and, when a result is on screen,
    /// search again so the iPhone and the Watch list the trains still to come.
    private func followCurrentTime() {
        guard !departureEdited else { return }
        departure = Date()
        if model.latestSnapshot != nil || model.searchState != .idle, canSearch {
            search()
        }
    }

    /// A saved route tapped in its tab: fill it in and search it from now.
    private func applyPlannerRequest() {
        guard let request = model.takePlannerRequest() else { return }
        origin = model.station(for: request.origin)
        destination = model.station(for: request.destination)
        departure = Date()
        departureEdited = false
        inputDidChange()
        if canSearch {
            search()
        } else {
            searchWhenScheduleIsReady = true
        }
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
        if !departureEdited { departure = Date() }
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
