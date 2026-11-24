import SwiftUI
import BinarioKit

// Android's «Collegamenti diretti» (DirectConnectionsSheet.kt): the destinations a station reaches
// without changes on a day of the coming week, from the bundled timetable; a destination opens
// the planner to it («Cerca i treni», «Cerca le corse» for a city line).

/// The button of a board's station that opens the sheet.
struct DirectConnectionsButton: View {
    let station: Station
    @State private var shown = false

    var body: some View {
        Section {
            Button {
                shown = true
            } label: {
                Label("departures.direct.title", systemImage: "arrow.triangle.branch")
            }
        }
        .sheet(isPresented: $shown) {
            DirectConnectionsSheet(station: station)
        }
    }
}

struct DirectConnectionsSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let station: Station
    @State private var dayOffset = 0
    @State private var filter = ""
    @State private var connections: DirectConnections?

    private static let daysShown = 7

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(PersonalTexts.format("departures.direct.from", station.name))
                        .font(.headline)
                    Text("departures.direct.note")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(0..<Self.daysShown, id: \.self) { offset in
                                Button {
                                    dayOffset = offset
                                } label: {
                                    Text(DirectConnectionTexts.dayLabel(offset: offset))
                                }
                                .buttonStyle(.bordered)
                                .tint(offset == dayOffset ? Color.accentColor : Color.secondary)
                                .accessibilityAddTraits(offset == dayOffset ? .isSelected : [])
                            }
                        }
                    }
                    TextField("departures.direct.filter", text: $filter)
                        .autocorrectionDisabled()
                        .onChange(of: filter) { _, value in
                            if value.count > 60 { filter = String(value.prefix(60)) }
                        }
                }
                results
            }
            .navigationTitle("departures.direct.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("departures.direct.close") { dismiss() }
                }
            }
            .task(id: "\(station.id)|\(dayOffset)") { await load() }
        }
    }

    @ViewBuilder private var results: some View {
        if let connections, connections.date == date {
            if connections.isEmpty {
                Text("departures.direct.empty")
                    .foregroundStyle(.secondary)
            } else {
                let names = Dictionary((connections.rail + connections.urban).map { ($0.id, name($0)) }, uniquingKeysWith: { first, _ in first })
                let rail = DirectConnectionTexts.matching(connections.rail, filter, names: names)
                let urban = DirectConnectionTexts.matching(connections.urban, filter, names: names)
                if rail.isEmpty, urban.isEmpty {
                    Text(PersonalTexts.format("departures.direct.no_match", filter.trimmingCharacters(in: .whitespaces)))
                        .foregroundStyle(.secondary)
                }
                if !rail.isEmpty {
                    Section("departures.direct.trains") {
                        ForEach(rail) { destination in
                            DirectDestinationRow(origin: station.name, name: names[destination.id] ?? destination.name, destination: destination) {
                                search(destination)
                            }
                        }
                    }
                }
                if !urban.isEmpty {
                    Section("departures.direct.urban") {
                        ForEach(urban) { destination in
                            DirectDestinationRow(origin: station.name, name: names[destination.id] ?? destination.name, destination: destination) {
                                search(destination)
                            }
                        }
                    }
                }
            }
        } else {
            HStack {
                ProgressView()
                Text("departures.direct.loading")
            }
        }
    }

    private var date: PlannedLocalDate? { PersonalModel.today().adding(days: dayOffset) }

    /// A railway destination by its catalogue name; a city stop by the network's.
    private func name(_ destination: DirectDestination) -> String {
        destination.urban ? destination.name : (model.catalogStation(id: destination.stationID)?.name ?? destination.name)
    }

    private func load() async {
        guard let date else { return }
        if let known = DirectConnectionsMemory.get(stationID: station.id, date: date) {
            connections = known
            return
        }
        guard let repository = model.repository as? SQLiteGTFSJourneyRepository else {
            connections = DirectConnections(date: date, rail: [], urban: [])
            return
        }
        let found = await repository.directConnections(from: StationIdentity(station), on: date)
        guard !Task.isCancelled else { return }
        DirectConnectionsMemory.put(found, stationID: station.id)
        connections = found
    }

    /// Today searches from now; another day from the first departure to the destination (a first
    /// departure after midnight, 24:10, is 00:10 of the next calendar day).
    private func search(_ destination: DirectDestination) {
        guard let date else { return }
        let target = destination.urban
            ? StationIdentity(id: destination.stationID, name: destination.name)
            : (model.catalogStation(id: destination.stationID).map { StationIdentity($0) } ?? StationIdentity(id: destination.stationID, name: destination.name))
        var departure: Date?
        if dayOffset > 0 {
            let minute = destination.firstDepartureMinute
            let ofDay = minute % (24 * 60)
            if let day = date.adding(days: minute / (24 * 60)), let time = PlannedLocalTime(hour: ofDay / 60, minute: ofDay % 60) {
                departure = ItalianWallClock.instant(date: day, time: time)
            }
        }
        model.openPlanner(origin: StationIdentity(station), destination: target, departure: departure)
        dismiss()
    }
}

