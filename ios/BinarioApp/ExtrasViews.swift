import SwiftUI
import BinarioKit

/// The train detail's sections beyond the run itself, in Android's order: the delay compensation
/// notice (on a verified actual arrival at least 60 minutes late) and the weather at the destination.
struct TrainDetailExtrasSections: View {
    let service: TrainService
    let query: JourneyQuery
    /// The run as the live sources published it, when the detail has it.
    let liveRun: LiveRunStatus?

    var body: some View {
        if let liveRun, let compensation = DelayCompensationRule.of(liveRun) {
            DelayCompensationSection(compensation: compensation)
        }
        DestinationWeatherSection(service: service, query: query, liveRun: liveRun)
    }
}

/// Regulation (EU) 2021/782, art. 19: what the delay at the destination grants, and its limits.
/// It states the rule only; the app knows nothing of the ticket.
struct DelayCompensationSection: View {
    let compensation: DelayCompensation

    var body: some View {
        let delay = String.localizedStringWithFormat(NSLocalizedString("result.compensation.minutes", comment: ""), compensation.delayMinutes)
        let notice = compensation.share == .half
            ? String.localizedStringWithFormat(NSLocalizedString("result.compensation.half", comment: ""), compensation.stationName, delay)
            : String.localizedStringWithFormat(NSLocalizedString("result.compensation.quarter", comment: ""), compensation.stationName, delay)
        Section {
            Label {
                Text(notice)
                    .font(.body.weight(.semibold))
            } icon: {
                Image(systemName: "eurosign.circle")
            }
            Text("result.compensation.conditions")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

/// «Meteo a destinazione»: the Open-Meteo forecast of the arrival day for the last station shown,
/// placed with the catalogue's coordinates. Asked only when the detail is shown and the switch is
/// on; a station without catalogue coordinates shows nothing rather than a guessed place.
struct DestinationWeatherSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var extras: ExtrasModel
    let service: TrainService
    let query: JourneyQuery
    let liveRun: LiveRunStatus?

    var body: some View {
        if extras.destinationWeatherEnabled, let today = ItalianWallClock.date(of: Date()), let target = target(today: today),
           case let .available(catalog) = model.catalogState,
           let station = extras.coordinates(of: target.stationName, catalogue: catalog.stations) {
            let key = ExtrasModel.weatherKey(station, date: target.arrivalDate)
            Section {
                Text(String.localizedStringWithFormat(
                    NSLocalizedString("result.weather.station_date", comment: ""), station.name, WeatherTexts.day(target.arrivalDate)
                ))
                .font(.footnote)
                .foregroundStyle(.secondary)
                switch extras.weather[key] {
                case .none, .loading?:
                    Text("result.weather.loading")
                        .foregroundStyle(.secondary)
                case let .ready(result)?:
                    WeatherResultRows(result: result)
                }
                // CC BY 4.0: the attribution is shown with every state; the page opens only on a tap.
                if let page = DestinationWeatherService.attributionURL {
                    Link(destination: page) {
                        Text("result.weather.attribution")
                            .font(.caption)
                    }
                }
            } header: {
                Label("result.weather.title", systemImage: "cloud.sun")
            }
            .task(id: key) { extras.loadWeather(station, date: target.arrivalDate) }
        }
    }

    /// The last stop of the run the sources published, else the train's destination on the day of
    /// the search (the bundled timetable has no arrival time).
    private func target(today: PlannedLocalDate) -> DestinationWeatherTarget? {
        if let liveRun, let target = DestinationWeatherTarget.of(liveRun, today: today) { return target }
        return DestinationWeatherTarget.of(
            lastStop: nil, lastScheduled: nil, destination: service.destination,
            departureDate: ItalianWallClock.date(of: query.departure), departure: service.scheduledDeparture, today: today
        )
    }
}

private struct WeatherResultRows: View {
    let result: DestinationWeatherResult

    var body: some View {
        switch result {
        case .outOfRange:
            Text("result.weather.out_of_range")
                .foregroundStyle(.secondary)
        case .unavailable:
            Text("result.weather.unavailable")
                .foregroundStyle(.secondary)
        case let .forecast(day, stale):
            VStack(alignment: .leading, spacing: 4) {
                Text(WeatherTexts.description(day.weatherCode))
                    .font(.body.weight(.semibold))
                if let maximum = day.maxTemperatureC, let minimum = day.minTemperatureC {
                    Text(String.localizedStringWithFormat(
                        NSLocalizedString("result.weather.temperatures", comment: ""), WeatherTexts.degrees(maximum), WeatherTexts.degrees(minimum)
                    ))
                }
                if let rain = day.precipitationProbabilityPercent {
                    Text(String.localizedStringWithFormat(NSLocalizedString("result.weather.precipitation", comment: ""), rain))
                }
            }
            .accessibilityElement(children: .combine)
            if stale {
                Text("notices.stale")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// The texts of the forecast: every WMO code Open-Meteo publishes has its own (Android's
/// `wmoDescription`), the numbers in the device's language.
enum WeatherTexts {
    static let descriptions: [Int: String] = [
        0: "result.weather.code.0", 1: "result.weather.code.1", 2: "result.weather.code.2", 3: "result.weather.code.3",
        45: "result.weather.code.45", 48: "result.weather.code.48", 51: "result.weather.code.51", 53: "result.weather.code.53",
        55: "result.weather.code.55", 56: "result.weather.code.56", 57: "result.weather.code.57", 61: "result.weather.code.61",
        63: "result.weather.code.63", 65: "result.weather.code.65", 66: "result.weather.code.66", 67: "result.weather.code.67",
        71: "result.weather.code.71", 73: "result.weather.code.73", 75: "result.weather.code.75", 77: "result.weather.code.77",
        80: "result.weather.code.80", 81: "result.weather.code.81", 82: "result.weather.code.82", 85: "result.weather.code.85",
        86: "result.weather.code.86", 95: "result.weather.code.95", 96: "result.weather.code.96", 99: "result.weather.code.99",
    ]

    static func description(_ code: Int?) -> LocalizedStringKey {
        LocalizedStringKey(code.flatMap { descriptions[$0] } ?? "result.weather.code.unknown")
    }

    /// «12,5»: at most one decimal, in the device's language.
    static func degrees(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 1
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// The arrival day in the device's language («23 nov 2026»), read on the Italian calendar.
    static func day(_ date: PlannedLocalDate) -> String {
        guard let time = PlannedLocalTime(hour: 12, minute: 0), let instant = ItalianWallClock.instant(date: date, time: time) else { return date.iso }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        formatter.timeZone = ItalianWallClock.timeZone
        return formatter.string(from: instant)
    }
}

/// The bottom of the station board, in Android's order: «Treni in movimento» around the station
/// and «Bici in condivisione vicino a …».
struct BoardExtrasSections: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let identity = model.boardStation {
            let station = model.station(for: identity)
            StationLiveTrainsEntry(station: station)
            BikeSharingSection(station: station)
        }
    }
}

/// «Bici in condivisione vicino a <stazione>»: docking stations and free-floating bikes or
/// scooters within 300 m of the station's own coordinates (never the device position), from the
/// GBFS feeds of the systems serving that place. Nothing is asked until the user taps the button;
/// a station no system of this build serves shows nothing.
struct BikeSharingSection: View {
    @EnvironmentObject private var extras: ExtrasModel
    let station: Station

    var body: some View {
        if extras.servesBikes(station) {
            Section {
                switch extras.bikes[station.id] {
                case .none:
                    Button {
                        extras.loadBikes(station)
                    } label: {
                        Label("departures.bikes.show", systemImage: "bicycle")
                    }
                case .loading?:
                    Text("departures.bikes.loading")
                        .foregroundStyle(.secondary)
                case let .ready(result)?:
                    switch result {
                    case .notCovered:
                        Text("departures.bikes.none")
                    case .unavailable:
                        Text("departures.bikes.unavailable")
                            .foregroundStyle(.secondary)
                        Button("departures.bikes.show") {
                            extras.resetBikes(station)
                            extras.loadBikes(station)
                        }
                    case let .loaded(systems):
                        if systems.allSatisfy({ $0.docks.isEmpty && $0.vehicles.isEmpty }) {
                            Text("departures.bikes.none")
                        }
                        ForEach(systems.indices, id: \.self) { index in
                            BikeSharingSystemRows(nearby: systems[index])
                        }
                    }
                }
            } header: {
                Text(String.localizedStringWithFormat(NSLocalizedString("departures.bikes.title", comment: ""), station.name))
            }
        }
    }
}

private struct BikeSharingSystemRows: View {
    let nearby: BikeSharingNearby

    var body: some View {
        if !nearby.docks.isEmpty || !nearby.vehicles.isEmpty {
            Text(verbatim: nearby.system.name)
                .font(.subheadline.weight(.bold))
            ForEach(nearby.docks.indices, id: \.self) { index in
                let dock = nearby.docks[index]
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: dock.name)
                    Text(BikeTexts.dockParts(dock).joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
            ForEach(nearby.vehicles.indices, id: \.self) { index in
                Text(BikeTexts.vehicleParts(nearby.vehicles[index]).joined(separator: " · "))
            }
            Text(String.localizedStringWithFormat(
                NSLocalizedString("departures.bikes.source", comment: ""), nearby.system.attribution, LiveTexts.clock(nearby.observedAt)
            ))
            .font(.footnote)
            .foregroundStyle(.secondary)
            if nearby.stale {
                Text("notices.stale")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let page = URL(string: nearby.system.websiteURL) {
                Link(destination: page) {
                    Text(String.localizedStringWithFormat(NSLocalizedString("departures.bikes.open", comment: ""), nearby.system.name))
                        .font(.footnote)
                }
            }
        }
    }
}

enum BikeTexts {
    static func distance(_ meters: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.bikes.distance", comment: ""), meters)
    }

    static func dockParts(_ dock: NearbyDock) -> [String] {
        var parts = [distance(dock.distanceMeters),
                     String.localizedStringWithFormat(NSLocalizedString("departures.bikes.dock_bikes", comment: ""), dock.bikesAvailable)]
        if let ebikes = dock.ebikesAvailable, ebikes > 0 {
            parts.append(String.localizedStringWithFormat(NSLocalizedString("departures.bikes.dock_ebikes", comment: ""), ebikes))
        }
        if let free = dock.docksAvailable, free > 0 {
            parts.append(String.localizedStringWithFormat(NSLocalizedString("departures.bikes.dock_free", comment: ""), free))
        }
        return parts
    }

    static func vehicleParts(_ vehicle: NearbyVehicle) -> [String] {
        var parts = [kind(vehicle.kind), distance(vehicle.distanceMeters)]
        if let battery = vehicle.batteryPercent {
            parts.append(String.localizedStringWithFormat(NSLocalizedString("departures.bikes.battery", comment: ""), battery))
        }
        return parts
    }

    static func kind(_ kind: SharedVehicleKind) -> String {
        switch kind {
        case .bike: return String(localized: "departures.bikes.type_bike")
        case .ebike: return String(localized: "departures.bikes.type_ebike")
        case .scooter: return String(localized: "departures.bikes.type_scooter")
        case .other: return String(localized: "departures.bikes.type_other")
        }
    }
}

/// Settings: «Meteo a destinazione» and the two evening alerts, each with what it asks and when.
struct ExtrasSettingsSection: View {
    @EnvironmentObject private var extras: ExtrasModel

    var body: some View {
        Section {
            Toggle(
                "settings.weather.title",
                isOn: Binding(get: { extras.destinationWeatherEnabled }, set: { extras.setDestinationWeatherEnabled($0) })
            )
            Text("settings.weather.detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Toggle(
                "settings.alerts.strike_title",
                isOn: Binding(get: { extras.strikeReminderEnabled }, set: { extras.setStrikeReminderEnabled($0) })
            )
            Text("settings.alerts.strike_detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Toggle(
                "settings.alerts.works_title",
                isOn: Binding(get: { extras.routeWorksAlertEnabled }, set: { extras.setRouteWorksAlertEnabled($0) })
            )
            Text("settings.alerts.works_detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            // Android's two background jobs of the favourite trains (EveningAlerts.swift).
            Toggle(
                "settings.punctuality_recording.title",
                isOn: Binding(get: { extras.punctualityRecordingEnabled }, set: { extras.setPunctualityRecordingEnabled($0) })
            )
            Text("settings.punctuality_recording.detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Toggle(
                "settings.weekly_digest.title",
                isOn: Binding(get: { extras.weeklyDigestEnabled }, set: { extras.setWeeklyDigestEnabled($0) })
            )
            Text("settings.weekly_digest.detail")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}
