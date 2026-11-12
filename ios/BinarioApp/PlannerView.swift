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
    /// The minute the results are read at: «Più puntuale» never suggests a train that has left.
    @State private var clock = Date()

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

                if !model.airportLinks.isEmpty, model.latestSnapshot == nil, model.searchState == .idle {
                    AirportLinksSection(links: model.airportLinks, search: searchAirport)
                }

                if let snapshot = model.latestSnapshot {
                    AvailableResultView(snapshot: snapshot, now: clock)
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
        .task { await followTheClock() }
        // The live values of the results are asked only while the planner is on screen.
        .onAppear { model.plannerLiveDidAppear() }
        .onDisappear { model.plannerLiveDidDisappear() }
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
        // The clock's task may have slept through the time in the background.
        clock = Date()
        guard !departureEdited else { return }
        departure = Date()
        if model.latestSnapshot != nil || model.searchState != .idle, canSearch {
            search()
        }
    }

    /// Moves `clock` at every new minute while the planner is on screen (the task ends when it
    /// leaves the screen and starts again, from the current time, when it comes back).
    private func followTheClock() async {
        while !Task.isCancelled {
            let current = Date()
            clock = current
            let seconds = 60 - current.timeIntervalSince1970.truncatingRemainder(dividingBy: 60)
            try? await Task.sleep(nanoseconds: UInt64((seconds + 0.5) * 1_000_000_000))
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

    /// An airport link: fill the form and search it from now, as a saved route does.
    private func searchAirport(from origin: Station, to destination: Station) {
        model.openPlanner(origin: StationIdentity(origin), destination: StationIdentity(destination))
    }
}

/// «Collegamenti con gli aeroporti»: a tap searches towards the airport, the button beside it the
/// way back. Station names as the catalogue has them; no service brand.
private struct AirportLinksSection: View {
    let links: [AirportLink]
    let search: @MainActor (Station, Station) -> Void

    var body: some View {
        Section("planner.airports.title") {
            ForEach(links) { link in
                HStack {
                    Button {
                        search(link.city, link.airport)
                    } label: {
                        Label {
                            Text(verbatim: "\(link.city.name) → \(link.airport.name)")
                        } icon: {
                            Image(systemName: "airplane.departure")
                        }
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text(ReliabilityTexts.airportSearch(from: link.city.name, to: link.airport.name)))
                    Spacer()
                    Button {
                        search(link.airport, link.city)
                    } label: {
                        Image(systemName: "arrow.uturn.backward")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text(ReliabilityTexts.airportSearch(from: link.airport.name, to: link.city.name)))
                }
            }
        }
    }
}

/// How the results are listed: by departure, or «Più affidabili» by the network statistics.
private enum ResultOrder: Hashable {
    case departure
    case mostReliable
}

private struct AvailableResultView: View {
    @EnvironmentObject private var model: AppModel
    let snapshot: JourneySnapshot
    /// The current minute: a listed train is gone a minute after its departure (scheduled, or
    /// delayed by the minutes a live source publishes).
    let now: Date
    @State private var order: ResultOrder = .departure

    var body: some View {
        // The statistics of this result: computed by the model when the search or the file arrives.
        let reliability = model.resultReliability
        let measuredTrains = reliability?.byServiceID ?? [:]
        // «Più puntuale: …» under a train often late at the destination: another listed train
        // leaving within 30 minutes, still to be taken (not cancelled, its live delay counted),
        // clearly more often on time there.
        let alternatives = ReliableAlternatives.find(in: snapshot, reliability: measuredTrains, live: model.resultLive, now: now)
        Section("result.title") {
            if let route = reliability?.route {
                RoutePunctualityView(route: route)
            }
            if !measuredTrains.isEmpty {
                Picker("result.order.title", selection: $order) {
                    Text("result.order.departure").tag(ResultOrder.departure)
                    Text("result.order.most_reliable").tag(ResultOrder.mostReliable)
                }
                .pickerStyle(.segmented)
            }
            ForEach(orderedServices(reliability), id: \.id) { service in
                let alternative = alternatives[service.id]
                let live = model.resultLive[service.id]
                NavigationLink {
                    TrainDetailView(service: service, query: snapshot.query, boardLive: live)
                } label: {
                    ResultRow(service: service, reliability: measuredTrains[service.id], live: live)
                }
                .accessibilityHint(Text("accessibility.open_train_detail"))
                .listRowSeparator(alternative == nil ? .automatic : .hidden, edges: .bottom)
                if let alternative {
                    // Opens the suggested train as a tap on its own row does.
                    NavigationLink {
                        TrainDetailView(service: alternative.service, query: snapshot.query)
                    } label: {
                        ReliableAlternativeLine(alternative: alternative)
                    }
                    .accessibilityHint(Text(ReliabilityTexts.alternativeOpen(alternative)))
                    .listRowSeparator(.hidden, edges: .top)
                }
            }

            if let reliability, !reliability.isEmpty {
                Text(ReliabilityTexts.attribution(reliability.windowEnd))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text("\(String(localized: "result.source_label")) \(snapshot.provenance.sourceLabel)")
                .font(.footnote)
                .foregroundStyle(.secondary)
            // The timetable's trains without a live value keep the disclosure, as in «Partenze».
            if snapshot.services.contains(where: { model.resultLive[$0.id] == nil }) {
                Label("result.programmed.disclosure", systemImage: "clock")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            LiveSourcesFooter(state: model.resultLiveState, sources: model.resultLiveSources, pauses: model.liveSourcePauses)
        }
    }

    /// The departure order of the repository, or «Più affidabili» when chosen and measurable.
    private func orderedServices(_ reliability: JourneyReliability?) -> [TrainService] {
        guard order == .mostReliable, let reliability, !reliability.byServiceID.isEmpty else { return snapshot.services }
        return reliability.mostReliableFirst(snapshot.services)
    }
}

/// One listed train: time, number, category, its live state at the origin when the live board
/// lists it (delay, cancellation, platform and their source, read as one sentence), destination and
/// operator, then bikes on board and the unofficial statistics at the searched destination when known.
private struct ResultRow: View {
    let service: TrainService
    let reliability: StopReliability?
    let live: StationBoardLiveStatus?

    var body: some View {
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
            if let live {
                LiveStatusLine(status: live, showsPlatform: true)
            }
            Text(service.destination)
            Text(service.operatorName)
                .font(.caption)
                .foregroundStyle(.secondary)
            if let bikesAllowed = service.bikesAllowed {
                BikesLabel(allowed: bikesAllowed)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let reliability {
                let parts = ReliabilityTexts.trainParts(reliability)
                Label {
                    Text(parts.joined(separator: " · "))
                } icon: {
                    Image(systemName: "chart.bar")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityLabel(Text(ReliabilityTexts.spoken(parts.joined(separator: ", "))))
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// «Più puntuale: REG 16000 delle 08:12 · in orario 9 volte su 10» under a train often late at the
/// searched destination: a row of its own that opens the suggested train, read as one sentence
/// that says the statistics are unofficial; what a tap does is its hint.
private struct ReliableAlternativeLine: View {
    let alternative: ResultAlternative

    var body: some View {
        let parts = ReliabilityTexts.alternativeParts(alternative)
        Label {
            Text(parts.joined(separator: " · "))
        } icon: {
            Image(systemName: "arrow.up.arrow.down")
        }
        .font(.caption.weight(.medium))
        .foregroundStyle(.tint)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(ReliabilityTexts.spoken(parts.joined(separator: ", "))))
    }
}

/// «Questa tratta: 83% in orario · …» at the top of the results, with the attribution below the list.
private struct RoutePunctualityView: View {
    let route: RoutePunctuality

    var body: some View {
        let lines = ReliabilityTexts.routeLines(route)
        Label {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(lines.indices, id: \.self) { index in
                    Text(lines[index].joined(separator: " · "))
                }
            }
        } icon: {
            Image(systemName: "chart.bar")
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(ReliabilityTexts.spoken(lines.map { $0.joined(separator: ", ") }.joined(separator: ". "))))
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
