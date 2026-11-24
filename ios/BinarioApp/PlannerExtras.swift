import SwiftUI
import BinarioKit

// Android's planner modes and round trip on the iPhone: «Treno» (search by number), «Stazione»
// (the board) and «Andata e ritorno».

/// «Treno» and «Stazione», Android's other two planner modes.
struct PlannerModesSection: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Section {
            NavigationLink {
                TrainNumberSearchView()
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("planner.mode.train")
                        Text("planner.train.description")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "number")
                }
            }
            Button {
                model.selectedTab = .departures
            } label: {
                Label("planner.mode.station", systemImage: "list.bullet.rectangle")
            }
        }
    }
}

/// «Treno»: today's runs of a number from the live sources, independent of any route; a tap opens
/// the train as a planner row does.
struct TrainNumberSearchView: View {
    @EnvironmentObject private var model: AppModel
    /// A number from a link or a quick action, searched at once.
    var initialNumber: String = ""
    @State private var number = ""
    @State private var searching = false
    @State private var runs: [LiveRunStatus] = []
    @State private var messageKey: String?
    @State private var partial = false

    var body: some View {
        Form {
            Section {
                Text("planner.train.description")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                TextField("planner.train.number", text: $number, prompt: Text("planner.train.number_example"))
                    .keyboardType(.asciiCapable)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .onSubmit(search)
                Button(action: search) {
                    if searching {
                        HStack {
                            ProgressView()
                            Text("planner.train.loading")
                        }
                    } else {
                        Label("planner.search", systemImage: "magnifyingglass")
                    }
                }
                .disabled(searching || query == nil)
            }
            if !runs.isEmpty {
                Section {
                    ForEach(runs, id: \.id) { run in
                        TrainNumberRunRow(run: run)
                    }
                    if partial {
                        Text("planner.train.partial")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text(PersonalTexts.format("planner.train.heading", runs.first?.number ?? number))
                } footer: {
                    Text("planner.train.subtitle")
                }
            } else if let messageKey {
                Section {
                    Text(LocalizedStringKey(messageKey))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("planner.train.title")
        .onAppear {
            guard number.isEmpty, !initialNumber.isEmpty else { return }
            number = initialNumber
            search()
        }
    }

    /// Android's query: letters and digits, at least one digit, at most 32.
    private var query: String? {
        let cleaned = number.uppercased().filter { $0.isLetter || $0.isNumber }
        guard (1...32).contains(cleaned.count), cleaned.contains(where: \.isNumber) else { return nil }
        return cleaned
    }

    private func search() {
        guard let query, !searching else { return }
        searching = true
        messageKey = nil
        Task {
            let result = await model.trainNumberRuns(LiveTrainStatusRequest(number: query, serviceDate: Date()))
            searching = false
            switch result {
            case nil:
                runs = []
                messageKey = "planner.train.unavailable"
            case let .unavailable(reason)?:
                runs = []
                messageKey = reason == .notFound || reason == .notCovered ? "planner.train.not_found" : "planner.train.unavailable"
            case let .available(lookup)?:
                runs = lookup.runs.sorted { $0.scheduledDeparture < $1.scheduledDeparture }
                partial = !lookup.unavailableSources.isEmpty
                messageKey = runs.isEmpty ? "planner.train.not_found" : nil
            }
            DeparturesTexts.announce(runs.isEmpty ? NSLocalizedString(messageKey ?? "planner.train.not_found", comment: "")
                                                  : PersonalTexts.format("planner.train.heading", query))
        }
    }
}

/// One run of the number: route, departure date and time, state; opens the train's detail.
private struct TrainNumberRunRow: View {
    @EnvironmentObject private var model: AppModel
    let run: LiveRunStatus

    var body: some View {
        let label = VStack(alignment: .leading, spacing: 3) {
            Text(verbatim: "\(run.category) \(run.number) · \(run.operatorName)")
                .font(.headline)
            Text(verbatim: "\(run.originName) → \(run.destinationName)")
            Text(PersonalTexts.format("planner.train.run_date", run.scheduledDeparture.formatted(
                Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: ItalianWallClock.timeZone))))
                .font(.caption)
                .foregroundStyle(.secondary)
            if run.cancellation != LiveCancellation.none || run.state == .cancelled {
                Text(LiveTexts.cancelled)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BinarioPalette.statusError)
            } else if run.operationalStatusVerified, let delay = run.delayMinutes {
                Text(delay > 0 ? LiveTexts.delaySentence(delay) : LiveTexts.onTime)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(delay > 0 ? BinarioPalette.statusError : Color.primary)
            }
        }
        .accessibilityElement(children: .combine)
        if let pair = detail {
            NavigationLink {
                TrainDetailView(service: pair.service, query: pair.query)
            } label: { label }
            .accessibilityHint(Text("accessibility.open_train_detail"))
        } else {
            label
        }
    }

    /// The run as a planner row: boarded at its origin, measured at its destination.
    private var detail: (service: TrainService, query: JourneyQuery)? {
        func identity(_ name: String, code: String?) -> StationIdentity {
            if let station = model.catalogStation(named: name) { return StationIdentity(station) }
            return StationIdentity(id: "live:" + (code ?? name), name: name)
        }
        let origin = identity(run.originName, code: run.originCode)
        let destination = identity(run.destinationName, code: nil)
        guard let query = try? JourneyQuery(origin: origin, destination: destination, departure: run.scheduledDeparture) else { return nil }
        let service = TrainService(
            id: "live:\(run.id)",
            number: run.number,
            operatorName: run.operatorName,
            category: run.category,
            destination: run.destinationName,
            scheduledDeparture: LiveTexts.clock(run.scheduledDeparture),
            expectedDeparture: LiveTexts.clock(run.expectedDeparture),
            status: "",
            platform: run.platform,
            delayMinutes: run.delayMinutes ?? 0
        )
        return (service: service, query: query)
    }
}

/// «Andata e ritorno» in the planner's form: the switch and the return's date and time.
struct RoundTripFields: View {
    @Binding var roundTrip: Bool
    @Binding var returnDeparture: Date

    var body: some View {
        Toggle("planner.round_trip", isOn: $roundTrip)
        if roundTrip {
            DatePicker("planner.return", selection: $returnDeparture)
                .environment(\.timeZone, AppModel.timetableTimeZone)
        }
    }
}

/// «RITORNO»: the trains of the way back, as the outbound rows, each opening its detail.
struct ReturnResultsSection: View {
    @EnvironmentObject private var model: AppModel
    let result: JourneyResult

    var body: some View {
        Section {
            switch result {
            case let .available(snapshot):
                let co2 = model.co2Estimate(from: snapshot.query.origin, to: snapshot.query.destination)
                ForEach(snapshot.services, id: \.id) { service in
                    NavigationLink {
                        TrainDetailView(service: service, query: snapshot.query)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(service.scheduledDeparture)
                                    .font(.headline.monospacedDigit())
                                Text(service.isUrban ? VehicleTexts.rowLabel(service) : service.number)
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
                            if let co2 { Co2Line(estimate: co2) }
                        }
                        .accessibilityElement(children: .combine)
                    }
                    .accessibilityHint(Text("accessibility.open_train_detail"))
                }
                Label("result.programmed.disclosure", systemImage: "clock")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            case .unavailable:
                Text("planner.return.empty_title")
                    .font(.body.weight(.semibold))
                Text("planner.return.empty_body")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("result.share.return_title")
        }
    }
}