/// One destination: name, lines, runs, first and last departure, the fastest run and the city stop
/// it leaves from when that is a walk away.
private struct DirectDestinationRow: View {
    let origin: String
    let name: String
    let destination: DirectDestination
    let action: () -> Void

    var body: some View {
        let runs = String.localizedStringWithFormat(
            NSLocalizedString(destination.urban ? "departures.direct.run_count" : "departures.direct.train_count", comment: ""),
            destination.runs
        )
        let firstLast = PersonalTexts.format(
            "departures.direct.first_last",
            DirectConnectionTexts.clock(destination.firstDepartureMinute),
            DirectConnectionTexts.clock(destination.lastDepartureMinute)
        )
        let fastest = PersonalTexts.format("departures.direct.fastest", PersonalTexts.duration(destination.fastestMinutes))
        let lines: String? = destination.lines.isEmpty ? nil : destination.lines.joined(separator: " ")
        let boarding = destination.boardingStop.map { PersonalTexts.format("departures.direct.from_stop", $0) }
        // A metro, tram or funicular line runs, it is not a train.
        let actionName = PersonalTexts.format(destination.urban ? "departures.direct.search_runs" : "departures.direct.search_trains", origin, name)
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.primary)
                Text([lines, runs, firstLast, fastest].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let boarding {
                    Text(boarding)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text([name, lines, runs, firstLast, fastest, boarding].compactMap { $0 }.joined(separator: ", ")))
        .accessibilityHint(Text(actionName))
        .accessibilityAddTraits(.isButton)
    }
}

/// The pure parts of the sheet (Android's `DirectConnectionFilter`): the name filter, the clock
/// times and the days.
enum DirectConnectionTexts {
    /// The destinations whose name holds every word of `query`, case and accents aside; all of
    /// them for a blank query.
    static func matching(_ destinations: [DirectDestination], _ query: String, names: [String: String]) -> [DirectDestination] {
        let words = normalize(query).split(separator: " ")
        guard !words.isEmpty else { return destinations }
        return destinations.filter { destination in
            let named = normalize(names[destination.id] ?? destination.name)
            return words.allSatisfy { named.contains($0) }
        }
    }

    /// «06:05»; a minute of the service day after midnight (24:35) is the clock time it shows (00:35).
    static func clock(_ minute: Int) -> String {
        let ofDay = ((minute % (24 * 60)) + 24 * 60) % (24 * 60)
        return String(format: "%02d:%02d", ofDay / 60, ofDay % 60)
    }

    /// «Oggi 24 nov», then «mer 25 nov».
    static func dayLabel(offset: Int) -> String {
        guard let day = PersonalModel.today().adding(days: offset),
              let noon = PlannedLocalTime(hour: 12, minute: 0),
              let instant = ItalianWallClock.instant(date: day, time: noon) else { return "" }
        if offset == 0 { return PersonalTexts.format("departures.direct.today", EveningAlertTexts.dayMonth(day)) }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEdMMM")
        formatter.timeZone = ItalianWallClock.timeZone
        return formatter.string(from: instant)
    }

    private static func normalize(_ value: String) -> String {
        let folded = value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "it_IT")).lowercased()
        let spaced = String(folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " })
        return spaced.split(separator: " ").joined(separator: " ")
    }
}

/// The last few answers, so a day or a station chosen again shows at once (in memory only).
@MainActor
private enum DirectConnectionsMemory {
    private static let capacity = 6
    private static var entries: [(key: String, value: DirectConnections)] = []

    static func get(stationID: String, date: PlannedLocalDate) -> DirectConnections? {
        entries.first { $0.key == "\(stationID)|\(date.iso)" }?.value
    }

    static func put(_ value: DirectConnections, stationID: String) {
        let key = "\(stationID)|\(value.date.iso)"
        entries.removeAll { $0.key == key }
        entries.append((key: key, value: value))
        if entries.count > capacity { entries.removeFirst(entries.count - capacity) }
    }
}
